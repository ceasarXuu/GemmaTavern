package selfgemma.talk.data.roleplay.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.SessionEventType

@Entity(
  tableName = "roles",
  indices = [
    Index(value = ["name"]),
    Index(value = ["builtIn"]),
    Index(value = ["archived"]),
    Index(value = ["updatedAt"]),
  ],
)
data class RoleEntity(
  @PrimaryKey val id: String,
  val name: String,
  val avatarUri: String? = null,
  val coverUri: String? = null,
  val summary: String = "",
  val systemPrompt: String,
  val personaDescription: String = "",
  val worldSettings: String = "",
  val openingLine: String = "",
  val exampleDialogues: List<String> = emptyList(),
  val safetyPolicy: String = "",
  val defaultModelId: String? = null,
  val defaultTemperature: Float? = null,
  val defaultTopP: Float? = null,
  val defaultTopK: Int? = null,
  val enableThinking: Boolean = false,
  val summaryTurnThreshold: Int = 6,
  val memoryEnabled: Boolean = true,
  val memoryMaxItems: Int = 32,
  val tags: List<String> = emptyList(),
  val cardCoreJson: String? = null,
  val runtimeProfileJson: String? = null,
  val mediaProfileJson: String? = null,
  val interopStateJson: String? = null,
  val builtIn: Boolean = false,
  val archived: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "sessions",
  foreignKeys = [
    ForeignKey(
      entity = RoleEntity::class,
      parentColumns = ["id"],
      childColumns = ["roleId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [
    Index(value = ["roleId"]),
    Index(value = ["updatedAt"]),
    Index(value = ["pinned"]),
    Index(value = ["archived"]),
  ],
)
data class SessionEntity(
  @PrimaryKey val id: String,
  val roleId: String,
  val title: String,
  val activeModelId: String,
  val pinned: Boolean = false,
  val archived: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val lastMessageAt: Long,
  val lastSummary: String? = null,
  val lastUserMessageExcerpt: String? = null,
  val lastAssistantMessageExcerpt: String? = null,
  val turnCount: Int = 0,
  val summaryVersion: Int = 0,
  val draftInput: String = "",
  val interopChatMetadataJson: String? = null,
  val sessionUserProfileJson: String? = null,
)

@Entity(
  tableName = "messages",
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
    Index(value = ["sessionId", "createdAt"]),
    Index(value = ["status"]),
    Index(value = ["sessionId", "isCanonical"]),
    Index(value = ["sessionId", "accepted"]),
    Index(value = ["sessionId", "branchId"]),
    Index(value = ["regenerateGroupId"]),
    Index(value = ["sessionId", "seq"], unique = true),
  ],
)
data class MessageEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val seq: Int,
  val branchId: String = "main",
  val side: MessageSide,
  val kind: MessageKind = MessageKind.TEXT,
  val status: MessageStatus = MessageStatus.PENDING,
  val accepted: Boolean = true,
  val isCanonical: Boolean = true,
  val content: String = "",
  val isMarkdown: Boolean = false,
  val errorMessage: String? = null,
  val latencyMs: Double? = null,
  val accelerator: String? = null,
  val parentMessageId: String? = null,
  val regenerateGroupId: String? = null,
  val editedFromMessageId: String? = null,
  val supersededMessageId: String? = null,
  val metadataJson: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Entity(
  tableName = "session_summaries",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
)
data class SessionSummaryEntity(
  @PrimaryKey val sessionId: String,
  val version: Int,
  val coveredUntilSeq: Int,
  val summaryText: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)

@Entity(
  tableName = "memories",
  foreignKeys = [
    ForeignKey(
      entity = RoleEntity::class,
      parentColumns = ["id"],
      childColumns = ["roleId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.SET_NULL,
    ),
  ],
  indices = [
    Index(value = ["roleId", "normalizedHash"], unique = true),
    Index(value = ["roleId"]),
    Index(value = ["sessionId"]),
    Index(value = ["category"]),
    Index(value = ["pinned"]),
    Index(value = ["active"]),
  ],
)
data class MemoryEntity(
  @PrimaryKey val id: String,
  val roleId: String,
  val sessionId: String? = null,
  val category: MemoryCategory,
  val content: String,
  val normalizedHash: String,
  val confidence: Float = 0f,
  val pinned: Boolean = false,
  val active: Boolean = true,
  val sourceMessageIds: List<String> = emptyList(),
  val createdAt: Long,
  val updatedAt: Long,
  val lastUsedAt: Long? = null,
)

@Entity(
  tableName = "session_events",
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
    Index(value = ["createdAt"]),
  ],
)
data class SessionEventEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val eventType: SessionEventType,
  val payloadJson: String = "{}",
  val createdAt: Long,
)
