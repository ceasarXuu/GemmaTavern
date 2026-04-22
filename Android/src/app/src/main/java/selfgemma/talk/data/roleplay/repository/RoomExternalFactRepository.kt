package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.ExternalFactDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.freshness
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness
import selfgemma.talk.domain.roleplay.repository.ExternalFactRepository

@Singleton
class RoomExternalFactRepository @Inject constructor(
  private val externalFactDao: ExternalFactDao,
) : ExternalFactRepository {
  override suspend fun listBySession(sessionId: String): List<RoleplayExternalFact> {
    return externalFactDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<RoleplayExternalFact> {
    return externalFactDao.listByTurn(sessionId, turnId).map { it.toDomain() }
  }

  override suspend fun listRecentBySession(
    sessionId: String,
    limit: Int,
    now: Long,
  ): List<RoleplayExternalFact> {
    if (limit <= 0) {
      return emptyList()
    }
    val candidates = externalFactDao.listRecentBySession(sessionId = sessionId, limit = limit * 4).map { it.toDomain() }
    val freshestByKey =
      linkedMapOf<String, RoleplayExternalFact>()
    candidates.forEach { fact ->
      val existing = freshestByKey[fact.factKey]
      if (existing == null || fact.capturedAt > existing.capturedAt) {
        freshestByKey[fact.factKey] = fact
      }
    }
    return freshestByKey
      .values
      .sortedWith(
        compareBy<RoleplayExternalFact> {
          when (it.freshness(now)) {
            RoleplayExternalFactFreshness.FRESH -> 0
            RoleplayExternalFactFreshness.STABLE -> 1
            RoleplayExternalFactFreshness.STALE -> 2
          }
        }.thenByDescending { it.capturedAt },
      )
      .take(limit)
  }

  override suspend fun upsertAll(
    sessionId: String,
    turnId: String,
    facts: List<RoleplayExternalFact>,
  ) {
    if (facts.isEmpty()) {
      return
    }
    externalFactDao.upsertAll(facts.map { it.toEntity(sessionId = sessionId, turnId = turnId) })
  }
}
