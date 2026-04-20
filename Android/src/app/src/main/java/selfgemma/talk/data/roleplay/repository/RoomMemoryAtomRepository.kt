package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.dao.MemoryAtomDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository

@Singleton
class RoomMemoryAtomRepository @Inject constructor(
  private val memoryAtomDao: MemoryAtomDao
) : MemoryAtomRepository {
  override suspend fun listBySession(sessionId: String): List<MemoryAtom> {
    return memoryAtomDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun upsert(atom: MemoryAtom) {
    memoryAtomDao.upsert(atom.toEntity())
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    if (memoryIds.isEmpty()) {
      return
    }

    memoryAtomDao.markUsed(memoryIds, usedAt)
  }

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    memoryAtomDao.tombstone(memoryId, updatedAt)
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) {
    memoryAtomDao.tombstoneBySession(sessionId, updatedAt)
  }

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
      return memoryAtomDao.listTopRelevant(sessionId = sessionId, roleId = roleId, limit = limit).map { it.toDomain() }
    }

    val ftsQuery = normalizedQuery.toFtsQuery()
    val ftsResults =
      if (ftsQuery.isNotBlank()) {
        memoryAtomDao.searchRelevantFts(
          sessionId = sessionId,
          roleId = roleId,
          ftsQuery = ftsQuery,
          limit = limit,
        )
      } else {
        emptyList()
      }
    if (ftsResults.isNotEmpty()) {
      return ftsResults.map { it.toDomain() }
    }

    return memoryAtomDao.searchRelevant(
      sessionId = sessionId,
      roleId = roleId,
      query = normalizedQuery,
      limit = limit,
    ).map { it.toDomain() }
  }

  private fun String.toFtsQuery(): String {
    return lowercase()
      .replace(NON_TERM_REGEX, " ")
      .split(WHITESPACE_REGEX)
      .filter { token -> token.length >= 2 && token !in FTS_STOP_WORDS }
      .distinct()
      .take(MAX_FTS_TERMS)
      .joinToString(separator = " AND ") { token -> "$token*" }
  }

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val NON_TERM_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private const val MAX_FTS_TERMS = 8
    private val FTS_STOP_WORDS =
      setOf("the", "and", "for", "with", "that", "this", "from", "your", "about", "have", "were")
  }
}
