package selfgemma.talk.domain.roleplay.usecase

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import selfgemma.talk.data.cloudllm.CloudModelConfigRepository
import selfgemma.talk.domain.cloudllm.CloudContentPart
import selfgemma.talk.domain.cloudllm.CloudGenerationEvent
import selfgemma.talk.domain.cloudllm.CloudGenerationRequest
import selfgemma.talk.domain.cloudllm.CloudGenerationResult
import selfgemma.talk.domain.cloudllm.CloudMessage
import selfgemma.talk.domain.cloudllm.CloudMessageRole
import selfgemma.talk.domain.cloudllm.CloudModelCapability
import selfgemma.talk.domain.cloudllm.CloudModelRouter
import selfgemma.talk.domain.cloudllm.CloudNetworkStatusProvider
import selfgemma.talk.domain.cloudllm.CloudProviderAdapterResolver
import selfgemma.talk.domain.cloudllm.CloudProviderError
import selfgemma.talk.domain.cloudllm.CloudProviderErrorType
import selfgemma.talk.domain.cloudllm.CloudProviderHealthTracker
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.cloudllm.CloudRequiredCapability
import selfgemma.talk.domain.cloudllm.CloudRouteReason
import selfgemma.talk.domain.cloudllm.CloudRouteRequest
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

internal data class CloudRoleplayInferenceRequest(
  val sessionId: String,
  val assistantSeed: Message,
  val promptAssembly: PromptAssemblyResult,
  val input: String,
  val userMessages: List<Message>,
  val currentTurnMedia: CurrentTurnMedia,
  val turnToolContext: RoleplayPreparedToolContext,
  val enableStreamingOutput: Boolean,
  val isStopRequested: () -> Boolean,
)

internal sealed interface CloudRoleplayInferenceOutcome {
  data object NotAttempted : CloudRoleplayInferenceOutcome

  data class Completed(val message: Message) : CloudRoleplayInferenceOutcome

  data class Fallback(
    val reason: CloudRouteReason,
    val error: CloudProviderError? = null,
  ) : CloudRoleplayInferenceOutcome
}

