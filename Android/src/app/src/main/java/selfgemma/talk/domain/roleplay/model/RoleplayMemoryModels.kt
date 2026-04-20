package selfgemma.talk.domain.roleplay.model

enum class MemoryPlane {
  IC,
  OOC,
  CANON,
  SHARED,
}

enum class MemoryNamespace {
  SEMANTIC,
  EPISODIC,
  PROMISE,
  WORLD,
}

enum class MemoryStability {
  TRANSIENT,
  CANDIDATE,
  STABLE,
  LOCKED,
}

enum class MemoryEpistemicStatus {
  OBSERVED,
  SELF_REPORT,
  THIRD_PARTY_CLAIM,
  INFERRED,
  DISPUTED,
}

enum class MemoryBranchScope {
  ACCEPTED_ONLY,
  BRANCH_LOCAL,
}

enum class OpenThreadType {
  PROMISE,
  QUESTION,
  TASK,
  MYSTERY,
  EMOTIONAL,
}

enum class OpenThreadOwner {
  USER,
  ASSISTANT,
  SHARED,
}

enum class OpenThreadStatus {
  OPEN,
  RESOLVED,
  DROPPED,
}

enum class CompactionSummaryType {
  CHAPTER,
  ARC,
  SCENE,
}

data class CharacterKernel(
  val roleId: String,
  val version: Int,
  val identityJson: String,
  val speechStyleJson: String,
  val invariantsJson: String,
  val microExemplar: String,
  val tokenBudget: Int,
  val compiledAt: Long,
)

data class RuntimeStateSnapshot(
  val sessionId: String,
  val sceneJson: String,
  val relationshipJson: String,
  val activeEntitiesJson: String,
  val updatedAt: Long,
  val sourceMessageId: String? = null,
)

data class MemoryAtom(
  val id: String,
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

data class OpenThread(
  val id: String,
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

data class CompactionCacheEntry(
  val id: String,
  val sessionId: String,
  val rangeStartMessageId: String,
  val rangeEndMessageId: String,
  val summaryType: CompactionSummaryType,
  val compactText: String,
  val sourceHash: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)
