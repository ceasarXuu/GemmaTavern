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

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.ExperimentalApi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.toModelContextProfile
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageLoading
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatMessageThinking
import selfgemma.talk.ui.common.chat.ChatMessageType
import selfgemma.talk.ui.common.chat.ChatSide
import selfgemma.talk.ui.common.chat.ChatViewModel

private const val TAG = "AGLlmChatViewModel"
private const val STREAM_UI_UPDATE_MIN_INTERVAL_MS = 50L

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase() : ChatViewModel() {
  internal val contextManager = LlmChatContextManager()

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
}

@HiltViewModel class LlmChatViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()

