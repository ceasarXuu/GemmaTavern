package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness
import selfgemma.talk.domain.roleplay.model.freshness
import selfgemma.talk.domain.roleplay.repository.ExternalFactRepository

class FakeExternalFactRepository(
  initialFacts: List<RoleplayExternalFact> = emptyList(),
) : ExternalFactRepository {
  private val facts = initialFacts.toMutableList()

  override suspend fun listBySession(sessionId: String): List<RoleplayExternalFact> {
    return facts.toList()
  }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<RoleplayExternalFact> {
    return facts.filter { it.turnId == turnId }
  }

  override suspend fun listRecentBySession(
    sessionId: String,
    limit: Int,
    now: Long,
  ): List<RoleplayExternalFact> {
    return facts
      .sortedWith(
        compareBy<RoleplayExternalFact> {
          when (it.freshness(now)) {
            RoleplayExternalFactFreshness.FRESH -> 0
            RoleplayExternalFactFreshness.STABLE -> 1
            RoleplayExternalFactFreshness.STALE -> 2
          }
        }.thenByDescending(RoleplayExternalFact::capturedAt),
      )
      .take(limit)
  }

  override suspend fun upsertAll(
    sessionId: String,
    turnId: String,
    facts: List<RoleplayExternalFact>,
  ) {
    facts.forEach { fact ->
      this.facts.removeAll { existing -> existing.id == fact.id }
      this.facts += fact.copy(turnId = turnId)
    }
  }
}
