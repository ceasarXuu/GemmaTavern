package selfgemma.talk.performance.roleplayeval

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.BuildConfig
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.usecase.SendRoleplayMessageResult
import selfgemma.talk.domain.roleplay.usecase.SendRoleplayMessageUseCase
import selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCase
import selfgemma.talk.domain.roleplay.usecase.SummarizeSessionUseCase

data class RoleplayEvalModelBinding(
  val model: Model,
  val resolvedModel: RoleplayEvalResolvedModel,
)

@Singleton
class RoleplayEvalRunner
@Inject
constructor(
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val memoryRepository: MemoryRepository,
  private val sendRoleplayMessageUseCase: SendRoleplayMessageUseCase,
  private val summarizeSessionUseCase: SummarizeSessionUseCase,
  private val extractMemoriesUseCase: ExtractMemoriesUseCase,
) {
  suspend fun run(
    context: Context,
    manifest: RoleplayEvalManifest,
    manifestPath: String,
    runId: String,
    modelBinding: RoleplayEvalModelBinding,
    onCaseProgress: suspend (completedCases: Int, totalCases: Int, currentCaseId: String?) -> Unit =
      { _, _, _ -> },
  ): RoleplayEvalRunSummary = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    val totalCases = manifest.cases.size
    val caseResults = mutableListOf<RoleplayEvalCaseResult>()

    manifest.cases.forEachIndexed { index, evalCase ->
      val currentCaseId = evalCase.caseId.ifBlank { "case-${index + 1}" }
      onCaseProgress(index, totalCases, currentCaseId)
      caseResults +=
        runCase(
          context = context,
          manifest = manifest,
          runId = runId,
          caseOrdinal = index + 1,
          evalCase = evalCase,
          model = modelBinding.model,
        )
      onCaseProgress(index + 1, totalCases, currentCaseId)
    }

    if (manifest.cleanupAfterRun) {
      caseResults.forEach { caseResult ->
        conversationRepository.deleteSession(caseResult.sessionId)
        roleRepository.deleteRole(caseResult.roleId)
      }
    }

    RoleplayEvalRunSummary(
      manifestVersion = manifest.manifestVersion,
      suiteId = manifest.suiteId,
      runLabel = manifest.runLabel,
      runId = runId,
      appVersionName = BuildConfig.VERSION_NAME,
      appVersionCode = BuildConfig.VERSION_CODE,
      packageName = context.packageName,
      deviceModel = android.os.Build.MODEL ?: "unknown",
      deviceSdkInt = android.os.Build.VERSION.SDK_INT,
      startedAtEpochMs = startedAt,
      finishedAtEpochMs = System.currentTimeMillis(),
      manifestPath = manifestPath,
      cleanupAfterRun = manifest.cleanupAfterRun,
      resolvedModel = modelBinding.resolvedModel,
      caseResults = caseResults,
    )
  }

  private suspend fun runCase(
    context: Context,
    manifest: RoleplayEvalManifest,
    runId: String,
    caseOrdinal: Int,
    evalCase: RoleplayEvalCase,
    model: Model,
  ): RoleplayEvalCaseResult {
    val caseStartedAt = System.currentTimeMillis()
    val normalizedCaseId = evalCase.caseId.ifBlank { "case-$caseOrdinal" }
    val roleId = "roleplay-eval-$runId-$normalizedCaseId-role"
    val role = createRole(roleId = roleId, roleSpec = evalCase.role)
    roleRepository.saveRole(role)

    val session =
      conversationRepository.createSession(
        roleId = role.id,
        modelId = model.name,
        userProfile = evalCase.userProfile.toDomainOrNull(),
      )

    val seededMessages = seedTurns(sessionId = session.id, seedTurns = evalCase.seedTurns)
    hydrateSeedArtifacts(
      session = session,
      role = role,
      seededMessages = seededMessages,
      evalCase = evalCase,
    )

    val turnResults = mutableListOf<RoleplayEvalTurnResult>()
    for ((index, turn) in evalCase.turns.withIndex()) {
      val result =
        sendRoleplayMessageUseCase(
          sessionId = session.id,
          model = model,
          userInput = turn.userInput,
          enableStreamingOutput = false,
          isStopRequested = { false },
        )
      turnResults += buildTurnResult(turn = turn, index = index, result = result)
      if (result.assistantMessage?.status != MessageStatus.COMPLETED) {
        break
      }
    }

    val artifacts = collectArtifacts(sessionId = session.id, roleId = role.id)
    val assertionResults =
      buildList {
        addAll(turnResults.flatMap { it.assertionResults })
        addAll(evaluateCaseExpectations(expectations = evalCase.expectations, artifacts = artifacts))
      }

    val caseResult =
      RoleplayEvalCaseResult(
        caseId = normalizedCaseId,
        description = evalCase.description,
        roleId = role.id,
        sessionId = session.id,
        passed = assertionResults.all { it.passed },
        startedAtEpochMs = caseStartedAt,
        finishedAtEpochMs = System.currentTimeMillis(),
        turnResults = turnResults,
        assertionResults = assertionResults,
        completedAssistantMessageCount =
          artifacts.messages.count {
            it.side == MessageSide.ASSISTANT && it.status == MessageStatus.COMPLETED
          },
        totalMessageCount = artifacts.messages.size,
        roleMemoryCount = artifacts.roleMemories.size,
        sessionMemoryCount = artifacts.sessionMemories.size,
        summaryVersion = artifacts.summary?.version,
        eventTypes = artifacts.events.map { it.eventType.name },
      )

    val caseDir = RoleplayEvalStorage.caseDir(context = context, runId = runId, caseId = normalizedCaseId)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_CASE_RESULT_FILE), caseResult)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_MESSAGES_FILE), artifacts.messages)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_ROLE_MEMORIES_FILE), artifacts.roleMemories)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_SESSION_MEMORIES_FILE), artifacts.sessionMemories)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_SUMMARY_EXPORT_FILE), artifacts.summary)
    RoleplayEvalStorage.writeJson(File(caseDir, ROLEPLAY_EVAL_EVENTS_FILE), artifacts.events)

    return caseResult
  }

  private fun createRole(roleId: String, roleSpec: RoleplayEvalRole): RoleCard {
    val now = System.currentTimeMillis()
    return RoleCard(
      id = roleId,
      name = roleSpec.name.ifBlank { "Roleplay Eval Role" },
      summary = roleSpec.summary,
      systemPrompt =
        roleSpec.systemPrompt.ifBlank {
          "Stay in character, preserve continuity, and answer with concrete detail."
        },
      personaDescription = roleSpec.personaDescription,
      worldSettings = roleSpec.worldSettings,
      openingLine = roleSpec.openingLine,
      exampleDialogues = roleSpec.exampleDialogues,
      safetyPolicy = roleSpec.safetyPolicy,
      summaryTurnThreshold = max(1, roleSpec.summaryTurnThreshold),
      memoryEnabled = roleSpec.memoryEnabled,
      memoryMaxItems = roleSpec.memoryMaxItems.coerceIn(0, 32),
      tags = roleSpec.tags,
      createdAt = now,
      updatedAt = now,
    )
  }

  private suspend fun seedTurns(
    sessionId: String,
    seedTurns: List<RoleplayEvalSeedTurn>,
  ): List<Message> {
    if (seedTurns.isEmpty()) {
      return emptyList()
    }

    var nextSeq = conversationRepository.nextMessageSeq(sessionId)
    var timestamp = System.currentTimeMillis() - (seedTurns.size * 1_000L)
    val seededMessages = mutableListOf<Message>()
    seedTurns.forEach { seedTurn ->
      val side =
        when (seedTurn.side.trim().lowercase(Locale.ROOT)) {
          "user" -> MessageSide.USER
          "assistant" -> MessageSide.ASSISTANT
          "system" -> MessageSide.SYSTEM
          else -> MessageSide.USER
        }

      val message =
        Message(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          seq = nextSeq++,
          side = side,
          status = MessageStatus.COMPLETED,
          content = seedTurn.content,
          createdAt = timestamp,
          updatedAt = timestamp,
        )
      conversationRepository.appendMessage(message)
      seededMessages += message
      timestamp += 500L
    }
    return seededMessages
  }

  private suspend fun hydrateSeedArtifacts(
    session: Session,
    role: RoleCard,
    seededMessages: List<Message>,
    evalCase: RoleplayEvalCase,
  ) {
    if (seededMessages.isEmpty()) {
      return
    }

    if (evalCase.bootstrapMemoriesFromSeed) {
      var lastUserMessage: Message? = null
      seededMessages.sortedBy { it.seq }.forEach { message ->
        when (message.side) {
          MessageSide.USER -> lastUserMessage = message
          MessageSide.ASSISTANT -> {
            val pairedUserMessage = lastUserMessage
            if (pairedUserMessage != null) {
              extractMemoriesUseCase(
                session = session,
                role = role,
                userMessage = pairedUserMessage,
                assistantMessage = message,
              )
            }
          }
          MessageSide.SYSTEM -> Unit
        }
      }
    }

    if (evalCase.bootstrapSummaryFromSeed) {
      summarizeSessionUseCase(session.id)
    }
  }

  private suspend fun collectArtifacts(sessionId: String, roleId: String): RoleplayEvalCaseArtifacts {
    return RoleplayEvalCaseArtifacts(
      messages = conversationRepository.listMessages(sessionId),
      roleMemories = memoryRepository.listRoleMemories(roleId),
      sessionMemories = memoryRepository.listSessionMemories(sessionId),
      summary = conversationRepository.getSummary(sessionId),
      events = conversationRepository.listEvents(sessionId),
    )
  }

  private fun buildTurnResult(
    turn: RoleplayEvalTurn,
    index: Int,
    result: SendRoleplayMessageResult,
  ): RoleplayEvalTurnResult {
    val assistantMessage = result.assistantMessage
    val turnId = turn.turnId.ifBlank { "turn-${index + 1}" }
    val assertions =
      evaluateTurnExpectations(
        scope = turnId,
        expectations = turn.expectations,
        assistantMessage = assistantMessage,
      )
    return RoleplayEvalTurnResult(
      turnId = turnId,
      userInput = turn.userInput,
      assistantMessageId = assistantMessage?.id,
      assistantStatus = assistantMessage?.status?.name ?: "MISSING",
      assistantLatencyMs = assistantMessage?.latencyMs,
      assistantContent = assistantMessage?.content.orEmpty(),
      assertionResults = assertions,
    )
  }

  private fun evaluateTurnExpectations(
    scope: String,
    expectations: RoleplayEvalTurnExpectations?,
    assistantMessage: Message?,
  ): List<RoleplayEvalAssertionResult> {
    if (expectations == null) {
      return emptyList()
    }

    val assertions = mutableListOf<RoleplayEvalAssertionResult>()
    val actualText = assistantMessage?.content.orEmpty()
    val actualStatus = assistantMessage?.status?.name ?: "MISSING"

    assertions +=
      RoleplayEvalAssertionResult(
        scope = scope,
        label = "assistant_status",
        passed = actualStatus == expectations.expectedAssistantStatus,
        expected = expectations.expectedAssistantStatus,
        actual = actualStatus,
      )

    expectations.minAssistantChars?.let { minChars ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = scope,
          label = "assistant_min_chars",
          passed = actualText.length >= minChars,
          expected = ">= $minChars",
          actual = actualText.length.toString(),
        )
    }

    expectations.maxAssistantChars?.let { maxChars ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = scope,
          label = "assistant_max_chars",
          passed = actualText.length <= maxChars,
          expected = "<= $maxChars",
          actual = actualText.length.toString(),
        )
    }

    assertions +=
      evaluateTextExpectation(
        scope = scope,
        labelPrefix = "assistant_text",
        expectation = expectations.assistantText,
        actualText = actualText,
      )

    return assertions
  }

  private fun evaluateCaseExpectations(
    expectations: RoleplayEvalCaseExpectations?,
    artifacts: RoleplayEvalCaseArtifacts,
  ): List<RoleplayEvalAssertionResult> {
    if (expectations == null) {
      return emptyList()
    }

    val assertions = mutableListOf<RoleplayEvalAssertionResult>()
    val summaryText = artifacts.summary?.summaryText.orEmpty()
    val memoryText = artifacts.roleMemories.joinToString(separator = "\n") { it.content }
    val eventTypes = artifacts.events.map { it.eventType.name }
    val completedAssistantMessages =
      artifacts.messages.count {
        it.side == MessageSide.ASSISTANT && it.status == MessageStatus.COMPLETED
      }

    assertions +=
      evaluateTextExpectation(
        scope = "case",
        labelPrefix = "summary_text",
        expectation = expectations.summaryText,
        actualText = summaryText,
      )
    assertions +=
      evaluateTextExpectation(
        scope = "case",
        labelPrefix = "memory_text",
        expectation = expectations.memoryText,
        actualText = memoryText,
      )

    expectations.requiredEventTypes.forEach { requiredType ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = "case",
          label = "required_event_type:$requiredType",
          passed = eventTypes.contains(requiredType),
          expected = requiredType,
          actual = eventTypes.joinToString(separator = ","),
        )
    }

    expectations.minMemoryCount?.let { minMemoryCount ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = "case",
          label = "min_memory_count",
          passed = artifacts.roleMemories.size >= minMemoryCount,
          expected = ">= $minMemoryCount",
          actual = artifacts.roleMemories.size.toString(),
        )
    }

    expectations.minCompletedAssistantMessages?.let { minAssistantMessages ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = "case",
          label = "min_completed_assistant_messages",
          passed = completedAssistantMessages >= minAssistantMessages,
          expected = ">= $minAssistantMessages",
          actual = completedAssistantMessages.toString(),
        )
    }

    expectations.minSummaryVersion?.let { minSummaryVersion ->
      val actualVersion = artifacts.summary?.version ?: 0
      assertions +=
        RoleplayEvalAssertionResult(
          scope = "case",
          label = "min_summary_version",
          passed = actualVersion >= minSummaryVersion,
          expected = ">= $minSummaryVersion",
          actual = actualVersion.toString(),
        )
    }

    return assertions
  }

  private fun evaluateTextExpectation(
    scope: String,
    labelPrefix: String,
    expectation: RoleplayEvalTextExpectation?,
    actualText: String,
  ): List<RoleplayEvalAssertionResult> {
    if (expectation == null) {
      return emptyList()
    }

    val normalizedActual = actualText.lowercase(Locale.ROOT)
    val assertions = mutableListOf<RoleplayEvalAssertionResult>()

    expectation.containsAll.forEach { requiredText ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = scope,
          label = "$labelPrefix.contains_all:$requiredText",
          passed = normalizedActual.contains(requiredText.lowercase(Locale.ROOT)),
          expected = requiredText,
          actual = actualText,
        )
    }

    if (expectation.containsAny.isNotEmpty()) {
      val passed =
        expectation.containsAny.any { candidate ->
          normalizedActual.contains(candidate.lowercase(Locale.ROOT))
        }
      assertions +=
        RoleplayEvalAssertionResult(
          scope = scope,
          label = "$labelPrefix.contains_any",
          passed = passed,
          expected = expectation.containsAny.joinToString(separator = " | "),
          actual = actualText,
        )
    }

    expectation.notContainsAny.forEach { forbiddenText ->
      assertions +=
        RoleplayEvalAssertionResult(
          scope = scope,
          label = "$labelPrefix.not_contains:$forbiddenText",
          passed = !normalizedActual.contains(forbiddenText.lowercase(Locale.ROOT)),
          expected = forbiddenText,
          actual = actualText,
        )
    }

    return assertions
  }

  private fun RoleplayEvalUserProfile?.toDomainOrNull(): StUserProfile? {
    if (this == null) {
      return null
    }

    val profile = StUserProfile().withActivePersona(
      name = userName.ifBlank { "User" },
      title = personaTitle,
      description = personaDescription,
      position = StPersonaDescriptionPosition.fromRawValue(personaDescriptionPositionRaw),
      depth = personaDescriptionDepth,
      role = personaDescriptionRole,
    )
    return profile.ensureDefaults()
  }
}
