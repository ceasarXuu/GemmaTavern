package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.MemoryDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.repository.MemoryRepository

@Singleton
class RoomMemoryRepository @Inject constructor(private val memoryDao: MemoryDao) : MemoryRepository {
  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> {
    return memoryDao.listByRole(roleId).map { it.toDomain() }
  }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> {
    return memoryDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun upsert(memory: MemoryItem) {
    memoryDao.upsert(memory.toEntity())
  }

  override suspend fun deactivate(memoryId: String) {
    memoryDao.deactivate(memoryId, System.currentTimeMillis())
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    if (memoryIds.isEmpty()) {
      return
    }

    memoryDao.markUsed(memoryIds, usedAt)
  }

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> {
    return memoryDao.searchRelevant(
      roleId = roleId,
      sessionId = sessionId,
      query = query.trim(),
      limit = limit,
    )
      .map { it.toDomain() }
  }
}