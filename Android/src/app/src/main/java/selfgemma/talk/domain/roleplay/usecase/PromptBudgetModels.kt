package selfgemma.talk.domain.roleplay.usecase

internal enum class PromptSectionId {
  CORE_CHARACTER,
  LOREBOOK_BEFORE,
  CHARACTER_SUMMARY,
  CHARACTER_PERSONALITY,
  PERSONA,
  WORLD,
  SAFETY,
  RUNTIME_STATE,
  OPEN_THREADS,
  SEMANTIC_MEMORY,
  EXAMPLE_DIALOGUE,
  SESSION_SUMMARY,
  RELEVANT_MEMORY,
  DEPTH_PROMPT,
  RECENT_CONVERSATION,
  LOREBOOK_AFTER,
  POST_HISTORY_INSTRUCTIONS,
  LOREBOOK_OUTLET,
}

internal enum class PromptSectionPriority(val rank: Int) {
  REQUIRED(0),
  HIGH(1),
  MEDIUM(2),
  LOW(3),
  OPTIONAL(4),
}

internal enum class PromptSectionLevel {
  FULL,
  COMPACT,
  MINIMAL,
  DROPPED,
}

enum class PromptBudgetMode {
  FULL,
  COMPACT,
  AGGRESSIVE,
}

internal data class PromptSectionCandidate(
  val id: PromptSectionId,
  val title: String,
  val fullBody: String,
  val compactBody: String? = null,
  val minimalBody: String? = null,
  val priority: PromptSectionPriority = PromptSectionPriority.MEDIUM,
  val required: Boolean = false,
)

internal data class PromptMaterial(
  val preambleLines: List<String>,
  val sections: List<PromptSectionCandidate>,
  val responseRules: List<String>,
  val updatedChatMetadataJson: String? = null,
)

internal data class PlannedPromptSection(
  val id: PromptSectionId,
  val title: String,
  val body: String,
  val level: PromptSectionLevel,
  val tokenEstimate: Int,
)

data class PromptBudgetReport(
  val usableInputTokens: Int,
  val estimatedInputTokens: Int,
  val mode: PromptBudgetMode,
  val droppedSectionIds: List<String> = emptyList(),
  val compactedSectionIds: List<String> = emptyList(),
)

internal data class BudgetedPromptPlan(
  val prompt: String,
  val sections: List<PlannedPromptSection>,
  val mode: PromptBudgetMode,
  val estimatedInputTokens: Int,
  val report: PromptBudgetReport,
)
