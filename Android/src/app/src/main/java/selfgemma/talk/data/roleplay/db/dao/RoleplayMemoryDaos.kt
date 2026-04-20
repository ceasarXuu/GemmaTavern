package selfgemma.talk.data.roleplay.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import selfgemma.talk.data.roleplay.db.entity.CompactionCacheEntity
import selfgemma.talk.data.roleplay.db.entity.MemoryAtomEntity
import selfgemma.talk.data.roleplay.db.entity.OpenThreadEntity
import selfgemma.talk.data.roleplay.db.entity.RuntimeStateSnapshotEntity
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus

@Dao
interface RuntimeStateSnapshotDao {
  @Query("SELECT * FROM runtime_state_snapshots WHERE sessionId = :sessionId LIMIT 1")
  suspend fun getBySessionId(sessionId: String): RuntimeStateSnapshotEntity?

  @Upsert
  suspend fun upsert(entity: RuntimeStateSnapshotEntity)

  @Query("DELETE FROM runtime_state_snapshots WHERE sessionId = :sessionId")
  suspend fun deleteBySession(sessionId: String): Int
}

@Dao
interface MemoryAtomDao {
  @Query(
    """
    SELECT * FROM memory_atoms
    WHERE sessionId = :sessionId
      AND tombstone = 0
    ORDER BY updatedAt DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<MemoryAtomEntity>

  @Upsert
  suspend fun upsert(entity: MemoryAtomEntity)

  @Query("UPDATE memory_atoms SET lastUsedAt = :usedAt WHERE id IN (:memoryIds)")
  suspend fun markUsed(memoryIds: List<String>, usedAt: Long): Int

  @Query("UPDATE memory_atoms SET tombstone = 1, updatedAt = :updatedAt WHERE id = :memoryId")
  suspend fun tombstone(memoryId: String, updatedAt: Long): Int

  @Query("UPDATE memory_atoms SET tombstone = 1, updatedAt = :updatedAt WHERE sessionId = :sessionId")
  suspend fun tombstoneBySession(sessionId: String, updatedAt: Long): Int

  @Query(
    """
    SELECT * FROM memory_atoms
    WHERE sessionId = :sessionId
      AND roleId = :roleId
      AND tombstone = 0
    ORDER BY
      CASE stability
        WHEN 'LOCKED' THEN 3
        WHEN 'STABLE' THEN 2
        WHEN 'CANDIDATE' THEN 1
        ELSE 0
      END DESC,
      salience DESC,
      confidence DESC,
      updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun listTopRelevant(
    sessionId: String,
    roleId: String,
    limit: Int,
  ): List<MemoryAtomEntity>

  @Query(
    """
    SELECT memory_atoms.* FROM memory_atoms
    JOIN memory_atoms_fts ON memory_atoms.rowid = memory_atoms_fts.rowid
    WHERE memory_atoms.sessionId = :sessionId
      AND memory_atoms.roleId = :roleId
      AND memory_atoms.tombstone = 0
      AND memory_atoms_fts MATCH :ftsQuery
    ORDER BY
      CASE memory_atoms.stability
        WHEN 'LOCKED' THEN 3
        WHEN 'STABLE' THEN 2
        WHEN 'CANDIDATE' THEN 1
        ELSE 0
      END DESC,
      memory_atoms.salience DESC,
      memory_atoms.confidence DESC,
      memory_atoms.updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun searchRelevantFts(
    sessionId: String,
    roleId: String,
    ftsQuery: String,
    limit: Int,
  ): List<MemoryAtomEntity>

  @Query(
    """
    SELECT * FROM memory_atoms
    WHERE sessionId = :sessionId
      AND roleId = :roleId
      AND tombstone = 0
      AND (
        :query = ''
        OR LOWER(subject) LIKE '%' || LOWER(:query) || '%'
        OR LOWER(predicate) LIKE '%' || LOWER(:query) || '%'
        OR LOWER(objectValue) LIKE '%' || LOWER(:query) || '%'
        OR LOWER(evidenceQuote) LIKE '%' || LOWER(:query) || '%'
      )
    ORDER BY
      CASE stability
        WHEN 'LOCKED' THEN 3
        WHEN 'STABLE' THEN 2
        WHEN 'CANDIDATE' THEN 1
        ELSE 0
      END DESC,
      salience DESC,
      confidence DESC,
      updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtomEntity>
}

@Dao
interface OpenThreadDao {
  @Query(
    """
    SELECT * FROM open_threads
    WHERE sessionId = :sessionId
    ORDER BY priority DESC, updatedAt DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<OpenThreadEntity>

  @Query(
    """
    SELECT * FROM open_threads
    WHERE sessionId = :sessionId AND status = :status
    ORDER BY priority DESC, updatedAt DESC
    """
  )
  suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThreadEntity>

  @Upsert
  suspend fun upsert(entity: OpenThreadEntity)

  @Query("DELETE FROM open_threads WHERE sessionId = :sessionId")
  suspend fun deleteBySession(sessionId: String): Int

  @Query(
    """
    UPDATE open_threads
    SET status = :status,
      resolvedByMessageId = :resolvedByMessageId,
      updatedAt = :updatedAt
    WHERE id = :threadId
    """
  )
  suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ): Int
}

@Dao
interface CompactionCacheDao {
  @Query(
    """
    SELECT * FROM compaction_cache
    WHERE sessionId = :sessionId
    ORDER BY updatedAt DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<CompactionCacheEntity>

  @Upsert
  suspend fun upsert(entity: CompactionCacheEntity)

  @Query("DELETE FROM compaction_cache WHERE sessionId = :sessionId")
  suspend fun deleteBySession(sessionId: String): Int
}
