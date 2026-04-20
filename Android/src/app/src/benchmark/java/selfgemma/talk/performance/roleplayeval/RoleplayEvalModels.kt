package selfgemma.talk.performance.roleplayeval

import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary

data class RoleplayEvalManifest(
  val manifestVersion: Int = 1,
  val suiteId: String = "",
  val runLabel: String = "",
  val modelName: String = "",
  val cleanupAfterRun: Boolean = false,
  val cases: List<RoleplayEvalCase> = emptyList(),
)

data class RoleplayEvalCase(
  val caseId: String = "",
  val description: String = "",
  val role: RoleplayEvalRole = RoleplayEvalRole(),
  val userProfile: RoleplayEvalUserProfile? = null,
  val bootstrapSummaryFromSeed: Boolean = false,
  val bootstrapMemoriesFromSeed: Boolean = false,
  val seedTurns: List<RoleplayEvalSeedTurn> = emptyList(),
  val turns: List<RoleplayEvalTurn> = emptyList(),
  val expectations: RoleplayEvalCaseExpectations? = null,
)

data class RoleplayEvalRole(
  val name: String = "",
  val summary: String = "",
  val systemPrompt: String = "",
  val personaDescription: String = "",
  val worldSettings: String = "",
  val openingLine: String = "",
  val exampleDialogues: List<String> = emptyList(),
  val safetyPolicy: String = "",
  val memoryEnabled: Boolean = true,
  val memoryMaxItems: Int = 8,
  val summaryTurnThreshold: Int = 1,
  val tags: List<String> = emptyList(),
)

data class RoleplayEvalUserProfile(
  val userName: String = "",
  val personaDescription: String = "",
  val personaTitle: String = "",
  val personaDescriptionPositionRaw: Int = 0,
  val personaDescriptionDepth: Int = 2,
  val personaDescriptionRole: Int = 0,
)

data class RoleplayEvalSeedTurn(
  val side: String = "",
  val content: String = "",
)

data class RoleplayEvalTurn(
  val turnId: String = "",
  val userInput: String = "",
  val expectations: RoleplayEvalTurnExpectations? = null,
)

data class RoleplayEvalTurnExpectations(
  val assistantText: RoleplayEvalTextExpectation? = null,
  val expectedAssistantStatus: String = "COMPLETED",
  val minAssistantChars: Int? = null,
  val maxAssistantChars: Int? = null,
  val referenceAnswer: String? = null,
  val acceptableAnswers: List<String> = emptyList(),
  val scorer: String = "",
)

data class RoleplayEvalCaseExpectations(
  val summaryText: RoleplayEvalTextExpectation? = null,
  val memoryText: RoleplayEvalTextExpectation? = null,
  val requiredEventTypes: List<String> = emptyList(),
  val minMemoryCount: Int? = null,
  val minCompletedAssistantMessages: Int? = null,
  val minSummaryVersion: Int? = null,
)

data class RoleplayEvalTextExpectation(
  val containsAll: List<String> = emptyList(),
  val containsAny: List<String> = emptyList(),
  val notContainsAny: List<String> = emptyList(),
)

data class RoleplayEvalRunStatus(
  val state: String,
  val phase: String,
  val runId: String,
  val suiteId: String,
  val manifestPath: String,
  val startedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val completedCases: Int,
  val totalCases: Int,
  val currentCaseId: String? = null,
  val resolvedModelName: String? = null,
  val errorMessage: String? = null,
)

data class RoleplayEvalRunError(
  val runId: String,
  val suiteId: String,
  val phase: String,
  val message: String,
  val throwableClass: String,
  val stackTrace: String,
  val createdAtEpochMs: Long,
)

data class RoleplayEvalResolvedModel(
  val requestedModelName: String = "",
  val resolvedModelName: String = "",
  val selectionSource: String = "",
  val runtimeType: String = "",
  val modelPath: String = "",
  val accelerator: String = "",
  val llmMaxToken: Int = 0,
  val supportImage: Boolean = false,
  val supportAudio: Boolean = false,
  val supportThinking: Boolean = false,
  val wasAlreadyInitialized: Boolean = false,
)

data class RoleplayEvalRunSummary(
  val manifestVersion: Int,
  val suiteId: String,
  val runLabel: String,
  val runId: String,
  val appVersionName: String,
  val appVersionCode: Int,
  val packageName: String,
  val deviceModel: String,
  val deviceSdkInt: Int,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val manifestPath: String,
  val cleanupAfterRun: Boolean,
  val resolvedModel: RoleplayEvalResolvedModel,
  val caseResults: List<RoleplayEvalCaseResult>,
)

data class RoleplayEvalCaseResult(
  val caseId: String,
  val description: String,
  val roleId: String,
  val sessionId: String,
  val passed: Boolean,
  val startedAtEpochMs: Long,
  val finishedAtEpochMs: Long,
  val turnResults: List<RoleplayEvalTurnResult>,
  val assertionResults: List<RoleplayEvalAssertionResult>,
  val completedAssistantMessageCount: Int,
  val totalMessageCount: Int,
  val roleMemoryCount: Int,
  val sessionMemoryCount: Int,
  val summaryVersion: Int?,
  val eventTypes: List<String>,
)

data class RoleplayEvalTurnResult(
  val turnId: String,
  val userInput: String,
  val assistantMessageId: String?,
  val assistantStatus: String,
  val assistantLatencyMs: Double?,
  val assistantContent: String,
  val assertionResults: List<RoleplayEvalAssertionResult>,
)

data class RoleplayEvalAssertionResult(
  val scope: String,
  val label: String,
  val passed: Boolean,
  val expected: String,
  val actual: String,
)

data class RoleplayEvalCaseArtifacts(
  val messages: List<Message>,
  val roleMemories: List<MemoryItem>,
  val sessionMemories: List<MemoryItem>,
  val summary: SessionSummary?,
  val events: List<SessionEvent>,
)
