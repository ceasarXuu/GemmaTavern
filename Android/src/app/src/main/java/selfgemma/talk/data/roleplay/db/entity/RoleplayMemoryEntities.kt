package selfgemma.talk.data.roleplay.db.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType

@Entity(
  tableName = "runtime_state_snapshots",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["sessionId"], unique = true),
    Index(value = ["updatedAt"]),
  ],
)
data class RuntimeStateSnapshotEntity(
  @PrimaryKey val sessionId: String,
  val sceneJson: String,
  val relationshipJson: String,
  val activeEntitiesJson: String,
  val updatedAt: Long,
  val sourceMessageId: String? = null,
)

@Entity(
  tableName = "memory_atoms",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = RoleEntity::class,
      parentColumns = ["id"],
      childColumns = ["roleId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["sessionId"]),
    Index(value = ["roleId"]),
    Index(value = ["plane"]),
    Index(value = ["namespace"]),
    Index(value = ["stability"]),
    Index(value = ["tombstone"]),
    Index(value = ["normalizedObjectValue"]),
    Index(value = ["updatedAt"]),
  ],
)
data class MemoryAtomEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val roleId: String,
  val plane: MemoryPlane,
  val namespace: MemoryNamespace,
  val subject: String,
  val predicate: String,
  val objectValue: String,
  val normalizedObjectValue: String,
  val stability: MemoryStability,
  val epistemicStatus: MemoryEpistemicStatus,
  val salience: Float = 0f,
  val confidence: Float = 0f,
  val timeStartMessageId: String? = null,
  val timeEndMessageId: String? = null,
  val branchScope: MemoryBranchScope = MemoryBranchScope.ACCEPTED_ONLY,
  val sourceMessageIds: List<String> = emptyList(),
  val evidenceQuote: String = "",
  val supersedesMemoryId: String? = null,
  val tombstone: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val lastUsedAt: Long? = null,
)

@Fts4(contentEntity = MemoryAtomEntity::class)
@Entity(tableName = "memory_atoms_fts")
data class MemoryAtomFtsEntity(
  val subject: String,
  val predicate: String,
  val objectValue: String,
  val evidenceQuote: String,
)

@Entity(
  tableName = "open_threads",
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
    Index(value = ["status"]),
    Index(value = ["priority"]),
    Index(value = ["updatedAt"]),
  ],
)
data class OpenThreadEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val type: OpenThreadType,
  val content: String,
  val owner: OpenThreadOwner,
  val priority: Int = 0,
  val status: OpenThreadStatus = OpenThreadStatus.OPEN,
  val sourceMessageIds: List<String> = emptyList(),
  val resolvedByMessageId: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "compaction_cache",
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
    Index(value = ["updatedAt"]),
  ],
)
data class CompactionCacheEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val rangeStartMessageId: String,
  val rangeEndMessageId: String,
  val summaryType: CompactionSummaryType,
  val compactText: String,
  val sourceHash: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)
