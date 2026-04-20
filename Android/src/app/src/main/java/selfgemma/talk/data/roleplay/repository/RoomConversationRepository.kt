package selfgemma.talk.data.roleplay.repository

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.data.roleplay.db.dao.MessageDao
import selfgemma.talk.data.roleplay.db.dao.SessionDao
import selfgemma.talk.data.roleplay.db.dao.SessionEventDao
import selfgemma.talk.data.roleplay.db.dao.SessionSummaryDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

private const val DEFAULT_SESSION_TITLE = "New Session"
private const val SESSION_TITLE_MAX_LENGTH = 48
private const val SESSION_EXCERPT_MAX_LENGTH = 140

@Singleton
class RoomConversationRepository
@Inject
constructor(
  private val sessionDao: SessionDao,
  private val messageDao: MessageDao,
  private val sessionSummaryDao: SessionSummaryDao,
  private val sessionEventDao: SessionEventDao,
) : ConversationRepository {

  override fun observeSessions(): Flow<List<Session>> {
    return sessionDao.observeActiveSessions().map { sessions -> sessions.map { it.toDomain() } }
  }

  override fun observeMessages(sessionId: String): Flow<List<Message>> {
    return messageDao.observeBySession(sessionId).map { messages -> messages.map { it.toDomain() } }
  }

  override suspend fun listMessages(sessionId: String): List<Message> {
    return messageDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> {
    return messageDao.listCanonicalBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun getMessage(messageId: String): Message? {
    return messageDao.getById(messageId)?.toDomain()
  }

  override suspend fun getSession(sessionId: String): Session? {
    return sessionDao.getById(sessionId)?.toDomain()
  }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    val now = System.currentTimeMillis()
    val session =
      Session(
        id = UUID.randomUUID().toString(),
        roleId = roleId,
        title = DEFAULT_SESSION_TITLE,
        activeModelId = modelId,
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
        sessionUserProfile = userProfile?.ensureDefaults(),
      )
    sessionDao.upsert(session.toEntity())
    return session
  }

  override suspend fun updateSession(session: Session) {
    sessionDao.upsert(session.toEntity())
  }

  override suspend fun archiveSession(sessionId: String) {
    sessionDao.archive(sessionId, System.currentTimeMillis())
  }

  override suspend fun deleteSession(sessionId: String) {
    sessionDao.delete(sessionId)
  }

  override suspend fun appendMessage(message: Message) {
    messageDao.insert(message.toEntity())
    syncSessionMetadata(message)
  }

  override suspend fun updateMessage(message: Message) {
    messageDao.update(message.toEntity())
    syncSessionMetadata(message)
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    val target = messageDao.getById(messageId)?.toDomain() ?: return null
    if (target.side != MessageSide.ASSISTANT) {
      return target
    }

    target.regenerateGroupId
      ?.takeIf(String::isNotBlank)
      ?.let { regenerateGroupId ->
        messageDao.markAssistantAlternativesSuperseded(
          sessionId = target.sessionId,
          side = MessageSide.ASSISTANT,
          regenerateGroupId = regenerateGroupId,
          acceptedMessageId = target.id,
          status = MessageStatus.COMPLETED,
          updatedAt = acceptedAt,
        )
      }

    val acceptedMessage =
      target.copy(
        accepted = true,
        isCanonical = true,
        supersededMessageId = null,
        updatedAt = acceptedAt,
      )
    messageDao.update(acceptedMessage.toEntity())
    syncSessionMetadata(acceptedMessage)
    return acceptedMessage
  }

  override suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messageDao.getById(targetMessageId)?.toDomain() ?: return 0
    if (target.sessionId != sessionId || !target.accepted || !target.isCanonical) {
      return 0
    }

    val updatedCount =
      messageDao.markCanonicalMessagesAfterSeqSuperseded(
        sessionId = sessionId,
        targetSeq = target.seq,
        targetMessageId = targetMessageId,
        rollbackBranchId = rollbackBranchId,
        updatedAt = updatedAt,
      )
    resyncSessionMetadata(sessionId = sessionId, canonicalOnly = true)
    return updatedCount
  }

  override suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messageDao.getById(targetMessageId)?.toDomain() ?: return 0
    if (target.sessionId != sessionId || !target.accepted || !target.isCanonical) {
      return 0
    }

    val updatedCount =
      messageDao.markCanonicalMessagesFromSeqSuperseded(
        sessionId = sessionId,
        targetSeq = target.seq,
        targetMessageId = targetMessageId,
        rollbackBranchId = rollbackBranchId,
        updatedAt = updatedAt,
      )
    resyncSessionMetadata(sessionId = sessionId, canonicalOnly = true)
    return updatedCount
  }

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    messageDao.deleteBySession(sessionId)
    if (messages.isNotEmpty()) {
      messageDao.insertAll(messages.map { it.toEntity() })
    }
    resyncSessionMetadata(sessionId)
  }

  override suspend fun nextMessageSeq(sessionId: String): Int {
    return messageDao.getMaxSeq(sessionId) + 1
  }

  override suspend fun getSummary(sessionId: String): SessionSummary? {
    return sessionSummaryDao.getBySessionId(sessionId)?.toDomain()
  }

  override suspend fun upsertSummary(summary: SessionSummary) {
    sessionSummaryDao.upsert(summary.toEntity())

    val session = sessionDao.getById(summary.sessionId) ?: return
    sessionDao.upsert(
      session.copy(
        lastSummary = summary.summaryText,
        summaryVersion = summary.version,
        updatedAt = summary.updatedAt,
      )
    )
  }

  override suspend fun deleteSummary(sessionId: String) {
    sessionSummaryDao.delete(sessionId)
    val session = sessionDao.getById(sessionId) ?: return
    sessionDao.upsert(
      session.copy(
        lastSummary = null,
        summaryVersion = 0,
        updatedAt = System.currentTimeMillis(),
      )
    )
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> {
    return sessionEventDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun appendEvent(event: SessionEvent) {
    sessionEventDao.insert(event.toEntity())
  }

  private suspend fun syncSessionMetadata(message: Message) {
    val session = sessionDao.getById(message.sessionId) ?: return
    val completedTurns =
      if (message.side == MessageSide.ASSISTANT && message.status == MessageStatus.COMPLETED) {
        messageDao.countAcceptedBySideAndStatus(
          sessionId = message.sessionId,
          side = MessageSide.ASSISTANT,
          status = MessageStatus.COMPLETED,
        )
      } else {
        session.turnCount
      }

    sessionDao.upsert(session.mergeWithMessage(message, completedTurns))
  }

  private suspend fun resyncSessionMetadata(sessionId: String, canonicalOnly: Boolean = false) {
    val session = sessionDao.getById(sessionId) ?: return
    val messages =
      if (canonicalOnly) {
        messageDao.listCanonicalBySession(sessionId).map { it.toDomain() }
      } else {
        messageDao.listBySession(sessionId).map { it.toDomain() }
      }
    val updatedSession =
      if (messages.isEmpty()) {
        session.copy(
          title = DEFAULT_SESSION_TITLE,
          updatedAt = maxOf(session.updatedAt, session.createdAt),
          lastMessageAt = session.createdAt,
          lastUserMessageExcerpt = null,
          lastAssistantMessageExcerpt = null,
          turnCount = 0,
        )
      } else {
        messages.fold(
          initial = session.copy(
            title = DEFAULT_SESSION_TITLE,
            lastUserMessageExcerpt = null,
            lastAssistantMessageExcerpt = null,
            turnCount = 0,
          )
        ) { accumulator, message ->
          accumulator.mergeWithMessage(
            message = message,
            completedTurns =
              if (
                message.side == MessageSide.ASSISTANT &&
                  message.status == MessageStatus.COMPLETED &&
                  message.accepted &&
                  message.isCanonical
              ) {
                accumulator.turnCount + 1
              } else {
                accumulator.turnCount
              },
          )
        }
      }
    sessionDao.upsert(updatedSession)
  }
}

private fun SessionEntity.mergeWithMessage(message: Message, completedTurns: Int): SessionEntity {
  return copy(
    title = deriveSessionTitle(title, message),
    updatedAt = maxOf(updatedAt, message.updatedAt),
    lastMessageAt = maxOf(lastMessageAt, message.updatedAt),
    lastUserMessageExcerpt =
      if (message.side == MessageSide.USER) {
        message.content.toExcerpt()
      } else {
        lastUserMessageExcerpt
      },
    lastAssistantMessageExcerpt =
      if (message.side == MessageSide.ASSISTANT && message.content.isNotBlank()) {
        message.content.toExcerpt()
      } else {
        lastAssistantMessageExcerpt
      },
    turnCount = completedTurns,
  )
}

private fun deriveSessionTitle(currentTitle: String, message: Message): String {
  if (currentTitle != DEFAULT_SESSION_TITLE) {
    return currentTitle
  }

  if (message.side != MessageSide.USER || message.content.isBlank()) {
    return currentTitle
  }

  return message.content.trim().replace("\n", " ").take(SESSION_TITLE_MAX_LENGTH)
}

private fun String.toExcerpt(): String {
  return trim().replace("\n", " ").take(SESSION_EXCERPT_MAX_LENGTH).ifBlank { this }
}
