package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

internal suspend fun buildStyleRepairRole(
  conversationRepository: ConversationRepository,
  sessionId: String,
  role: RoleCard,
  recentMessages: List<Message>,
): RoleCard {
  val runtimeProfile = role.runtimeProfile ?: return role
  val events = conversationRepository.listEvents(sessionId)
  val directive =
    buildStyleRepairDirective(
      role = role,
      recentMessages = recentMessages,
      events = events,
    )
  val updatedProfile =
    runtimeProfile.copy(
      styleRepairPrompt = directive?.prompt.orEmpty(),
    )
  if (directive == null) {
    return if (updatedProfile == runtimeProfile) role else role.copy(runtimeProfile = updatedProfile)
  }

  val repairAlreadyLogged =
    events.any { event ->
      event.eventType == SessionEventType.ROLE_STYLE_REPAIR_APPLIED &&
        event.createdAt >= directive.driftEventCreatedAt &&
        event.payloadJson
          .toJsonObjectOrNull()
          ?.get("sourceMessageId")
          ?.asString
          ?.trim() == directive.sourceMessageId
    }
  if (!repairAlreadyLogged) {
    appendRoleplayEvent(
      conversationRepository = conversationRepository,
      sessionId = sessionId,
      eventType = SessionEventType.ROLE_STYLE_REPAIR_APPLIED,
      payload =
        JsonObject().apply {
          addProperty("sourceMessageId", directive.sourceMessageId)
          add("signals", directive.signals.toJsonArray())
          add("tabooMatches", directive.tabooMatches.toJsonArray())
          addProperty("prompt", directive.prompt)
        },
    )
  }
  return role.copy(runtimeProfile = updatedProfile)
}

internal suspend fun appendDriftEventIfNeeded(
  conversationRepository: ConversationRepository,
  sessionId: String,
  role: RoleCard,
  recentMessages: List<Message>,
  assistantMessage: Message,
) {
  val analysis =
    analyzeDrift(
      role = role,
      recentMessages = recentMessages,
      assistantMessage = assistantMessage,
    ) ?: return
  appendRoleplayEvent(
    conversationRepository = conversationRepository,
    sessionId = sessionId,
    eventType = SessionEventType.ROLE_DRIFT_DETECTED,
    payload =
      JsonObject().apply {
        addProperty("sourceMessageId", assistantMessage.id)
        add("signals", analysis.signals.toJsonArray())
        add("tabooMatches", analysis.tabooMatches.toJsonArray())
        addProperty("currentAverageSentenceLength", analysis.currentAverageSentenceLength)
        addProperty("recentAverageSentenceLength", analysis.recentAverageSentenceLength)
      },
  )
}
