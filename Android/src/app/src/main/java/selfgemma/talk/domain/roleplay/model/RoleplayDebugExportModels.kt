package selfgemma.talk.domain.roleplay.model

const val ROLEPLAY_DEBUG_BUNDLE_SCHEMA_VERSION = "roleplay_debug_bundle_v2"
const val ROLEPLAY_DEBUG_POINTER_SCHEMA_VERSION = "roleplay_debug_pointer_v2"

enum class RoleplayDebugExportOrigin(val rawValue: String) {
  SESSIONS_LIST("sessions_list"),
  CHAT_SCREEN("chat_screen"),
}

data class RoleplayDebugExportAppInfo(
  val applicationId: String,
  val versionName: String,
  val versionCode: Int,
  val debugBuild: Boolean,
)

data class RoleplayDebugSessionSnapshot(
  val id: String,
  val title: String,
  val roleId: String,
  val activeModelId: String,
  val pinned: Boolean,
  val archived: Boolean,
  val createdAt: Long,
  val updatedAt: Long,
  val lastMessageAt: Long,
  val lastSummary: String?,
  val lastUserMessageExcerpt: String?,
  val lastAssistantMessageExcerpt: String?,
  val turnCount: Int,
  val summaryVersion: Int,
  val draftInput: String,
  val interopChatMetadataJson: String?,
)

data class RoleplayDebugRoleSnapshot(
  val id: String,
  val name: String,
  val avatarUri: String?,
  val coverUri: String?,
  val summary: String,
  val systemPrompt: String,
  val personaDescription: String,
  val worldSettings: String,
  val openingLine: String,
  val exampleDialogues: List<String>,
  val tags: List<String>,
  val safetyPolicy: String,
  val defaultModelId: String?,
)

data class RoleplayDebugUserProfileSnapshot(
  val activePersonaId: String,
  val defaultPersonaId: String?,
  val userName: String,
  val personaTitle: String,
  val personaDescription: String,
  val personaDescriptionPosition: String,
  val personaDescriptionDepth: Int,
  val personaDescriptionRole: Int,
  val avatarUri: String?,
)

data class RoleplayDebugSummarySnapshot(
  val version: Int,
  val coveredUntilSeq: Int,
  val summaryText: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)

data class RoleplayDebugMessageSnapshot(
  val id: String,
  val seq: Int,
  val branchId: String,
  val side: String,
  val kind: String,
  val status: String,
  val accepted: Boolean,
  val isCanonical: Boolean,
  val content: String,
  val isMarkdown: Boolean,
  val errorMessage: String?,
  val latencyMs: Double?,
  val accelerator: String?,
  val parentMessageId: String?,
  val regenerateGroupId: String?,
  val editedFromMessageId: String?,
  val supersededMessageId: String?,
  val metadataJson: String?,
  val createdAt: Long,
  val updatedAt: Long,
)

data class RoleplayDebugToolInvocationSnapshot(
  val id: String,
  val turnId: String,
  val toolName: String,
  val source: String,
  val status: String,
  val stepIndex: Int,
  val argsJson: String,
  val resultJson: String?,
  val resultSummary: String?,
  val artifactRefs: List<ToolArtifactRef>,
  val errorMessage: String?,
  val startedAt: Long,
  val finishedAt: Long?,
)

data class RoleplayDebugExternalFactSnapshot(
  val id: String,
  val turnId: String?,
  val toolInvocationId: String?,
  val sourceToolName: String,
  val title: String,
  val content: String,
  val factKey: String,
  val factType: String,
  val structuredValueJson: String?,
  val ephemeral: Boolean,
  val summaryEligible: Boolean,
  val capturedAt: Long,
  val freshnessTtlMillis: Long?,
  val confidence: Float,
)

data class RoleplayDebugSessionEventSnapshot(
  val id: String,
  val eventType: String,
  val payloadJson: String,
  val createdAt: Long,
)

data class RoleplayDebugExportNotes(
  val exportKind: String,
  val initiatedFrom: String,
)

data class RoleplayDebugBundle(
  val schemaVersion: String = ROLEPLAY_DEBUG_BUNDLE_SCHEMA_VERSION,
  val exportedAt: Long,
  val app: RoleplayDebugExportAppInfo,
  val session: RoleplayDebugSessionSnapshot,
  val role: RoleplayDebugRoleSnapshot,
  val userProfile: RoleplayDebugUserProfileSnapshot,
  val summary: RoleplayDebugSummarySnapshot?,
  val messages: List<RoleplayDebugMessageSnapshot>,
  val toolInvocations: List<RoleplayDebugToolInvocationSnapshot>,
  val externalFacts: List<RoleplayDebugExternalFactSnapshot>,
  val sessionEvents: List<RoleplayDebugSessionEventSnapshot>,
  val notes: RoleplayDebugExportNotes,
)

data class RoleplayDebugExportPointer(
  val schemaVersion: String = ROLEPLAY_DEBUG_POINTER_SCHEMA_VERSION,
  val sessionId: String,
  val roleName: String,
  val title: String,
  val exportedAt: Long,
  val relativePath: String,
  val fileName: String,
  val messageCount: Int,
  val toolInvocationCount: Int,
  val externalFactCount: Int,
)

data class RoleplayDebugStoredFile(
  val fileName: String,
  val relativePath: String,
  val adbPath: String,
  val contentUri: String,
)

data class RoleplayDebugExportResult(
  val exportedAt: Long,
  val sessionId: String,
  val sessionTitle: String,
  val roleName: String,
  val messageCount: Int,
  val toolInvocationCount: Int,
  val externalFactCount: Int,
  val origin: RoleplayDebugExportOrigin,
  val bundleFile: RoleplayDebugStoredFile,
  val pointerFile: RoleplayDebugStoredFile,
)
