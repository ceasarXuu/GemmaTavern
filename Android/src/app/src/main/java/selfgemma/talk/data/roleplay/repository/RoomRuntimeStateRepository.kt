package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.RuntimeStateSnapshotDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

@Singleton
class RoomRuntimeStateRepository @Inject constructor(
  private val runtimeStateSnapshotDao: RuntimeStateSnapshotDao
) : RuntimeStateRepository {
  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? {
    return runtimeStateSnapshotDao.getBySessionId(sessionId)?.toDomain()
  }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    runtimeStateSnapshotDao.upsert(snapshot.toEntity())
  }

  override suspend fun deleteBySession(sessionId: String) {
    runtimeStateSnapshotDao.deleteBySession(sessionId)
  }
}
