package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

data class PreparedRoleplayEdit(
  val sessionId: String,
  val targetMessageId: String,
  val rollbackBranchId: String,
  val restoredDraft: String,
  val rolledBackMessageCount: Int,
  val rebuildResult: RoleplayContinuityRebuildResult?,
)

class PrepareRoleplayEditUseCase @Inject constructor(
  private val conversationRepository: ConversationRepository,
  private val rebuildRoleplayContinuityUseCase: RebuildRoleplayContinuityUseCase,
) {
  suspend operator fun invoke(
    sessionId: String,
    targetMessageId: String,
  ): PreparedRoleplayEdit? {
    val targetMessage = conversationRepository.getMessage(targetMessageId) ?: return null
    if (
      targetMessage.sessionId != sessionId ||
        targetMessage.side != MessageSide.USER ||
        !targetMessage.accepted ||
        !targetMessage.isCanonical ||
        targetMessage.content.isBlank()
    ) {
      return null
    }

    val now = System.currentTimeMillis()
    val rollbackBranchId = buildEditBranchId(targetMessageId = targetMessageId, createdAt = now)
    val rolledBackMessageCount =
      conversationRepository.rollbackFromMessageInclusive(
        sessionId = sessionId,
        targetMessageId = targetMessageId,
        rollbackBranchId = rollbackBranchId,
        updatedAt = now,
      )
    if (rolledBackMessageCount <= 0) {
      return null
    }

    val rebuildResult = rebuildRoleplayContinuityUseCase(sessionId)
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.CONTINUITY_EDIT_TRIGGERED,
        payloadJson =
          """{"targetMessageId":"$targetMessageId","targetSeq":${targetMessage.seq},"rollbackBranchId":"$rollbackBranchId","rolledBackMessageCount":$rolledBackMessageCount}""",
        createdAt = now,
      ),
    )

    return PreparedRoleplayEdit(
      sessionId = sessionId,
      targetMessageId = targetMessageId,
      rollbackBranchId = rollbackBranchId,
      restoredDraft = targetMessage.content,
      rolledBackMessageCount = rolledBackMessageCount,
      rebuildResult = rebuildResult,
    )
  }

  private fun buildEditBranchId(targetMessageId: String, createdAt: Long): String {
    return "edit-${targetMessageId.take(8)}-$createdAt"
  }
}
