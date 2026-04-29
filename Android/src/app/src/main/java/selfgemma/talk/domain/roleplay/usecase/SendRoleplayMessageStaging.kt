package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

internal suspend fun createStagedTurn(
  conversationRepository: ConversationRepository,
  sessionId: String,
  model: Model,
  userInputs: List<String>,
): StagedRoleplayTurn {
  val now = System.currentTimeMillis()
  val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
  val firstSeq = conversationRepository.nextMessageSeq(sessionId)
  srmDebugLog("queue next seq resolved sessionId=$sessionId seq=$firstSeq")
  val sanitizedInputs = userInputs.map(String::trim).filter(String::isNotBlank)
  val userMessages =
    sanitizedInputs.mapIndexed { index, input ->
      Message(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        seq = firstSeq + index,
        branchId = DEFAULT_BRANCH_ID,
        side = MessageSide.USER,
        status = MessageStatus.COMPLETED,
        accepted = true,
        isCanonical = true,
        content = input,
        createdAt = now,
        updatedAt = now,
      )
    }
  val parentMessageId = userMessages.lastOrNull()?.id
  return StagedRoleplayTurn(
    userMessages = userMessages,
    assistantMessage =
      Message(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        seq = firstSeq + sanitizedInputs.size,
        branchId = DEFAULT_BRANCH_ID,
        side = MessageSide.ASSISTANT,
        status = MessageStatus.STREAMING,
        accepted = false,
        isCanonical = false,
        content = "",
        accelerator = accelerator,
        parentMessageId = parentMessageId,
        regenerateGroupId = parentMessageId,
        createdAt = now,
        updatedAt = now,
      ),
    combinedUserInput = sanitizedInputs.joinToString(separator = "\n\n"),
  )
}

internal suspend fun prepareTurnToolContext(
  toolOrchestrator: RoleplayToolOrchestrator,
  conversationRepository: ConversationRepository,
  pendingMessage: PendingRoleplayMessage,
  model: Model,
  enableStreamingOutput: Boolean,
  isStopRequested: () -> Boolean,
): RoleplayPreparedToolContext {
  return runCatching {
    toolOrchestrator.prepareTurnContext(
      RoleplayToolPreparationRequest(
        pendingMessage = pendingMessage,
        model = model,
        enableStreamingOutput = enableStreamingOutput,
        isStopRequested = isStopRequested,
      )
    )
  }.getOrElse { error ->
    srmWarnLog(
      "failed to prepare roleplay tool context sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id}",
      error,
    )
    appendToolPreparationFailureEvent(
      conversationRepository = conversationRepository,
      sessionId = pendingMessage.session.id,
      turnId = pendingMessage.assistantSeed.id,
      errorMessage = error.message ?: "Failed to prepare roleplay tool context.",
    )
    RoleplayPreparedToolContext(
      collector =
        RoleplayToolTraceCollector(
          sessionId = pendingMessage.session.id,
          turnId = pendingMessage.assistantSeed.id,
        )
    )
  }
}
