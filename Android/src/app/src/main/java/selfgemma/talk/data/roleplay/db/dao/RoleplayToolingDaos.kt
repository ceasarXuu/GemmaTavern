package selfgemma.talk.data.roleplay.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
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
