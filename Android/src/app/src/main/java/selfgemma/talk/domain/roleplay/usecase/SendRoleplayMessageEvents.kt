package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

internal suspend fun appendBudgetEventIfNeeded(
  conversationRepository: ConversationRepository,
  sessionId: String,
  report: PromptBudgetReport?,
) {
  if (
    report == null ||
      report.mode == PromptBudgetMode.FULL ||
      (report.compactedSectionIds.isEmpty() && report.droppedSectionIds.isEmpty())
  ) {
    return
  }
  conversationRepository.appendEvent(
    SessionEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      eventType = SessionEventType.CONTEXT_BUDGET_APPLIED,
      payloadJson =
        """{"mode":"${report.mode.name}","estimatedInputTokens":${report.estimatedInputTokens},"usableInputTokens":${report.usableInputTokens},"compactedSectionCount":${report.compactedSectionIds.size},"droppedSectionCount":${report.droppedSectionIds.size}}""",
      createdAt = System.currentTimeMillis(),
    )
  )
}

internal suspend fun appendToolMemoryGuardEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  toolNames: List<String>,
) {
  val payload =
    JsonObject().apply {
      addProperty("reason", "ephemeral_tool_fact_guard")
      addProperty("toolCount", toolNames.size)
      add(
        "toolNames",
        JsonArray().apply {
          toolNames.distinct().forEach(::add)
        },
      )
    }
  conversationRepository.appendEvent(
    SessionEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      eventType = SessionEventType.MEMORY_OP_REJECTED,
      payloadJson = payload.toString(),
      createdAt = System.currentTimeMillis(),
    )
  )
}

internal suspend fun appendToolPreparationFailureEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  turnId: String,
  errorMessage: String,
) {
  val payload =
    JsonObject().apply {
      addProperty("turnId", turnId)
      addProperty("toolName", "__tool_context__")
      addProperty("status", "FAILED")
      addProperty("stepIndex", -1)
      addProperty("errorMessage", errorMessage)
    }
  conversationRepository.appendEvent(
    SessionEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      eventType = SessionEventType.TOOL_CALL_FAILED,
      payloadJson = payload.toString(),
      createdAt = System.currentTimeMillis(),
    )
  )
}

internal suspend fun appendOverflowRecoveryEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  stage: String,
  retry: Int,
  report: PromptBudgetReport?,
) {
  conversationRepository.appendEvent(
    SessionEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      eventType = SessionEventType.CONTEXT_OVERFLOW_RECOVERED,
      payloadJson =
        """{"stage":"$stage","retry":$retry,"mode":"${report?.mode?.name ?: PromptBudgetMode.AGGRESSIVE.name}","estimatedInputTokens":${report?.estimatedInputTokens ?: -1},"usableInputTokens":${report?.usableInputTokens ?: -1}}""",
      createdAt = System.currentTimeMillis(),
    )
  )
}

internal suspend fun appendRoleplayEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  eventType: SessionEventType,
  payload: JsonObject,
) {
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
