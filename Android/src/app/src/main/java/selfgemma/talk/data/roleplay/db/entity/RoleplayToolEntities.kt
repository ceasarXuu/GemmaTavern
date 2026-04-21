package selfgemma.talk.data.roleplay.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus

@Entity(
  tableName = "tool_invocations",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["sessionId"]),
    Index(value = ["sessionId", "turnId"]),
    Index(value = ["sessionId", "startedAt"]),
    Index(value = ["status"]),
  ],
)
data class ToolInvocationEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val turnId: String,
  val toolName: String,
  val source: ToolExecutionSource,
  val status: ToolInvocationStatus = ToolInvocationStatus.PENDING,
  val stepIndex: Int = 0,
  val argsJson: String = "{}",
  val resultJson: String? = null,
  val resultSummary: String? = null,
  val artifactRefsJson: String = "[]",
  val errorMessage: String? = null,
  val startedAt: Long,
  val finishedAt: Long? = null,
)
