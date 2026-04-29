package selfgemma.talk.domain.roleplay.usecase

import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.runtime.LlmModelHelper

private const val MODEL_READY_TIMEOUT_MS = 60_000L
private const val MODEL_READY_POLL_INTERVAL_MS = 50L

internal suspend fun awaitModelReady(
  model: Model,
  isStopRequested: () -> Boolean,
): ModelReadinessResult {
  val startTime = System.currentTimeMillis()
  var sawInitialization = model.initializing

  while (model.instance == null) {
    if (isStopRequested()) {
      return ModelReadinessResult(ready = false, interrupted = true)
    }

    sawInitialization = sawInitialization || model.initializing
    if (sawInitialization && !model.initializing) {
      return ModelReadinessResult(
        ready = false,
        errorMessage = "Selected model failed to initialize.",
      )
    }

    if (System.currentTimeMillis() - startTime >= MODEL_READY_TIMEOUT_MS) {
      return ModelReadinessResult(
        ready = false,
        errorMessage = "Selected model is still preparing.",
      )
    }

    delay(MODEL_READY_POLL_INTERVAL_MS)
  }

  return ModelReadinessResult(ready = true)
}

internal fun prepareConversation(
  runtimeHelper: LlmModelHelper,
  assistantSeed: Message,
  model: Model,
  promptAssembly: PromptAssemblyResult,
  turnToolContext: RoleplayPreparedToolContext,
  currentTurnMedia: CurrentTurnMedia,
  sessionId: String,
  recentMessages: List<Message>,
  memoryContext: RoleplayMemoryContextPack,
  trigger: String,
  startTime: Long,
): ConversationPreparationResult {
  val systemInstruction = Contents.of(promptAssembly.prompt)
  val sectionTokenSummary =
    promptAssembly.sections.joinToString(separator = ",") { section ->
      "${section.id.name}:${section.tokenEstimate}"
    }
  srmDebugLog(
    "assembled prompt sessionId=$sessionId trigger=$trigger recentMessages=${recentMessages.size} atoms=${memoryContext.memoryAtoms.size} openThreads=${memoryContext.openThreads.size} fallbackMemories=${memoryContext.fallbackMemories.size} promptChars=${systemInstruction.toString().length} estimatedTokens=${promptAssembly.budgetReport?.estimatedInputTokens} usableTokens=${promptAssembly.budgetReport?.usableInputTokens} budgetMode=${promptAssembly.budgetReport?.mode} sectionTokens=$sectionTokenSummary",
  )

  return try {
    runtimeHelper.resetConversation(
      model = model,
      supportImage = currentTurnMedia.images.isNotEmpty(),
      supportAudio = currentTurnMedia.audioClips.isNotEmpty(),
      systemInstruction = systemInstruction,
      tools = turnToolContext.tools,
    )
    srmDebugLog(
      "conversation reset after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId tools=${turnToolContext.tools.size} images=${currentTurnMedia.images.size} audioClips=${currentTurnMedia.audioClips.size} historicalImages=${currentTurnMedia.historicalImageCount} currentImages=${currentTurnMedia.currentImageCount} historicalAudioClips=${currentTurnMedia.historicalAudioCount} currentAudioClips=${currentTurnMedia.currentAudioCount}",
    )
    ConversationPreparationResult()
  } catch (exception: Exception) {
    val errorMessage = exception.message ?: "Failed to prepare the chat session."
    ConversationPreparationResult(
      failureMessage =
        assistantSeed.copy(
          status = MessageStatus.FAILED,
          errorMessage = ContextOverflowRecovery.toUserFacingError(errorMessage),
          updatedAt = System.currentTimeMillis(),
        ),
      overflowDetected = ContextOverflowRecovery.isContextOverflow(errorMessage),
    )
  }
}

