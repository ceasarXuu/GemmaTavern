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
