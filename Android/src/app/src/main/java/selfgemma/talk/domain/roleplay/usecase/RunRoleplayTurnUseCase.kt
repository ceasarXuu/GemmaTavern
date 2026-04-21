package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository

data class RunRoleplayTurnResult(
  val pendingAccepted: Boolean = true,
  val assistantMessage: selfgemma.talk.domain.roleplay.model.Message? = null,
  val interrupted: Boolean = false,
  val errorMessage: String? = null,
  val toolInvocations: List<ToolInvocation> = emptyList(),
)

class RunRoleplayTurnUseCase @Inject constructor(
  private val sendRoleplayMessageUseCase: SendRoleplayMessageUseCase,
  private val toolOrchestrator: RoleplayToolOrchestrator,
  private val toolInvocationRepository: ToolInvocationRepository,
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
    val orchestration =
      runCatching {
        toolOrchestrator.execute(
          RoleplayToolExecutionRequest(
            pendingMessage = pendingMessage,
            model = model,
            enableStreamingOutput = enableStreamingOutput,
            isStopRequested = isStopRequested,
          )
        )
      }.getOrElse { error ->
        appendInvocationEvent(
          sessionId = pendingMessage.session.id,
          turnId = pendingMessage.assistantSeed.id,
          eventType = SessionEventType.TOOL_CALL_FAILED,
          toolName = "__turn_orchestrator__",
          status = ToolInvocationStatus.FAILED,
          stepIndex = -1,
          errorMessage = error.message ?: "Roleplay tool orchestration failed.",
        )
        RoleplayToolExecutionResult()
      }

    persistToolInvocations(pendingMessage.session.id, pendingMessage.assistantSeed.id, orchestration.toolInvocations)

    val finalResult =
      if (orchestration.handled) {
        orchestration.finalResult ?: SendRoleplayMessageResult(
          assistantMessage = null,
          interrupted = false,
          errorMessage = "Tool orchestrator reported a handled turn without a final result.",
        )
      } else {
        sendRoleplayMessageUseCase.completePendingMessage(
          pendingMessage = pendingMessage,
          model = model,
          enableStreamingOutput = enableStreamingOutput,
          isStopRequested = isStopRequested,
        )
      }

    if (orchestration.toolInvocations.isNotEmpty() && finalResult.assistantMessage != null) {
      appendToolResultAppliedEvent(
        sessionId = pendingMessage.session.id,
        turnId = pendingMessage.assistantSeed.id,
        assistantMessageId = finalResult.assistantMessage.id,
        invocationCount = orchestration.toolInvocations.size,
      )
    }

    return RunRoleplayTurnResult(
      pendingAccepted = true,
      assistantMessage = finalResult.assistantMessage,
      interrupted = finalResult.interrupted,
      errorMessage = finalResult.errorMessage,
      toolInvocations = orchestration.toolInvocations,
    )
  }

  private suspend fun persistToolInvocations(
    sessionId: String,
    turnId: String,
    toolInvocations: List<ToolInvocation>,
  ) {
    toolInvocations.forEach { invocation ->
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
