package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

data class RoleplayContinuityRebuildResult(
  val sessionId: String,
  val replayedTurnCount: Int,
  val canonicalMessageCount: Int,
)

class RebuildRoleplayContinuityUseCase @Inject constructor(
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val runtimeStateRepository: RuntimeStateRepository,
  private val memoryAtomRepository: MemoryAtomRepository,
  private val openThreadRepository: OpenThreadRepository,
  private val compactionCacheRepository: CompactionCacheRepository,
  private val extractMemoriesUseCase: ExtractMemoriesUseCase,
  private val summarizeSessionUseCase: SummarizeSessionUseCase,
) {
  suspend operator fun invoke(sessionId: String): RoleplayContinuityRebuildResult? {
    val session = conversationRepository.getSession(sessionId) ?: return null
    val role = roleRepository.getRole(session.roleId) ?: return null
    val canonicalMessages =
      conversationRepository
        .listCanonicalMessages(sessionId)
        .filter { message ->
          message.accepted &&
            message.isCanonical &&
            message.side != MessageSide.SYSTEM &&
            (message.content.isNotBlank() || message.kind == MessageKind.IMAGE || message.kind == MessageKind.AUDIO)
        }

    val now = System.currentTimeMillis()
    runtimeStateRepository.deleteBySession(sessionId)
    memoryAtomRepository.tombstoneBySession(sessionId, now)
    openThreadRepository.deleteBySession(sessionId)
    compactionCacheRepository.deleteBySession(sessionId)
    conversationRepository.deleteSummary(sessionId)

    var replayedTurnCount = 0
    var lastUserMessage: Message? = null
    canonicalMessages
      .sortedBy(Message::seq)
      .forEach { message ->
        when (message.side) {
          MessageSide.USER -> lastUserMessage = message
          MessageSide.ASSISTANT -> {
            if (
              lastUserMessage != null &&
                message.status == MessageStatus.COMPLETED &&
                message.accepted &&
                message.isCanonical
            ) {
              extractMemoriesUseCase.rebuildStructuredState(
                session = session,
                role = role,
                userMessage = lastUserMessage!!,
                assistantMessage = message,
              )
              replayedTurnCount += 1
            }
          }
          MessageSide.SYSTEM -> Unit
        }
      }

    if (canonicalMessages.any { it.kind == MessageKind.TEXT && it.content.isNotBlank() }) {
      summarizeSessionUseCase(sessionId)
    }

    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.MEMORY_REBUILD_COMPLETED,
        payloadJson =
          """{"canonicalMessageCount":${canonicalMessages.size},"replayedTurnCount":$replayedTurnCount}""",
        createdAt = System.currentTimeMillis(),
      ),
    )

    return RoleplayContinuityRebuildResult(
      sessionId = sessionId,
      replayedTurnCount = replayedTurnCount,
      canonicalMessageCount = canonicalMessages.size,
    )
  }
}
