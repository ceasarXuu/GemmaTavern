package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.CompactionCacheDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository

@Singleton
class RoomCompactionCacheRepository @Inject constructor(
  private val compactionCacheDao: CompactionCacheDao
) : CompactionCacheRepository {
  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> {
    return compactionCacheDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun upsert(entry: CompactionCacheEntry) {
    compactionCacheDao.upsert(entry.toEntity())
  }

  override suspend fun deleteBySession(sessionId: String) {
    compactionCacheDao.deleteBySession(sessionId)
  }
}
