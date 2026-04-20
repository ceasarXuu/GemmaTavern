package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.OpenThreadDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository

@Singleton
class RoomOpenThreadRepository @Inject constructor(
  private val openThreadDao: OpenThreadDao
) : OpenThreadRepository {
  override suspend fun listBySession(sessionId: String): List<OpenThread> {
    return openThreadDao
      .listBySession(sessionId)
      .map { it.toDomain() }
      .filterNot { thread -> thread.type == OpenThreadType.QUESTION }
  }

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> {
    return openThreadDao
      .listByStatus(sessionId, status)
      .map { it.toDomain() }
      .filterNot { thread -> thread.type == OpenThreadType.QUESTION }
  }

  override suspend fun upsert(thread: OpenThread) {
    if (thread.type == OpenThreadType.QUESTION) {
      return
    }
    openThreadDao.upsert(thread.toEntity())
  }

  override suspend fun deleteBySession(sessionId: String) {
    openThreadDao.deleteBySession(sessionId)
  }

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) {
    openThreadDao.updateStatus(threadId, status, resolvedByMessageId, updatedAt)
  }
}
