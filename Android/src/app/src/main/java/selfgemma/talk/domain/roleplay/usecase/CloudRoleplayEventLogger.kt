package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.domain.cloudllm.CloudProviderError
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.cloudllm.CloudRouteDecision
import selfgemma.talk.domain.cloudllm.CloudRouteReason
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

class CloudRoleplayEventLogger
@Inject
constructor(
  private val conversationRepository: ConversationRepository,
) {
  internal suspend fun routeStarted(
    sessionId: String,
    providerId: CloudProviderId,
    modelName: String,
    decision: CloudRouteDecision,
    toolCount: Int,
  ) {
    append(
      sessionId,
      SessionEventType.CLOUD_ROUTE_STARTED,
      JsonObject().apply {
        addProperty("providerId", providerId.storageId)
        addProperty("modelName", modelName)
        addProperty("reason", decision.reason.name)
        addProperty("mediaBridgeRequired", decision.mediaBridgeRequired)
        addProperty("toolCount", toolCount)
      },
    )
  }

  internal suspend fun routeCompleted(sessionId: String, providerId: CloudProviderId, textLength: Int) {
    append(
      sessionId,
      SessionEventType.CLOUD_ROUTE_COMPLETED,
      JsonObject().apply {
        addProperty("providerId", providerId.storageId)
        addProperty("textLength", textLength)
      },
    )
  }

  internal suspend fun routeFallback(
    sessionId: String,
    providerId: CloudProviderId,
    reason: CloudRouteReason,
    error: CloudProviderError? = null,
  ) {
    append(
      sessionId,
      SessionEventType.CLOUD_ROUTE_FALLBACK,
      JsonObject().apply {
        addProperty("providerId", providerId.storageId)
        addProperty("reason", reason.name)
        error?.let {
          addProperty("errorType", it.type.name)
          it.statusCode?.let { statusCode -> addProperty("statusCode", statusCode) }
        }
      },
    )
  }

  internal suspend fun providerError(sessionId: String, error: CloudProviderError) {
    append(
      sessionId,
      SessionEventType.CLOUD_PROVIDER_ERROR,
      JsonObject().apply {
        addProperty("providerId", error.providerId.storageId)
        addProperty("type", error.type.name)
        addProperty("retryable", error.retryable)
        error.statusCode?.let { addProperty("statusCode", it) }
        addProperty("message", error.message.take(240))
      },
    )
  }

  internal suspend fun mediaBridgeUsed(
    sessionId: String,
    providerId: CloudProviderId,
    mediaBridge: CloudRoleplayMediaBridgeResult,
  ) {
    append(
      sessionId,
      SessionEventType.CLOUD_MEDIA_BRIDGE_USED,
      JsonObject().apply {
        addProperty("providerId", providerId.storageId)
        addProperty("requiredCount", mediaBridge.requiredCount)
        addProperty("bridgedCount", mediaBridge.bridgedCount)
      },
    )
  }

  internal suspend fun localToolBridgeUsed(sessionId: String, providerId: CloudProviderId, toolCallCount: Int) {
    append(
      sessionId,
      SessionEventType.CLOUD_LOCAL_TOOL_BRIDGE_USED,
      JsonObject().apply {
        addProperty("providerId", providerId.storageId)
        addProperty("toolCallCount", toolCallCount)
      },
    )
  }

  private suspend fun append(sessionId: String, eventType: SessionEventType, payload: JsonObject) {
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = eventType,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      )
    )
  }
}
