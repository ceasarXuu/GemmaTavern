package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

data class PreparedRoleplayRegeneration(
  val sourceAssistantMessageId: String,
  val sourceUserMessageIds: List<String>,
  val pendingMessage: PendingRoleplayMessage,
  val rollbackResult: RoleplayContinuityRollbackResult,
)

class PrepareRoleplayRegenerationUseCase @Inject constructor(
  private val conversationRepository: ConversationRepository,
  private val rollbackRoleplayContinuityUseCase: RollbackRoleplayContinuityUseCase,
  private val sendRoleplayMessageUseCase: SendRoleplayMessageUseCase,
) {
  suspend operator fun invoke(
    sessionId: String,
    assistantMessageId: String,
    model: Model,
  ): PreparedRoleplayRegeneration? {
    val assistantMessage = conversationRepository.getMessage(assistantMessageId) ?: return null
    if (
      assistantMessage.sessionId != sessionId ||
        assistantMessage.side != MessageSide.ASSISTANT ||
        assistantMessage.status != MessageStatus.COMPLETED ||
        !assistantMessage.accepted ||
        !assistantMessage.isCanonical
    ) {
      return null
    }

    val canonicalMessages = conversationRepository.listCanonicalMessages(sessionId).sortedBy(Message::seq)
    val assistantIndex = canonicalMessages.indexOfFirst { it.id == assistantMessageId }
    if (assistantIndex <= 0) {
      return null
    }

    val sourceUserMessages = collectTriggerUserMessages(canonicalMessages = canonicalMessages, assistantIndex = assistantIndex)
    if (sourceUserMessages.isEmpty()) {
      return null
    }

    val rollbackTarget = sourceUserMessages.last()
    val rollbackResult =
      rollbackRoleplayContinuityUseCase(
        sessionId = sessionId,
        targetMessageId = rollbackTarget.id,
      ) ?: return null

    val now = System.currentTimeMillis()
    val assistantSeed =
      Message(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        seq = conversationRepository.nextMessageSeq(sessionId),
        branchId = rollbackTarget.branchId,
        side = MessageSide.ASSISTANT,
        kind = MessageKind.TEXT,
        status = MessageStatus.STREAMING,
        accepted = false,
        isCanonical = false,
        content = "",
        accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = ""),
        parentMessageId = rollbackTarget.id,
        regenerateGroupId = rollbackTarget.id,
        createdAt = now,
        updatedAt = now,
      )
    val stagedTurn =
      StagedRoleplayTurn(
        userMessages = sourceUserMessages,
        assistantMessage = assistantSeed,
        combinedUserInput =
          sourceUserMessages
            .filter { it.kind == MessageKind.TEXT }
            .joinToString(separator = "\n\n") { it.content.trim() },
      )
    val pendingMessage =
      sendRoleplayMessageUseCase.enqueuePendingMessage(
        sessionId = sessionId,
        stagedTurn = stagedTurn,
        persistedUserMessageIds = sourceUserMessages.mapTo(mutableSetOf()) { it.id },
      ) ?: return null

    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.CONTINUITY_REGENERATE_TRIGGERED,
        payloadJson =
          """{"sourceAssistantMessageId":"$assistantMessageId","sourceUserMessageIds":["${sourceUserMessages.joinToString(separator = "\",\"") { it.id }}"],"assistantSeedId":"${pendingMessage.assistantSeed.id}"}""",
        createdAt = now,
      ),
    )

    return PreparedRoleplayRegeneration(
      sourceAssistantMessageId = assistantMessageId,
      sourceUserMessageIds = sourceUserMessages.map(Message::id),
      pendingMessage = pendingMessage,
      rollbackResult = rollbackResult,
    )
  }

  private fun collectTriggerUserMessages(
    canonicalMessages: List<Message>,
    assistantIndex: Int,
  ): List<Message> {
    val collected = mutableListOf<Message>()
    var cursor = assistantIndex - 1
    while (cursor >= 0) {
      val candidate = canonicalMessages[cursor]
      if (candidate.side != MessageSide.USER) {
        break
      }
      collected += candidate
      cursor -= 1
    }
    return collected.asReversed()
  }
}