internal suspend fun runInferenceAttempt(
  runtimeHelper: LlmModelHelper,
  conversationRepository: ConversationRepository,
  assistantSeed: Message,
  model: Model,
  input: String,
  currentTurnMedia: CurrentTurnMedia,
  role: RoleCard,
  sessionId: String,
  startTime: Long,
  enableStreamingOutput: Boolean,
  isStopRequested: () -> Boolean,
): InferenceAttemptResult {
  val callbackScope = CoroutineScope(Dispatchers.IO)
  val partialContent = StringBuilder()
  val completed = AtomicBoolean(false)
  val inferenceStart = System.currentTimeMillis()
  val hasLoggedStreamingUpdate = AtomicBoolean(false)

  return try {
    suspendCancellableCoroutine { continuation ->
      fun finish(status: MessageStatus, errorMessage: String? = null) {
        if (!completed.compareAndSet(false, true)) {
          return
        }
        val updatedMessage =
          assistantSeed.copy(
            content = partialContent.toString().trim(),
            status = status,
            errorMessage = errorMessage,
            latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
            updatedAt = System.currentTimeMillis(),
          )
        if (continuation.isActive) {
          continuation.resume(
            InferenceAttemptResult(
              message = updatedMessage,
              overflowDetected = status == MessageStatus.FAILED && ContextOverflowRecovery.isContextOverflow(errorMessage),
            )
          )
        }
      }

      try {
        runtimeHelper.runInference(
          model = model,
          input = input,
          resultListener = { partialResult, done, _ ->
            if (!partialResult.startsWith("<ctrl") && partialResult.isNotEmpty()) {
              partialContent.append(partialResult)

              if (enableStreamingOutput && !isStopRequested()) {
                if (hasLoggedStreamingUpdate.compareAndSet(false, true)) {
                  srmDebugLog(
                    "streaming content updates enabled sessionId=$sessionId assistantMessageId=${assistantSeed.id}",
                  )
                }
                val streamingMessage =
                  assistantSeed.copy(
                    content = partialContent.toString(),
                    status = MessageStatus.STREAMING,
                    updatedAt = System.currentTimeMillis(),
                  )
                callbackScope.launch { conversationRepository.updateMessage(streamingMessage) }
              }
            }

            if (done) {
              srmDebugLog(
                "inference callback done after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId",
              )
              finish(
                status =
                  if (isStopRequested()) {
                    MessageStatus.INTERRUPTED
                  } else {
                    MessageStatus.COMPLETED
                  }
              )
            }
          },
          cleanUpListener = {},
          onError = { message ->
            srmDebugLog(
              "inference error after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId message=$message",
            )
            finish(
              status =
                if (isStopRequested()) {
                  MessageStatus.INTERRUPTED
                } else {
                  MessageStatus.FAILED
                },
              errorMessage = if (isStopRequested()) null else message,
            )
          },
          images = currentTurnMedia.images,
          audioClips = currentTurnMedia.audioClips,
          extraContext =
            if (
              role.enableThinking &&
                model.getBooleanConfigValue(
                  key = ConfigKeys.ENABLE_THINKING,
                  defaultValue = false,
                )
            ) {
              mapOf("enable_thinking" to "true")
            } else {
              null
            },
        )
        srmDebugLog("runInference dispatched after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId")
      } catch (exception: Exception) {
        finish(
          status = MessageStatus.FAILED,
          errorMessage = exception.message ?: "Failed to generate a reply.",
        )
      }

      continuation.invokeOnCancellation {
        if (!completed.get()) {
          runtimeHelper.stopResponse(model)
        }
      }
    }
  } catch (exception: Exception) {
    InferenceAttemptResult(
      message =
        assistantSeed.copy(
          content = partialContent.toString().trim(),
          status = MessageStatus.FAILED,
          errorMessage = exception.message ?: "Failed to generate a reply.",
          latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
          updatedAt = System.currentTimeMillis(),
        ),
      overflowDetected = ContextOverflowRecovery.isContextOverflow(exception.message),
    )
  }
}
