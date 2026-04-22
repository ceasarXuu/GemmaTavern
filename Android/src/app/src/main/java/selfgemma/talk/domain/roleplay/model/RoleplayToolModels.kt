package selfgemma.talk.domain.roleplay.model

enum class ToolExecutionSource {
  NATIVE,
  JS_SKILL,
  REMOTE,
}

enum class ToolInvocationStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  SKIPPED,
}

enum class ToolArtifactKind {
  TEXT,
  IMAGE,
  WEBVIEW,
  AUDIO,
  JSON,
}

data class ToolArtifactRef(
  val kind: ToolArtifactKind,
  val label: String? = null,
  val uri: String? = null,
  val mimeType: String? = null,
  val metadataJson: String? = null,
)

data class RoleplayExternalFact(
  val id: String,
  val sourceToolName: String,
  val title: String,
  val content: String,
  val ephemeral: Boolean = true,
  val factKey: String = defaultFactKey(sourceToolName = sourceToolName, title = title),
  val factType: String = "generic",
  val structuredValueJson: String? = null,
  val summaryEligible: Boolean = false,
  val turnId: String? = null,
  val toolInvocationId: String? = null,
  val capturedAt: Long = System.currentTimeMillis(),
  val freshnessTtlMillis: Long? = null,
  val confidence: Float = 1f,
)

enum class RoleplayExternalFactFreshness {
  FRESH,
  STALE,
  STABLE,
}

fun RoleplayExternalFact.freshness(now: Long = System.currentTimeMillis()): RoleplayExternalFactFreshness {
  val ttl = freshnessTtlMillis
  if (ttl == null || ttl <= 0L) {
    return RoleplayExternalFactFreshness.STABLE
  }
  return if (capturedAt + ttl >= now) {
    RoleplayExternalFactFreshness.FRESH
  } else {
    RoleplayExternalFactFreshness.STALE
  }
}

fun RoleplayExternalFact.expiresAt(): Long? {
  val ttl = freshnessTtlMillis
  return if (ttl == null || ttl <= 0L) null else capturedAt + ttl
}

data class ToolInvocation(
  val id: String,
  val sessionId: String,
  val turnId: String,
  val toolName: String,
  val source: ToolExecutionSource,
  val status: ToolInvocationStatus = ToolInvocationStatus.PENDING,
  val stepIndex: Int = 0,
  val argsJson: String = "{}",
  val resultJson: String? = null,
  val resultSummary: String? = null,
  val artifactRefs: List<ToolArtifactRef> = emptyList(),
  val errorMessage: String? = null,
  val startedAt: Long,
  val finishedAt: Long? = null,
)

private fun defaultFactKey(sourceToolName: String, title: String): String {
  return buildString {
    append(sourceToolName.trim().ifBlank { "tool" })
    append(":")
    append(title.trim().ifBlank { "fact" })
  }
}
