/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.toModelContextProfile
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageError
import selfgemma.talk.ui.common.chat.ChatMessageLoading
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatMessageThinking
import selfgemma.talk.ui.common.chat.ChatMessageType
import selfgemma.talk.ui.common.chat.ChatMessageWarning
import selfgemma.talk.ui.common.chat.ChatSide
import selfgemma.talk.ui.common.chat.ChatViewModel
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "AGLlmChatViewModel"
private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 50L
private const val MAX_RESET_SESSION_RETRIES = 3

private data class LlmChatPreparationResult(
  val errorMessage: String? = null,
  val overflowDetected: Boolean = false,
)

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase() : ChatViewModel() {
  private val contextManager = LlmChatContextManager()

  fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    currentTurnMessages: List<ChatMessage> = listOf(),
    currentSystemPrompt: String = "",
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)
      val retainedMessageCount = getMessages(model = model).size

      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      val contextProfile = model.toModelContextProfile()
      var attemptMode = LlmChatContextMode.FULL
      var contextPlan =
        buildContextPlan(
          model = model,
          currentTurnMessages = currentTurnMessages,
          currentSystemPrompt = currentSystemPrompt,
          currentInput = input,
          imageCount = images.size,
          audioCount = audioMessages.size,
          contextProfile = contextProfile,
          preferredMode = attemptMode,
        )
      if (contextPlan.report.currentTurnOverflowDetected) {
        Log.w(
          TAG,
          "llmchat current turn exceeds usable input budget model=${model.name} reservedForCurrentTurnTokens=${contextPlan.report.reservedForCurrentTurnTokens} usableInputTokens=${contextPlan.report.usableInputTokens}",
        )
        setInProgress(false)
        setPreparing(false)
        onError(
          LlmChatOverflowRecovery.toUserMessage(
            "Input token exceeds model limit for the current turn."
          )
        )
        return@launch
      }
      if (LlmChatOverflowRecovery.shouldUseAggressiveModePreflight(contextPlan.report)) {
        attemptMode = LlmChatContextMode.AGGRESSIVE
        Log.w(
          TAG,
          "llmchat preflight overflow model=${model.name} estimatedInstructionTokens=${contextPlan.report.estimatedInstructionTokens} availableInstructionTokens=${contextPlan.report.availableInstructionTokens}",
        )
        contextPlan =
          buildContextPlan(
            model = model,
            currentTurnMessages = currentTurnMessages,
            currentSystemPrompt = currentSystemPrompt,
            currentInput = input,
            imageCount = images.size,
            audioCount = audioMessages.size,
            contextProfile = contextProfile,
            preferredMode = attemptMode,
          )
      }

      var preparationResult =
        prepareConversationForAttempt(
          model = model,
          plan = contextPlan,
          supportImage = supportImage,
          supportAudio = supportAudio,
        )
      if (preparationResult.errorMessage != null) {
        if (
          preparationResult.overflowDetected &&
            attemptMode != LlmChatContextMode.AGGRESSIVE
        ) {
          attemptMode = LlmChatContextMode.AGGRESSIVE
          contextPlan =
            buildContextPlan(
              model = model,
              currentTurnMessages = currentTurnMessages,
              currentSystemPrompt = currentSystemPrompt,
              currentInput = input,
              imageCount = images.size,
              audioCount = audioMessages.size,
              contextProfile = contextProfile,
              preferredMode = attemptMode,
            )
          preparationResult =
            prepareConversationForAttempt(
              model = model,
              plan = contextPlan,
              supportImage = supportImage,
              supportAudio = supportAudio,
            )
        }
        if (preparationResult.errorMessage != null) {
          setInProgress(false)
          setPreparing(false)
          onError(preparationResult.errorMessage)
          return@launch
        }
      }

      val audioClips = audioMessages.map { it.genByteArrayForWav() }
      val start = System.currentTimeMillis()
      var firstRun = true
      var overflowRetries = 0
      var pendingTextUpdate = StringBuilder()
      var pendingThinkingUpdate = StringBuilder()
      var lastTextUiUpdateAt = 0L
      var lastThinkingUiUpdateAt = 0L

      fun flushPendingThinkingUpdate(force: Boolean = false) {
        if (pendingThinkingUpdate.isEmpty()) {
          return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastThinkingUiUpdateAt < STREAM_UI_UPDATE_MIN_INTERVAL_MS) {
          return
        }

        updateLastThinkingMessageContentIncrementally(
          model = model,
          partialContent = pendingThinkingUpdate.toString(),
        )
        pendingThinkingUpdate = StringBuilder()
        lastThinkingUiUpdateAt = now
      }

      fun flushPendingTextUpdate(force: Boolean = false, latencyMs: Float = -1f) {
        val now = System.currentTimeMillis()
        if (!force) {
          if (pendingTextUpdate.isEmpty()) {
            return
          }
          if (now - lastTextUiUpdateAt < STREAM_UI_UPDATE_MIN_INTERVAL_MS) {
            return
          }
        }

        updateLastTextMessageContentIncrementally(
          model = model,
          partialContent = pendingTextUpdate.toString(),
          latencyMs = latencyMs,
        )
        pendingTextUpdate = StringBuilder()
        lastTextUiUpdateAt = now
      }

      fun restartAttemptUi() {
        pendingTextUpdate = StringBuilder()
        pendingThinkingUpdate = StringBuilder()
        lastTextUiUpdateAt = 0L
        lastThinkingUiUpdateAt = 0L
        truncateMessages(model = model, size = retainedMessageCount)
        addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))
        setInProgress(true)
        setPreparing(true)
      }

      fun startInferenceAttempt() {
        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (partialResult.startsWith("<ctrl")) {
            } else {
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = !thinkingText.isNullOrEmpty()
              var currentLastMessage = getLastMessage(model = model)

              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                pendingThinkingUpdate.append(thinkingText!!)
                flushPendingThinkingUpdate(force = done)
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  flushPendingThinkingUpdate(force = true)
                  val thinkingMessage = currentLastMessage as ChatMessageThinking
                  if (thinkingMessage.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMessage.content,
                          inProgress = false,
                          side = thinkingMessage.side,
                          accelerator = thinkingMessage.accelerator,
                          hideSenderLabel = thinkingMessage.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                val latencyMs = if (done) (System.currentTimeMillis() - start).toFloat() else -1f
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  pendingTextUpdate.append(partialResult)
                  flushPendingTextUpdate(force = done || wasLoading, latencyMs = latencyMs)
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMessage = finalLastMessage as ChatMessageThinking
                  if (thinkingMessage.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMessage.content,
                          inProgress = false,
                          side = thinkingMessage.side,
                          accelerator = thinkingMessage.accelerator,
                          hideSenderLabel = thinkingMessage.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                setInProgress(false)
                onDone()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          flushPendingThinkingUpdate(force = true)
          flushPendingTextUpdate(force = true)
          setInProgress(false)
          setPreparing(false)
        }

        val errorListener: (String) -> Unit = { message ->
          if (
            overflowRetries < LlmChatOverflowRecovery.MAX_OVERFLOW_RETRIES &&
              LlmChatOverflowRecovery.isContextOverflow(message)
          ) {
            overflowRetries += 1
            viewModelScope.launch(Dispatchers.Default) {
              Log.w(
                TAG,
                "Retrying llmchat after overflow model=${model.name} retry=$overflowRetries",
              )
              restartAttemptUi()
              attemptMode = LlmChatContextMode.AGGRESSIVE
              contextPlan =
                buildContextPlan(
                  model = model,
                  currentTurnMessages = currentTurnMessages,
                  currentSystemPrompt = currentSystemPrompt,
                  currentInput = input,
                  imageCount = images.size,
                  audioCount = audioMessages.size,
                  contextProfile = contextProfile,
                  preferredMode = attemptMode,
                )
              val retryPreparationResult =
                prepareConversationForAttempt(
                  model = model,
                  plan = contextPlan,
                  supportImage = supportImage,
                  supportAudio = supportAudio,
                )
              if (retryPreparationResult.errorMessage != null) {
                setInProgress(false)
                setPreparing(false)
                onError(retryPreparationResult.errorMessage)
                return@launch
              }
              startInferenceAttempt()
            }
          } else {
            Log.e(TAG, "Error occurred while running inference")
            flushPendingThinkingUpdate(force = true)
            flushPendingTextUpdate(
              force = true,
              latencyMs = (System.currentTimeMillis() - start).toFloat(),
            )
            setInProgress(false)
            setPreparing(false)
            onError(message)
          }
        }

        try {
          model.runtimeHelper.runInference(
            model = model,
            input = input,
            images = images,
            audioClips = audioClips,
            resultListener = resultListener,
            cleanUpListener = cleanUpListener,
            onError = errorListener,
            coroutineScope = viewModelScope,
            extraContext = extraContext,
          )
        } catch (exception: Exception) {
          Log.e(TAG, "Error occurred while running inference", exception)
          setInProgress(false)
          setPreparing(false)
          onError(exception.message ?: "")
        }
      }

      startInferenceAttempt()
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      var retries = 0
      var failureMessage: String? = null
      while (retries < MAX_RESET_SESSION_RETRIES) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          )
          setIsResettingSession(false)
          onDone()
          return@launch
        } catch (exception: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again", exception)
          failureMessage = exception.message ?: "Failed to reset the session."
          if (LlmChatOverflowRecovery.isContextOverflow(exception.message)) {
            break
          }
        }
        retries += 1
        if (retries < MAX_RESET_SESSION_RETRIES) {
          delay(200)
        }
      }
      Log.e(
        TAG,
        "Failed to reset session after retries model=${model.name} retries=$retries message=$failureMessage",
      )
      setIsResettingSession(false)
      addMessage(
        model = model,
        message =
          ChatMessageError(
            content = LlmChatOverflowRecovery.toUserMessage(failureMessage),
          ),
      )
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    currentSystemPrompt: String = "",
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      while (model.instance == null) {
        delay(100)
      }

      val clonedMessage = message.clone()
      addMessage(model = model, message = clonedMessage)

      generateResponse(
        model = model,
        input = message.content,
        currentTurnMessages = listOf(clonedMessage),
        currentSystemPrompt = currentSystemPrompt,
        onError = onError,
        allowThinking = allowThinking,
        supportImage = supportImage,
        supportAudio = supportAudio,
      )
    }
  }

  private fun buildContextPlan(
    model: Model,
    currentTurnMessages: List<ChatMessage>,
    currentSystemPrompt: String,
    currentInput: String,
    imageCount: Int,
    audioCount: Int,
    contextProfile: ModelContextProfile,
    preferredMode: LlmChatContextMode,
  ): LlmChatContextPlan {
    val allMessages = getMessages(model = model)
    val priorMessages = allMessages.take((allMessages.size - currentTurnMessages.size).coerceAtLeast(0))
    return contextManager.buildPlan(
      baseSystemPrompt = resolveBaseSystemPrompt(model = model, currentSystemPrompt = currentSystemPrompt),
      historyMessages = priorMessages,
      pendingInput = currentInput,
      pendingImageCount = imageCount,
      pendingAudioCount = audioCount,
      contextProfile = contextProfile,
      preferredMode = preferredMode,
    )
  }

  private fun prepareConversationForAttempt(
    model: Model,
    plan: LlmChatContextPlan,
    supportImage: Boolean,
    supportAudio: Boolean,
  ): LlmChatPreparationResult {
    val persistentSessionConfig =
      (model.instance as? LlmModelInstance)?.sessionConfig ?: LlmConversationSessionConfig()
    return try {
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
        systemInstruction = plan.systemInstruction,
        tools = persistentSessionConfig.tools,
        enableConversationConstrainedDecoding =
          persistentSessionConfig.enableConversationConstrainedDecoding,
      )
      (model.instance as? LlmModelInstance)?.sessionConfig = persistentSessionConfig
      Log.d(
        TAG,
        "Prepared llmchat conversation model=${model.name} mode=${plan.report.mode} estimatedInstructionTokens=${plan.report.estimatedInstructionTokens} availableInstructionTokens=${plan.report.availableInstructionTokens} recentLines=${plan.report.recentLineCount} summaryLines=${plan.report.summaryLineCount} droppedLines=${plan.report.droppedLineCount} tools=${persistentSessionConfig.tools.size} constrained=${persistentSessionConfig.enableConversationConstrainedDecoding}",
      )
      LlmChatPreparationResult()
    } catch (exception: Exception) {
      Log.w(
        TAG,
        "Failed to prepare llmchat conversation model=${model.name} mode=${plan.report.mode}",
        exception,
      )
      LlmChatPreparationResult(
        errorMessage = exception.message ?: "",
        overflowDetected = LlmChatOverflowRecovery.isContextOverflow(exception.message),
      )
    }
  }

  private fun resolveBaseSystemPrompt(model: Model, currentSystemPrompt: String): String {
    val configuredSystemPrompt =
      (model.instance as? LlmModelInstance)?.sessionConfig?.systemInstructionText.orEmpty()
    return configuredSystemPrompt.ifBlank { currentSystemPrompt }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    addMessage(
      model = model,
      message = ChatMessageError(content = LlmChatOverflowRecovery.toUserMessage(errorMessage)),
    )

    if (LlmChatOverflowRecovery.isContextOverflow(errorMessage)) {
      return
    }

    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel class LlmChatViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