@Singleton
class CloudRoleplayInferenceCoordinator
@Inject
constructor(
  private val configRepository: CloudModelConfigRepository,
  private val adapterResolver: CloudProviderAdapterResolver,
  private val networkStatusProvider: CloudNetworkStatusProvider,
  private val providerHealthTracker: CloudProviderHealthTracker,
  private val conversationRepository: ConversationRepository,
  private val eventLogger: CloudRoleplayEventLogger,
) {
  private val router = CloudModelRouter()
  private val toolBridge = CloudRoleplayToolBridge()

  internal suspend fun tryGenerate(request: CloudRoleplayInferenceRequest): CloudRoleplayInferenceOutcome {
    val config = configRepository.getConfig()
    if (!config.enabled) {
      return CloudRoleplayInferenceOutcome.NotAttempted
    }
    val apiKey = configRepository.getApiKey(config.providerId)
    val adapter = adapterResolver.adapterFor(config.providerId)
    val localTools = toolBridge.collectLocalTools(request.turnToolContext.tools)
    val mediaBridge = CloudRoleplayMediaBridge.build(userMessages = request.userMessages)
    val capability = adapter?.defaultCapability(config)?.cloudCapabilityWithoutRawMedia() ?: CloudModelCapability()
    val decision =
      router.route(
        CloudRouteRequest(
          config = config,
          apiKeyAvailable = !apiKey.isNullOrBlank(),
          networkAvailable = networkStatusProvider.isNetworkAvailable(),
          providerHealthy = providerHealthTracker.isHealthy(config.providerId) && adapter != null,
          capability = capability,
          requiredCapability =
            CloudRequiredCapability(
              imageInput = request.currentTurnMedia.images.isNotEmpty(),
              audioInput = request.currentTurnMedia.audioClips.isNotEmpty(),
              toolCalling = request.turnToolContext.tools.isNotEmpty(),
              localMediaBridgeAvailable = mediaBridge.available,
            ),
        )
      )
    if (!decision.usesCloud || adapter == null || apiKey.isNullOrBlank()) {
      eventLogger.routeFallback(request.sessionId, config.providerId, decision.reason)
      return CloudRoleplayInferenceOutcome.Fallback(decision.reason)
    }
    eventLogger.routeStarted(request.sessionId, config.providerId, config.modelName, decision, localTools.size)
    if (decision.mediaBridgeRequired || mediaBridge.text.isNotBlank()) {
      eventLogger.mediaBridgeUsed(request.sessionId, config.providerId, mediaBridge)
    }

    val messages = buildCloudMessages(request, mediaBridge)
    val inferenceStart = System.currentTimeMillis()
    val partialContent = StringBuilder()
    return try {
      if (request.isStopRequested()) {
        return CloudRoleplayInferenceOutcome.Completed(interrupted(request.assistantSeed, inferenceStart, ""))
      }
      val firstResult =
        adapter.streamGenerate(
          request =
            CloudGenerationRequest(
              config = config,
              apiKey = apiKey,
              messages = messages,
              tools = localTools.map(LocalCloudTool::spec),
              stream = localTools.isEmpty() && request.enableStreamingOutput,
            ),
          onEvent = { event -> handleCloudEvent(event, request, partialContent) },
        )
      firstResult.error?.let { error -> return fallbackAfterProviderError(request, error, partialContent) }
      val finalText =
        if (firstResult.toolCalls.isEmpty()) {
          firstResult.text
        } else {
          continueAfterLocalTools(
            request = request,
            configProviderId = config.providerId,
            apiKey = apiKey,
            firstResult = firstResult,
            baseMessages = messages,
            localTools = localTools,
            streamGenerate = adapter::streamGenerate,
            partialContent = partialContent,
          )
        }
      if (request.isStopRequested()) {
        return CloudRoleplayInferenceOutcome.Completed(
          interrupted(request.assistantSeed, inferenceStart, partialContent.toString().ifBlank { finalText }),
        )
      }
      if (finalText.isBlank()) {
        return fallbackAfterProviderError(
          request,
          CloudProviderError(
            type = CloudProviderErrorType.UNKNOWN,
            providerId = config.providerId,
            retryable = true,
            message = "Cloud provider returned an empty response.",
          ),
          partialContent,
        )
      }
      providerHealthTracker.recordSuccess(config.providerId)
      val completed =
        request.assistantSeed.copy(
          content = finalText.trim(),
          status = MessageStatus.COMPLETED,
          latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
          updatedAt = System.currentTimeMillis(),
        )
      eventLogger.routeCompleted(request.sessionId, config.providerId, finalText.length)
      CloudRoleplayInferenceOutcome.Completed(completed)
    } catch (exception: Exception) {
      fallbackAfterProviderError(
        request = request,
        error = exception.toCloudProviderError(config.providerId),
        partialContent = partialContent,
      )
    }
  }

  private suspend fun continueAfterLocalTools(
    request: CloudRoleplayInferenceRequest,
    configProviderId: CloudProviderId,
    apiKey: String,
    firstResult: CloudGenerationResult,
    baseMessages: List<CloudMessage>,
    localTools: List<LocalCloudTool>,
    streamGenerate: suspend (
      CloudGenerationRequest,
      suspend (CloudGenerationEvent) -> Unit,
    ) -> CloudGenerationResult,
    partialContent: StringBuilder,
  ): String {
    val toolResultText = toolBridge.executeLocalToolCalls(firstResult.toolCalls, localTools)
    val followUpMessages =
      buildList {
        addAll(baseMessages)
        firstResult.text.takeIf(String::isNotBlank)?.let { text ->
          add(CloudMessage(CloudMessageRole.ASSISTANT, listOf(CloudContentPart.Text(text))))
        }
        add(CloudMessage(CloudMessageRole.USER, listOf(CloudContentPart.Text(toolResultText))))
      }
    partialContent.clear()
    val result =
      streamGenerate(
        CloudGenerationRequest(
          config = configRepository.getConfig(),
          apiKey = apiKey,
          messages = followUpMessages,
          tools = emptyList(),
          stream = request.enableStreamingOutput,
        )
      ) { event -> handleCloudEvent(event, request, partialContent) }
    result.error?.let { error -> throw CloudProviderException(error) }
    eventLogger.localToolBridgeUsed(request.sessionId, configProviderId, firstResult.toolCalls.size)
    return result.text
  }

  private suspend fun handleCloudEvent(
    event: CloudGenerationEvent,
    request: CloudRoleplayInferenceRequest,
    partialContent: StringBuilder,
  ) {
    if (event is CloudGenerationEvent.TextDelta && event.text.isNotEmpty()) {
      partialContent.append(event.text)
      if (request.enableStreamingOutput && !request.isStopRequested()) {
        val streamingMessage =
          request.assistantSeed.copy(
            content = partialContent.toString(),
            status = MessageStatus.STREAMING,
            updatedAt = System.currentTimeMillis(),
          )
        CoroutineScope(Dispatchers.IO).launch { conversationRepository.updateMessage(streamingMessage) }
      }
    }
  }

  private suspend fun fallbackAfterProviderError(
    request: CloudRoleplayInferenceRequest,
    error: CloudProviderError,
    partialContent: StringBuilder,
  ): CloudRoleplayInferenceOutcome.Fallback {
    providerHealthTracker.recordFailure(error)
    eventLogger.providerError(request.sessionId, error)
    eventLogger.routeFallback(request.sessionId, error.providerId, CloudRouteReason.PROVIDER_COOLDOWN, error)
    if (partialContent.isNotBlank()) {
      conversationRepository.updateMessage(
        request.assistantSeed.copy(
          content = "",
          status = MessageStatus.STREAMING,
          updatedAt = System.currentTimeMillis(),
        )
      )
    }
    return CloudRoleplayInferenceOutcome.Fallback(CloudRouteReason.PROVIDER_COOLDOWN, error)
  }

  private fun buildCloudMessages(
    request: CloudRoleplayInferenceRequest,
    mediaBridge: CloudRoleplayMediaBridgeResult,
  ): List<CloudMessage> {
    val userText =
      buildString {
        append(request.input)
        if (mediaBridge.text.isNotBlank()) {
          append("\n\n")
          append("Local media bridge:\n")
          append(mediaBridge.text)
        }
      }
    return listOf(
      CloudMessage(
        role = CloudMessageRole.SYSTEM,
        parts = listOf(CloudContentPart.Text(request.promptAssembly.prompt)),
      ),
      CloudMessage(role = CloudMessageRole.USER, parts = listOf(CloudContentPart.Text(userText))),
    )
  }

  private fun interrupted(seed: Message, inferenceStart: Long, content: String): Message {
    return seed.copy(
      content = content.trim(),
      status = MessageStatus.INTERRUPTED,
      latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun Exception.toCloudProviderError(providerId: CloudProviderId): CloudProviderError {
    if (this is CloudProviderException) {
      return error
    }
    return CloudProviderError(
      type = if (this is IOException) CloudProviderErrorType.NETWORK else CloudProviderErrorType.UNKNOWN,
      providerId = providerId,
      retryable = this is IOException,
      message = message.orEmpty().take(240),
    )
  }

  private fun CloudModelCapability.cloudCapabilityWithoutRawMedia(): CloudModelCapability {
    return copy(supportsImageInput = false, supportsAudioInput = false)
  }

  private class CloudProviderException(val error: CloudProviderError) : RuntimeException(error.message)
}
