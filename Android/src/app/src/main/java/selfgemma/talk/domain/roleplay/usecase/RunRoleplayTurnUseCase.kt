package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.ExternalFactRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository

data class RunRoleplayTurnResult(
  val pendingAccepted: Boolean = true,
  val assistantMessage: selfgemma.talk.domain.roleplay.model.Message? = null,
  val interrupted: Boolean = false,
  val errorMessage: String? = null,
  val toolInvocations: List<ToolInvocation> = emptyList(),
  val externalFacts: List<RoleplayExternalFact> = emptyList(),
)

class RunRoleplayTurnUseCase @Inject constructor(
  private val sendRoleplayMessageUseCase: SendRoleplayMessageUseCase,
  private val toolInvocationRepository: ToolInvocationRepository,
  private val externalFactRepository: ExternalFactRepository,
  private val conversationRepository: ConversationRepository,
) {
  suspend fun enqueueTurn(
    sessionId: String,
    stagedTurn: StagedRoleplayTurn,
    persistedUserMessageIds: Set<String> = emptySet(),
  ): PendingRoleplayMessage? {
    return sendRoleplayMessageUseCase.enqueuePendingMessage(
      sessionId = sessionId,
      stagedTurn = stagedTurn,
      persistedUserMessageIds = persistedUserMessageIds,
    )
  }

  suspend fun queueAndRun(
    sessionId: String,
    stagedTurn: StagedRoleplayTurn,
    persistedUserMessageIds: Set<String> = emptySet(),
    model: Model,
    enableStreamingOutput: Boolean,
    isStopRequested: () -> Boolean,
  ): RunRoleplayTurnResult {
    val pendingMessage =
      enqueueTurn(
        sessionId = sessionId,
        stagedTurn = stagedTurn,
        persistedUserMessageIds = persistedUserMessageIds,
      ) ?: return RunRoleplayTurnResult(
        pendingAccepted = false,
        errorMessage = "Session no longer exists.",
      )

    return runPrepared(
      pendingMessage = pendingMessage,
      model = model,
      enableStreamingOutput = enableStreamingOutput,
      isStopRequested = isStopRequested,
    )
  }

  suspend fun runPrepared(
    pendingMessage: PendingRoleplayMessage,
    model: Model,
    enableStreamingOutput: Boolean,
    isStopRequested: () -> Boolean,
  ): RunRoleplayTurnResult {
    val finalResult =
      sendRoleplayMessageUseCase.completePendingMessage(
        pendingMessage = pendingMessage,
        model = model,
        enableStreamingOutput = enableStreamingOutput,
        isStopRequested = isStopRequested,
      )

    persistToolInvocations(pendingMessage.session.id, pendingMessage.assistantSeed.id, finalResult.toolInvocations)
    persistExternalFacts(
      sessionId = pendingMessage.session.id,
      turnId = pendingMessage.assistantSeed.id,
      externalFacts = finalResult.externalFacts,
    )

    if (finalResult.toolInvocations.isNotEmpty() && finalResult.assistantMessage != null) {
      appendToolResultAppliedEvent(
        sessionId = pendingMessage.session.id,
        turnId = pendingMessage.assistantSeed.id,
        assistantMessageId = finalResult.assistantMessage.id,
        invocationCount = finalResult.toolInvocations.size,
      )
    }

    return RunRoleplayTurnResult(
      pendingAccepted = true,
      assistantMessage = finalResult.assistantMessage,
      interrupted = finalResult.interrupted,
      errorMessage = finalResult.errorMessage,
      toolInvocations = finalResult.toolInvocations,
      externalFacts = finalResult.externalFacts,
    )
  }

  private suspend fun persistToolInvocations(
    sessionId: String,
    turnId: String,
    toolInvocations: List<ToolInvocation>,
  ) {
    toolInvocations.forEach { invocation ->
      if (invocation.status !in listOf(ToolInvocationStatus.PENDING, ToolInvocationStatus.RUNNING)) {
        appendInvocationEvent(
          sessionId = sessionId,
          turnId = turnId,
          eventType = SessionEventType.TOOL_CALL_STARTED,
          toolName = invocation.toolName,
          status = ToolInvocationStatus.RUNNING,
          stepIndex = invocation.stepIndex,
          errorMessage = null,
        )
      }
      toolInvocationRepository.upsert(invocation)
      appendInvocationEvent(
        sessionId = sessionId,
        turnId = turnId,
        eventType = invocation.status.toSessionEventType(),
        toolName = invocation.toolName,
        status = invocation.status,
        stepIndex = invocation.stepIndex,
        errorMessage = invocation.errorMessage,
      )
    }
  }

  private suspend fun appendInvocationEvent(
    sessionId: String,
    turnId: String,
    eventType: SessionEventType,
    toolName: String,
    status: ToolInvocationStatus,
    stepIndex: Int,
    errorMessage: String?,
  ) {
    val payload =
      JsonObject().apply {
        addProperty("turnId", turnId)
        addProperty("toolName", toolName)
        addProperty("status", status.name)
        addProperty("stepIndex", stepIndex)
        errorMessage?.takeIf(String::isNotBlank)?.let { addProperty("errorMessage", it) }
      }
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

  private suspend fun appendToolResultAppliedEvent(
    sessionId: String,
    turnId: String,
    assistantMessageId: String,
    invocationCount: Int,
  ) {
    val payload =
      JsonObject().apply {
        addProperty("turnId", turnId)
        addProperty("assistantMessageId", assistantMessageId)
        addProperty("invocationCount", invocationCount)
      }
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.TOOL_RESULT_APPLIED,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun persistExternalFacts(
    sessionId: String,
    turnId: String,
    externalFacts: List<RoleplayExternalFact>,
  ) {
    if (externalFacts.isEmpty()) {
      return
    }
    externalFactRepository.upsertAll(
      sessionId = sessionId,
      turnId = turnId,
      facts = externalFacts,
    )
    val payload =
      JsonObject().apply {
        addProperty("turnId", turnId)
        addProperty("factCount", externalFacts.size)
        addProperty("ephemeralCount", externalFacts.count(RoleplayExternalFact::ephemeral))
        addProperty("summaryEligibleCount", externalFacts.count(RoleplayExternalFact::summaryEligible))
      }
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.EXTERNAL_EVIDENCE_UPSERTED,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      ),
    )
  }
}

private fun ToolInvocationStatus.toSessionEventType(): SessionEventType {
  return when (this) {
    ToolInvocationStatus.PENDING,
    ToolInvocationStatus.RUNNING,
    -> SessionEventType.TOOL_CALL_STARTED
    ToolInvocationStatus.SUCCEEDED -> SessionEventType.TOOL_CALL_COMPLETED
    ToolInvocationStatus.FAILED -> SessionEventType.TOOL_CALL_FAILED
    ToolInvocationStatus.CANCELLED,
    ToolInvocationStatus.SKIPPED,
    -> SessionEventType.TOOL_CHAIN_ABORTED
  }
}
