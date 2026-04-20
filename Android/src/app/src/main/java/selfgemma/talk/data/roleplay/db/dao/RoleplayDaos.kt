package selfgemma.talk.data.roleplay.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import selfgemma.talk.data.roleplay.db.entity.MemoryEntity
import selfgemma.talk.data.roleplay.db.entity.MessageEntity
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEventEntity
import selfgemma.talk.data.roleplay.db.entity.SessionSummaryEntity
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

@Dao
interface RoleDao {
  @Query(
    """
    SELECT * FROM roles
    WHERE archived = 0
    ORDER BY builtIn DESC, updatedAt DESC
    """
  )
  fun observeActiveRoles(): Flow<List<RoleEntity>>

  @Query("SELECT * FROM roles WHERE id = :roleId LIMIT 1")
  suspend fun getById(roleId: String): RoleEntity?

  @Upsert
  suspend fun upsert(entity: RoleEntity)

  @Upsert
  suspend fun upsertAll(entities: List<RoleEntity>)

  @Query("DELETE FROM roles WHERE id = :roleId")
  suspend fun delete(roleId: String): Int
}

@Dao
interface SessionDao {
  @Query(
    """
    SELECT * FROM sessions
    WHERE archived = 0
    ORDER BY pinned DESC, updatedAt DESC
    """
  )
  fun observeActiveSessions(): Flow<List<SessionEntity>>

  @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
  suspend fun getById(sessionId: String): SessionEntity?

  @Upsert
  suspend fun upsert(entity: SessionEntity)

  @Upsert
  suspend fun upsertAll(entities: List<SessionEntity>)

  @Query("UPDATE sessions SET archived = 1, updatedAt = :updatedAt WHERE id = :sessionId")
  suspend fun archive(sessionId: String, updatedAt: Long): Int

  @Query("DELETE FROM sessions WHERE id = :sessionId")
  suspend fun delete(sessionId: String): Int
}

@Dao
interface MessageDao {
  @Query(
    """
    SELECT * FROM messages
    WHERE sessionId = :sessionId
    ORDER BY seq ASC
    """
  )
  fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

  @Query(
    """
    SELECT * FROM messages
    WHERE sessionId = :sessionId
    ORDER BY seq ASC
    """
  )
  suspend fun listBySession(sessionId: String): List<MessageEntity>

  @Query(
    """
    SELECT * FROM messages
    WHERE sessionId = :sessionId
      AND isCanonical = 1
    ORDER BY seq ASC
    """
  )
  suspend fun listCanonicalBySession(sessionId: String): List<MessageEntity>

  @Query(
    """
    SELECT * FROM messages
    WHERE sessionId = :sessionId
    ORDER BY seq DESC
    LIMIT :limit
    """
  )
  suspend fun listLatest(sessionId: String, limit: Int): List<MessageEntity>

  @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sessionId")
  suspend fun getMaxSeq(sessionId: String): Int

  @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
  suspend fun getById(messageId: String): MessageEntity?

  @Query(
    """
    SELECT COUNT(*) FROM messages
    WHERE sessionId = :sessionId AND side = :side AND status = :status
    """
  )
  suspend fun countBySideAndStatus(
    sessionId: String,
    side: MessageSide,
    status: MessageStatus,
  ): Int

  @Query(
    """
    SELECT COUNT(*) FROM messages
    WHERE sessionId = :sessionId
      AND side = :side
      AND status = :status
      AND accepted = 1
      AND isCanonical = 1
    """
  )
  suspend fun countAcceptedBySideAndStatus(
    sessionId: String,
    side: MessageSide,
    status: MessageStatus,
  ): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entity: MessageEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(entities: List<MessageEntity>)

  @Query("DELETE FROM messages WHERE sessionId = :sessionId")
  suspend fun deleteBySession(sessionId: String): Int

  @Update
  suspend fun update(entity: MessageEntity)

  @Query(
    """
    UPDATE messages
    SET content = :content, updatedAt = :updatedAt, status = :status
    WHERE id = :messageId
    """
  )
  suspend fun updateStreamingContent(
    messageId: String,
    content: String,
    status: MessageStatus,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE messages
    SET accepted = 0,
      isCanonical = 0,
      supersededMessageId = :acceptedMessageId,
      updatedAt = :updatedAt
    WHERE sessionId = :sessionId
      AND side = :side
      AND regenerateGroupId = :regenerateGroupId
      AND id != :acceptedMessageId
      AND status = :status
    """
  )
  suspend fun markAssistantAlternativesSuperseded(
    sessionId: String,
    side: MessageSide,
    regenerateGroupId: String,
    acceptedMessageId: String,
    status: MessageStatus,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE messages
    SET accepted = 0,
      isCanonical = 0,
      branchId = :rollbackBranchId,
      supersededMessageId = :targetMessageId,
      updatedAt = :updatedAt
    WHERE sessionId = :sessionId
      AND seq > :targetSeq
      AND isCanonical = 1
    """
  )
  suspend fun markCanonicalMessagesAfterSeqSuperseded(
    sessionId: String,
    targetSeq: Int,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int

  @Query(
    """
    UPDATE messages
    SET accepted = 0,
      isCanonical = 0,
      branchId = :rollbackBranchId,
      supersededMessageId = :targetMessageId,
      updatedAt = :updatedAt
    WHERE sessionId = :sessionId
      AND seq >= :targetSeq
      AND isCanonical = 1
    """
  )
  suspend fun markCanonicalMessagesFromSeqSuperseded(
    sessionId: String,
    targetSeq: Int,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int
}

@Dao
interface SessionSummaryDao {
  @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId LIMIT 1")
  suspend fun getBySessionId(sessionId: String): SessionSummaryEntity?

  @Upsert
  suspend fun upsert(entity: SessionSummaryEntity)

  @Upsert
  suspend fun upsertAll(entities: List<SessionSummaryEntity>)

  @Query("DELETE FROM session_summaries WHERE sessionId = :sessionId")
  suspend fun delete(sessionId: String): Int
}

@Dao
interface MemoryDao {
  @Query(
    """
    SELECT * FROM memories
    WHERE roleId = :roleId AND active = 1
    ORDER BY pinned DESC, updatedAt DESC
    """
  )
  suspend fun listByRole(roleId: String): List<MemoryEntity>

  @Query(
    """
    SELECT * FROM memories
    WHERE sessionId = :sessionId AND active = 1
    ORDER BY pinned DESC, updatedAt DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<MemoryEntity>

  @Upsert
  suspend fun upsert(entity: MemoryEntity)

  @Query("UPDATE memories SET active = 0, updatedAt = :updatedAt WHERE id = :memoryId")
  suspend fun deactivate(memoryId: String, updatedAt: Long): Int

  @Query("UPDATE memories SET lastUsedAt = :usedAt WHERE id IN (:memoryIds)")
  suspend fun markUsed(memoryIds: List<String>, usedAt: Long): Int

  @Query(
    """
    SELECT * FROM memories
    WHERE roleId = :roleId
      AND active = 1
      AND (:query = '' OR LOWER(content) LIKE '%' || LOWER(:query) || '%')
    ORDER BY pinned DESC,
      CASE WHEN sessionId = :sessionId THEN 1 ELSE 0 END DESC,
      confidence DESC,
      updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryEntity>
}

@Dao
interface SessionEventDao {
  @Query(
    """
    SELECT * FROM session_events
    WHERE sessionId = :sessionId
    ORDER BY createdAt DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<SessionEventEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entity: SessionEventEntity)
}
