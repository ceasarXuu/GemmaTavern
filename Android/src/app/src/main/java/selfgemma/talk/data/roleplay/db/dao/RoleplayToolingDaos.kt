package selfgemma.talk.data.roleplay.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import selfgemma.talk.data.roleplay.db.entity.ExternalFactEntity
import selfgemma.talk.data.roleplay.db.entity.ToolInvocationEntity

@Dao
interface ToolInvocationDao {
  @Query(
    """
    SELECT * FROM tool_invocations
    WHERE sessionId = :sessionId
    ORDER BY startedAt ASC, stepIndex ASC
    """
  )
  fun observeBySession(sessionId: String): Flow<List<ToolInvocationEntity>>

  @Query(
    """
    SELECT * FROM tool_invocations
    WHERE sessionId = :sessionId
    ORDER BY startedAt DESC, stepIndex DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<ToolInvocationEntity>

  @Query(
    """
    SELECT * FROM tool_invocations
    WHERE sessionId = :sessionId
      AND turnId = :turnId
    ORDER BY stepIndex ASC, startedAt ASC
    """
  )
  suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocationEntity>

  @Upsert
  suspend fun upsert(entity: ToolInvocationEntity)
}

@Dao
interface ExternalFactDao {
  @Query(
    """
    SELECT * FROM external_facts
    WHERE sessionId = :sessionId
    ORDER BY capturedAt DESC, id DESC
    """
  )
  suspend fun listBySession(sessionId: String): List<ExternalFactEntity>

  @Query(
    """
    SELECT * FROM external_facts
    WHERE sessionId = :sessionId
      AND turnId = :turnId
    ORDER BY capturedAt ASC, id ASC
    """
  )
  suspend fun listByTurn(sessionId: String, turnId: String): List<ExternalFactEntity>

  @Query(
    """
    SELECT * FROM external_facts
    WHERE sessionId = :sessionId
    ORDER BY capturedAt DESC, id DESC
    LIMIT :limit
    """
  )
  suspend fun listRecentBySession(sessionId: String, limit: Int): List<ExternalFactEntity>

  @Upsert
  suspend fun upsertAll(entities: List<ExternalFactEntity>)
}
