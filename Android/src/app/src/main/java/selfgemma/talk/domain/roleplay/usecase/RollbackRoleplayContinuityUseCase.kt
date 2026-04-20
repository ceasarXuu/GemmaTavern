package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

data class RoleplayContinuityRollbackResult(
  val sessionId: String,
  val targetMessageId: String,
  val rollbackBranchId: String,
  val rolledBackMessageCount: Int,
  val rebuildResult: RoleplayContinuityRebuildResult?,
)

class RollbackRoleplayContinuityUseCase @Inject constructor(
  private val conversationRepository: ConversationRepository,
  private val rebuildRoleplayContinuityUseCase: RebuildRoleplayContinuityUseCase,
) {
  suspend operator fun invoke(
    sessionId: String,
    targetMessageId: String,
  ): RoleplayContinuityRollbackResult? {
    val targetMessage = conversationRepository.getMessage(targetMessageId) ?: return null
    if (
      targetMessage.sessionId != sessionId ||
        !targetMessage.accepted ||
        !targetMessage.isCanonical ||
        targetMessage.side == MessageSide.SYSTEM
    ) {
      return null
    }

    val now = System.currentTimeMillis()
    val rollbackBranchId = buildRollbackBranchId(targetMessageId = targetMessageId, createdAt = now)
    val rolledBackMessageCount =
      conversationRepository.rollbackToMessage(
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
        eventType = SessionEventType.CONTINUITY_ROLLBACK_APPLIED,
        payloadJson =
          """{"targetMessageId":"$targetMessageId","targetSeq":${targetMessage.seq},"rollbackBranchId":"$rollbackBranchId","rolledBackMessageCount":$rolledBackMessageCount}""",
        createdAt = now,
      ),
    )

    return RoleplayContinuityRollbackResult(
      sessionId = sessionId,
      targetMessageId = targetMessageId,
      rollbackBranchId = rollbackBranchId,
      rolledBackMessageCount = rolledBackMessageCount,
      rebuildResult = rebuildResult,
    )
  }

  private fun buildRollbackBranchId(targetMessageId: String, createdAt: Long): String {
    return "rollback-${targetMessageId.take(8)}-$createdAt"
  }
}
