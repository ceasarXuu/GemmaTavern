package selfgemma.talk.domain.roleplay.repository

import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot

interface RuntimeStateRepository {
  suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot?

  suspend fun upsert(snapshot: RuntimeStateSnapshot)

  suspend fun deleteBySession(sessionId: String)
}

interface MemoryAtomRepository {
  suspend fun listBySession(sessionId: String): List<MemoryAtom>

  suspend fun upsert(atom: MemoryAtom)

  suspend fun markUsed(memoryIds: List<String>, usedAt: Long)

  suspend fun tombstone(memoryId: String, updatedAt: Long)

  suspend fun tombstoneBySession(sessionId: String, updatedAt: Long)

  suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom>
}

interface OpenThreadRepository {
  suspend fun listBySession(sessionId: String): List<OpenThread>

  suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread>

  suspend fun upsert(thread: OpenThread)

  suspend fun deleteBySession(sessionId: String)

  suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  )
}

interface CompactionCacheRepository {
  suspend fun listBySession(sessionId: String): List<CompactionCacheEntry>

  suspend fun upsert(entry: CompactionCacheEntry)

  suspend fun deleteBySession(sessionId: String)
}
