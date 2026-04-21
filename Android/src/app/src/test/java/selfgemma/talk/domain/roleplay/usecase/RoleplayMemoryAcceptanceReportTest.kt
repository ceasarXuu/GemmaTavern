package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.runtime.LlmModelHelper
import selfgemma.talk.testing.FakeDataStoreRepository

private const val MEMORY_ACCEPTANCE_SCORECARD_PREFIX = "MEMORY_ACCEPTANCE_SCORECARD="
private const val MEMORY_PERFORMANCE_SCORECARD_PREFIX = "MEMORY_PERFORMANCE_SCORECARD="
private const val HOST_MICROBENCH_P95_LIMIT_MS = 250.0

class RoleplayMemoryAcceptanceReportTest {
  @Test
  fun emitMemoryAcceptanceScorecard() =
    runBlocking {
      val correction = runStableFactCorrectionProbe()
      val structured = runStructuredRecallProbe()
      val fallback = runFallbackRecallProbe()
      val compaction = runCompactionRecallProbe()
      val continuity = runSceneContinuityProbe()
      val branchIsolation = runBranchIsolationProbe()
      val driftRepair = runDriftRepairProbe()
      val tokenSamples =
        listOf(
          structured.injectedMemoryTokens,
          fallback.injectedMemoryTokens,
          compaction.injectedMemoryTokens,
        )

      val scorecard =
        MemoryAcceptanceScorecard(
          semanticMemoryPrecision = precision(correction.correctSelections + structured.correctSelections, correction.totalSelections + structured.totalSelections),
          fallbackRecallPrecision = precision(fallback.correctSelections, fallback.totalSelections),
          episodicCompactionRecallRate = rate(if (compaction.passed) 1 else 0, 1),
          openThreadRecallRate = rate(structured.recalledOpenThreads + continuity.recalledOpenThreads, structured.expectedOpenThreads + continuity.expectedOpenThreads),
          continuityPassRate = rate(if (continuity.passed) 1 else 0, 1),
          branchPollutionRate = rate(if (branchIsolation.polluted) 1 else 0, 1),
          driftRepairActivationRate = rate(if (driftRepair.activated) 1 else 0, 1),
          promptBudgetComplianceRate = rate(structured.budgetChecksPassed, structured.budgetChecksTotal),
          injectedMemoryTokenMedian = median(tokenSamples),
          injectedMemoryTokenMax = tokenSamples.maxOrNull() ?: 0,
          tokenSamples = tokenSamples,
        )

      println("$MEMORY_ACCEPTANCE_SCORECARD_PREFIX${scorecard.toJson()}")

      assertTrue(scorecard.semanticMemoryPrecision >= 0.95)
      assertTrue(scorecard.fallbackRecallPrecision >= 0.95)
      assertTrue(scorecard.episodicCompactionRecallRate >= 0.90)
      assertTrue(scorecard.openThreadRecallRate >= 0.90)
      assertTrue(scorecard.continuityPassRate >= 1.0)
      assertTrue(scorecard.branchPollutionRate == 0.0)
      assertTrue(scorecard.driftRepairActivationRate >= 1.0)
      assertTrue(scorecard.promptBudgetComplianceRate >= 1.0)
      assertTrue(scorecard.injectedMemoryTokenMedian <= 900)
    }

  @Test
  fun emitMemoryPerformanceScorecard() =
    runBlocking {
      val scorecard =
        MemoryPerformanceScorecard(
          compileMemoryContext = measureLatencyStats { runStructuredRecallProbe() },
          extractMemories = measureLatencyStats { runStableFactCorrectionProbe() },
          summarizeSession = measureLatencyStats { runCompactionRecallProbe() },
          continuityRebuild = measureLatencyStats { runSceneContinuityProbe() },
        )

      println("$MEMORY_PERFORMANCE_SCORECARD_PREFIX${scorecard.toJson()}")

      assertTrue(scorecard.compileMemoryContext.p95Ms <= HOST_MICROBENCH_P95_LIMIT_MS)
      assertTrue(scorecard.extractMemories.p95Ms <= HOST_MICROBENCH_P95_LIMIT_MS)
      assertTrue(scorecard.summarizeSession.p95Ms <= HOST_MICROBENCH_P95_LIMIT_MS)
      assertTrue(scorecard.continuityRebuild.p95Ms <= HOST_MICROBENCH_P95_LIMIT_MS)
    }
}

private data class RetrievalProbeResult(
  val correctSelections: Int,
  val totalSelections: Int,
  val expectedOpenThreads: Int = 0,
  val recalledOpenThreads: Int = 0,
  val budgetChecksPassed: Int = 0,
  val budgetChecksTotal: Int = 0,
  val injectedMemoryTokens: Int = 0,
)

private data class BooleanProbeResult(
  val passed: Boolean,
  val expectedOpenThreads: Int = 0,
  val recalledOpenThreads: Int = 0,
  val polluted: Boolean = false,
  val injectedMemoryTokens: Int = 0,
)

private data class DriftRepairProbeResult(
  val driftEvents: Int,
  val repairEvents: Int,
  val activated: Boolean,
)

private data class MemoryAcceptanceScorecard(
  val semanticMemoryPrecision: Double,
  val fallbackRecallPrecision: Double,
  val episodicCompactionRecallRate: Double,
  val openThreadRecallRate: Double,
  val continuityPassRate: Double,
  val branchPollutionRate: Double,
  val driftRepairActivationRate: Double,
  val promptBudgetComplianceRate: Double,
  val injectedMemoryTokenMedian: Int,
  val injectedMemoryTokenMax: Int,
  val tokenSamples: List<Int>,
)

private data class LatencyStats(
  val iterations: Int,
  val medianMs: Double,
  val p95Ms: Double,
  val maxMs: Double,
)

private data class MemoryPerformanceScorecard(
  val compileMemoryContext: LatencyStats,
  val extractMemories: LatencyStats,
  val summarizeSession: LatencyStats,
  val continuityRebuild: LatencyStats,
)

private suspend fun runStableFactCorrectionProbe(): RetrievalProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-correction", turnCount = 2, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      legacyMemories =
        listOf(
          acceptanceLegacyMemory(
            id = "memory-black-coffee",
            roleId = role.id,
            sessionId = session.id,
            content = "I prefer black coffee.",
            now = now,
            category = selfgemma.talk.domain.roleplay.model.MemoryCategory.PREFERENCE,
          ),
        ),
      memoryAtoms =
        listOf(
          acceptanceMemoryAtom(
            id = "atom-black-coffee",
            sessionId = session.id,
            roleId = role.id,
            subject = "user",
            predicate = "preference",
            objectValue = "I prefer black coffee.",
            evidenceQuote = "I prefer black coffee.",
            now = now,
            namespace = MemoryNamespace.SEMANTIC,
          ),
        ),
    )

  fixture.extractMemoriesUseCase(
    session = session,
    role = role,
    userMessage =
      acceptanceMessage(
        id = "user-1",
        sessionId = session.id,
        seq = 1,
        side = MessageSide.USER,
        content = "I prefer latte instead of black coffee.",
        now = now + 1,
      ),
    assistantMessage = null,
  )

  val atoms = fixture.memoryAtomRepository.listBySession(session.id)
  val activeAtoms = atoms.filterNot { it.tombstone }
  val correctActiveCount = activeAtoms.count { it.objectValue == "I prefer latte." }
  val stalePreferenceTombstoned = atoms.any { it.tombstone && it.objectValue == "I prefer black coffee." }
  val activeLegacyLatte = fixture.memoryRepository.memories.any { it.active && it.content == "I prefer latte." }
  assertTrue(stalePreferenceTombstoned)
  assertTrue(activeLegacyLatte)
  return RetrievalProbeResult(
    correctSelections = correctActiveCount,
    totalSelections = activeAtoms.size,
  )
}

private suspend fun runStructuredRecallProbe(): RetrievalProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-structured", turnCount = 18, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val recentMessages =
    listOf(
      acceptanceMessage(
        id = "assistant-4",
        sessionId = session.id,
        seq = 16,
        side = MessageSide.ASSISTANT,
        content = "The observatory beacon is still unstable, but I have not forgotten the promise.",
        now = now,
      ),
    )
  val contextProfile =
    ModelContextProfile(
      contextWindowTokens = 1024,
      reservedOutputTokens = 384,
      reservedThinkingTokens = 0,
      safetyMarginTokens = 256,
    )
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      runtimeState =
        RuntimeStateSnapshot(
          sessionId = session.id,
          sceneJson = """{"location":"Observatory roof","goal":"return before dawn"}""",
          relationshipJson = """{"currentMood":"tense","trust":"fragile but real"}""",
          activeEntitiesJson = """["Astra","Mae","observatory beacon"]""",
          updatedAt = now,
          sourceMessageId = "assistant-4",
        ),
      openThreads =
        listOf(
          acceptanceOpenThread(
            id = "thread-irrelevant",
            sessionId = session.id,
            type = OpenThreadType.TASK,
            content = "Sort the cargo manifests before the quartermaster arrives.",
            priority = 22,
            now = now,
          ),
          acceptanceOpenThread(
            id = "thread-relevant",
            sessionId = session.id,
            type = OpenThreadType.PROMISE,
            content = "Return to the observatory before dawn and explain the family crest once the beacon is stable again.",
            priority = 95,
            now = now,
          ),
          acceptanceOpenThread(
            id = "thread-mystery",
            sessionId = session.id,
            type = OpenThreadType.MYSTERY,
            content = "Work out why the maintenance hatch was left unlocked during the storm.",
            priority = 31,
            now = now,
          ),
        ),
      memoryAtoms =
        listOf(
          acceptanceMemoryAtom(
            id = "atom-episode",
            sessionId = session.id,
            roleId = role.id,
            subject = "scene",
            predicate = "event",
            objectValue = "They crossed the flooded greenhouse tunnel while alarms echoed through the broken glass canopy.",
            evidenceQuote = "We crossed the greenhouse tunnel while the alarms were going off.",
            now = now,
            namespace = MemoryNamespace.EPISODIC,
            salience = 0.42f,
          ),
          acceptanceMemoryAtom(
            id = "atom-preference",
            sessionId = session.id,
            roleId = role.id,
            subject = "user",
            predicate = "preference",
            objectValue = "prefers black coffee before dawn, especially before returning to the observatory",
            evidenceQuote = "I need black coffee before dawn if we're going back there.",
            now = now,
            namespace = MemoryNamespace.SEMANTIC,
            salience = 0.94f,
          ),
          acceptanceMemoryAtom(
            id = "atom-world",
            sessionId = session.id,
            roleId = role.id,
            subject = "observatory beacon",
            predicate = "status",
            objectValue = "still unstable after the storm and likely to fail again without manual calibration",
            evidenceQuote = "The beacon is still unstable after the storm.",
            now = now,
            namespace = MemoryNamespace.WORLD,
            salience = 0.67f,
          ),
        ),
    )
  val userQuery =
    "Do you remember our promise to return to the observatory before dawn, and that I need black coffee first?"
  val pack =
    fixture.compileMemoryContextUseCase(
      session = session,
      role = role,
      recentMessages = recentMessages,
      pendingUserInput = userQuery,
      contextProfile = contextProfile,
      budgetMode = PromptBudgetMode.AGGRESSIVE,
    )
  val assembly =
    fixture.promptAssembler.assembleForSession(
      role = role,
      summary = pack.fallbackSummary,
      memories = pack.fallbackMemories,
      recentMessages = recentMessages,
      runtimeStateSnapshot = pack.runtimeState,
      openThreads = pack.openThreads,
      memoryAtoms = pack.memoryAtoms,
      pendingUserInput = userQuery,
      userProfile = StUserProfile(),
      contextProfile = contextProfile,
      budgetMode = PromptBudgetMode.AGGRESSIVE,
    )
  val assemblyBudgetReport = assembly.budgetReport

  assertEquals(listOf("thread-relevant"), pack.openThreads.map { it.id })
  assertEquals(listOf("atom-preference"), pack.memoryAtoms.map { it.id })
  assertNotNull(assemblyBudgetReport)
  assertTrue(assemblyBudgetReport!!.estimatedInputTokens <= contextProfile.usableInputTokens)

  return RetrievalProbeResult(
    correctSelections = pack.memoryAtoms.count { it.id == "atom-preference" },
    totalSelections = pack.memoryAtoms.size,
    expectedOpenThreads = 1,
    recalledOpenThreads = pack.openThreads.count { it.id == "thread-relevant" },
    budgetChecksPassed = buildList {
      add((pack.budgetReport?.estimatedTokens ?: Int.MAX_VALUE) <= (pack.budgetReport?.targetTokens ?: Int.MIN_VALUE))
      add(assemblyBudgetReport.estimatedInputTokens <= contextProfile.usableInputTokens)
    }.count { it },
    budgetChecksTotal = 2,
    injectedMemoryTokens = estimateInjectedMemoryTokens(pack, fixture.tokenEstimator),
  )
}

private suspend fun runFallbackRecallProbe(): RetrievalProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-fallback", turnCount = 5, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val summary =
    selfgemma.talk.domain.roleplay.model.SessionSummary(
      sessionId = session.id,
      version = 3,
      coveredUntilSeq = 12,
      summaryText = "Astra and Mae agreed to reach the relay tower before sunrise.",
      tokenEstimate = 14,
      updatedAt = now,
    )
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      summary = summary,
      legacyMemories =
        listOf(
          acceptanceLegacyMemory(
            id = "legacy-relay",
            roleId = role.id,
            sessionId = session.id,
            content = "The relay tower route was mapped through the greenhouse.",
            now = now,
          ),
          acceptanceLegacyMemory(
            id = "legacy-sunrise",
            roleId = role.id,
            sessionId = session.id,
            content = "They promised to arrive before sunrise.",
            now = now,
          ),
        ),
    )
  val userQuery = "Remind me how we planned to reach the relay tower before sunrise."
  val pack =
    fixture.compileMemoryContextUseCase(
      session = session,
      role = role,
      recentMessages =
        listOf(
          acceptanceMessage(
            id = "user-11",
            sessionId = session.id,
            seq = 11,
            side = MessageSide.USER,
            content = userQuery,
            now = now,
          ),
        ),
      pendingUserInput = userQuery,
    )

  assertNotNull(pack.fallbackSummary)
  assertEquals(setOf("legacy-relay", "legacy-sunrise"), pack.fallbackMemories.map { it.id }.toSet())

  return RetrievalProbeResult(
    correctSelections = pack.fallbackMemories.count { it.id == "legacy-relay" || it.id == "legacy-sunrise" },
    totalSelections = pack.fallbackMemories.size,
    injectedMemoryTokens = estimateInjectedMemoryTokens(pack, fixture.tokenEstimator),
  )
}

private suspend fun runCompactionRecallProbe(): BooleanProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-compaction", turnCount = 16, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val messages =
    listOf(
      "We hid the forged pass near the harbor gate.",
      "I kept the beacon key through the retreat.",
      "The patrols almost saw us in the tunnel.",
      "We escaped through the greenhouse before the alarms spread.",
      "Hours later we regrouped beside the salt market.",
      "The beacon key never left my coat after that.",
      "Meanwhile the harbor gate stayed under watch.",
      "The forged pass remained buried under the loose stone.",
      "At dawn we returned to the observatory ruins.",
      "I checked the roofline and counted two patrol lights.",
      "The relay tower was still offline.",
      "We promised to restore the beacon before sunrise.",
      "The wind got worse near the stairs.",
      "I told you the forged pass was still safe.",
      "You asked whether the beacon key survived the retreat.",
      "I said both artifacts were still where we left them.",
    ).mapIndexed { index, content ->
      acceptanceMessage(
        id = "message-${index + 1}",
        sessionId = session.id,
        seq = index + 1,
        side = if (index % 2 == 0) MessageSide.USER else MessageSide.ASSISTANT,
        content = content,
        now = now + index,
      )
    }
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      messages = messages,
      userProfile = StUserProfile(personas = mapOf("captain" to "Captain Mae")),
    )

  fixture.summarizeSessionUseCase(session.id)
  val pack =
    fixture.compileMemoryContextUseCase(
      session = session,
      role = role,
      recentMessages = messages.takeLast(1),
      pendingUserInput = "What happened to the forged pass and the beacon key during our last retreat?",
    )

  val compactionEntries = fixture.compactionCacheRepository.listBySession(session.id)
  val summary = fixture.conversationRepository.getSummary(session.id)
  assertTrue(compactionEntries.isNotEmpty())
  assertNotNull(summary)
  assertTrue(pack.compactionEntries.isNotEmpty())
  assertTrue(pack.fallbackSummary?.summaryText?.contains("forged pass") == true)
  assertTrue(pack.fallbackSummary?.summaryText?.contains("beacon key") == true)

  return BooleanProbeResult(
    passed = true,
    injectedMemoryTokens = estimateInjectedMemoryTokens(pack, fixture.tokenEstimator),
  )
}

private suspend fun runSceneContinuityProbe(): BooleanProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-continuity", turnCount = 4, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      messages =
        listOf(
          acceptanceMessage(
            id = "user-1",
            sessionId = session.id,
            seq = 1,
            side = MessageSide.USER,
            content = "We need to reach the observatory before dawn. Why is the hatch open?",
            now = now,
          ),
          acceptanceMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "I will get us there. I am worried the hatch was tampered with.",
            now = now + 1,
          ),
          acceptanceMessage(
            id = "user-3",
            sessionId = session.id,
            seq = 3,
            side = MessageSide.USER,
            content = "Remember the hatch code is blue delta.",
            now = now + 2,
          ),
          acceptanceMessage(
            id = "assistant-4",
            sessionId = session.id,
            seq = 4,
            side = MessageSide.ASSISTANT,
            content = "I will keep the blue delta code ready if we need it.",
            now = now + 3,
            status = selfgemma.talk.domain.roleplay.model.MessageStatus.INTERRUPTED,
          ),
          acceptanceMessage(
            id = "assistant-branch",
            sessionId = session.id,
            seq = 5,
            side = MessageSide.ASSISTANT,
            content = "Superseded branch reply that should be ignored.",
            now = now + 4,
            accepted = false,
            isCanonical = false,
          ),
        ),
    )

  val rebuildResult = fixture.rebuildContinuityUseCase(session.id)
  val snapshot = fixture.runtimeStateRepository.getLatestSnapshot(session.id)
  val openThreads = fixture.openThreadRepository.listBySession(session.id)
  val atoms = fixture.memoryAtomRepository.listBySession(session.id)
  val summary = fixture.conversationRepository.getSummary(session.id)

  assertNotNull(rebuildResult)
  assertNotNull(snapshot)
  assertNotNull(summary)
  assertTrue(snapshot!!.sceneJson.contains("observatory"))
  assertTrue(openThreads.none { it.type == OpenThreadType.QUESTION })
  assertTrue(openThreads.any { it.type == OpenThreadType.PROMISE })
  assertTrue(atoms.any { !it.tombstone && it.objectValue.contains("reach the observatory before dawn") })
  assertTrue(atoms.none { !it.tombstone && it.objectValue.contains("blue delta") })
  assertTrue(summary!!.summaryText.contains("Recent developments:"))

  return BooleanProbeResult(
    passed = true,
    expectedOpenThreads = 1,
    recalledOpenThreads = openThreads.count { it.type == OpenThreadType.PROMISE },
  )
}

private suspend fun runBranchIsolationProbe(): BooleanProbeResult {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-rollback", turnCount = 4, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      messages =
        listOf(
          acceptanceMessage(
            id = "user-1",
            sessionId = session.id,
            seq = 1,
            side = MessageSide.USER,
            content = "We need to reach the observatory before dawn.",
            now = now,
          ),
          acceptanceMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "I will get us there.",
            now = now + 1,
          ),
          acceptanceMessage(
            id = "user-3",
            sessionId = session.id,
            seq = 3,
            side = MessageSide.USER,
            content = "Remember the hatch code is blue delta.",
            now = now + 2,
          ),
          acceptanceMessage(
            id = "assistant-4",
            sessionId = session.id,
            seq = 4,
            side = MessageSide.ASSISTANT,
            content = "I will keep the blue delta code safe.",
            now = now + 3,
          ),
        ),
    )

  fixture.rebuildContinuityUseCase(session.id)
  val rollbackResult = fixture.rollbackContinuityUseCase(session.id, "assistant-2")
  val messages = fixture.conversationRepository.listMessages(session.id)
  val summary = fixture.conversationRepository.getSummary(session.id)
  val atoms = fixture.memoryAtomRepository.listBySession(session.id)
  val polluted =
    summary?.summaryText?.contains("blue delta") == true ||
      atoms.any { !it.tombstone && it.objectValue.contains("blue delta") }

  assertNotNull(rollbackResult)
  assertTrue(messages.filter { it.seq > 2 }.all { !it.isCanonical && !it.accepted })
  assertTrue(summary != null && !summary.summaryText.contains("blue delta"))
  assertTrue(atoms.none { !it.tombstone && it.objectValue.contains("blue delta") })

  return BooleanProbeResult(
    passed = !polluted,
    polluted = polluted,
  )
}

private suspend fun runDriftRepairProbe(): DriftRepairProbeResult {
  val runtimeHelper =
    AcceptanceSequentialRuntimeHelper(
      responses =
        listOf(
          "As an AI assistant, I can help you coordinate the breach.",
          "We move before dawn. Stay low and keep the beacon dark.",
        ),
    )
  val fixture = createRoleplaySendAcceptanceFixture(runtimeHelper)

  val firstResult =
    fixture.useCase(
      sessionId = fixture.session.id,
      model = fixture.model,
      userInput = "How do we breach the observatory?",
      enableStreamingOutput = false,
      isStopRequested = { false },
    )
  val secondResult =
    fixture.useCase(
      sessionId = fixture.session.id,
      model = fixture.model,
      userInput = "Then lead the entry team.",
      enableStreamingOutput = false,
      isStopRequested = { false },
    )

  val driftEvents =
    fixture.conversationRepository.events.count { it.eventType == SessionEventType.ROLE_DRIFT_DETECTED }
  val repairEvents =
    fixture.conversationRepository.events.count { it.eventType == SessionEventType.ROLE_STYLE_REPAIR_APPLIED }

  assertNotNull(firstResult.assistantMessage)
  assertNotNull(secondResult.assistantMessage)
  val activated = repairEvents >= 1 && runtimeHelper.resetPrompts.getOrNull(1).orEmpty().contains("Style repair:")
  assertTrue(driftEvents >= 1)
  assertTrue(activated)

  return DriftRepairProbeResult(
    driftEvents = driftEvents,
    repairEvents = repairEvents,
    activated = activated,
  )
}

private data class RoleplaySendAcceptanceFixture(
  val useCase: SendRoleplayMessageUseCase,
  val conversationRepository: AcceptanceConversationRepository,
  val session: selfgemma.talk.domain.roleplay.model.Session,
  val model: Model,
)

private fun createRoleplaySendAcceptanceFixture(
  runtimeHelper: AcceptanceSequentialRuntimeHelper,
): RoleplaySendAcceptanceFixture {
  val now = System.currentTimeMillis()
  val session = acceptanceSession(sessionId = "session-send", turnCount = 8, now = now)
  val role = acceptanceRole(roleId = session.roleId, now = now)
  val fixture =
    createRoleplayMemoryAcceptanceFixture(
      session = session,
      role = role,
      messages =
        listOf(
          acceptanceMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "We still owe the observatory promise before dawn.",
            now = now,
          ),
        ),
      runtimeState =
        RuntimeStateSnapshot(
          sessionId = session.id,
          sceneJson = """{"location":"Observatory roof","goal":"return before dawn"}""",
          relationshipJson = """{"currentMood":"tense but trusting"}""",
          activeEntitiesJson = """["Astra","Mae","observatory beacon"]""",
          updatedAt = now,
          sourceMessageId = "assistant-2",
        ),
      openThreads =
        listOf(
          acceptanceOpenThread(
            id = "thread-promise",
            sessionId = session.id,
            type = OpenThreadType.PROMISE,
            content = "Return to the observatory before dawn.",
            priority = 10,
            now = now,
          ),
        ),
      memoryAtoms =
        listOf(
          acceptanceMemoryAtom(
            id = "atom-promise",
            sessionId = session.id,
            roleId = role.id,
            subject = "Captain Astra",
            predicate = "promised",
            objectValue = "to return to the observatory before dawn",
            evidenceQuote = "We still owe the observatory promise before dawn.",
            now = now,
          ),
        ),
    )
  val useCase =
    SendRoleplayMessageUseCase(
      dataStoreRepository = FakeDataStoreRepository(stUserProfile = StUserProfile()),
      conversationRepository = fixture.conversationRepository,
      roleRepository = fixture.roleRepository,
      toolOrchestrator = NoOpRoleplayToolOrchestrator(),
      compileRuntimeRoleProfileUseCase = CompileRuntimeRoleProfileUseCase(TokenEstimator()),
      promptAssembler = PromptAssembler(TokenEstimator()),
      compileRoleplayMemoryContextUseCase = fixture.compileMemoryContextUseCase,
      summarizeSessionUseCase = fixture.summarizeSessionUseCase,
      extractMemoriesUseCase = fixture.extractMemoriesUseCase,
    )
  useCase.runtimeHelperResolver = { runtimeHelper }
  val model =
    Model(
      name = "acceptance-model",
      downloadFileName = "acceptance-model.bin",
      llmMaxToken = 4096,
    ).apply {
      instance = Any()
    }
  return RoleplaySendAcceptanceFixture(
    useCase = useCase,
    conversationRepository = fixture.conversationRepository,
    session = session,
    model = model,
  )
}

private class AcceptanceSequentialRuntimeHelper(
  private val responses: List<String>,
) : LlmModelHelper {
  val resetPrompts = mutableListOf<String>()
  private var responseIndex: Int = 0

  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    onDone("")
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    resetPrompts += systemInstruction?.toString().orEmpty()
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: (partialResult: String, done: Boolean, partialThinkingResult: String?) -> Unit,
    cleanUpListener: () -> Unit,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val response = responses.getOrElse(responseIndex) { responses.lastOrNull().orEmpty() }
    responseIndex += 1
    resultListener(response, false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private suspend fun measureLatencyStats(
  warmupIterations: Int = 2,
  sampleIterations: Int = 8,
  block: suspend () -> Any,
): LatencyStats {
  repeat(warmupIterations) {
    block()
  }
  val samples =
    MutableList(sampleIterations) {
      val start = System.nanoTime()
      block()
      (System.nanoTime() - start) / 1_000_000.0
    }
  return LatencyStats(
    iterations = sampleIterations,
    medianMs = percentile(samples, 0.5).round2(),
    p95Ms = percentile(samples, 0.95).round2(),
    maxMs = (samples.maxOrNull() ?: 0.0).round2(),
  )
}

private fun precision(correct: Int, selected: Int): Double =
  if (selected <= 0) {
    1.0
  } else {
    (correct.toDouble() / selected.toDouble()).round2()
  }

private fun rate(successes: Int, total: Int): Double =
  if (total <= 0) {
    1.0
  } else {
    (successes.toDouble() / total.toDouble()).round2()
  }

private fun median(values: List<Int>): Int {
  if (values.isEmpty()) {
    return 0
  }
  val sorted = values.sorted()
  val middle = sorted.size / 2
  return if (sorted.size % 2 == 1) {
    sorted[middle]
  } else {
    (sorted[middle - 1] + sorted[middle]) / 2
  }
}

private fun estimateInjectedMemoryTokens(
  pack: RoleplayMemoryContextPack,
  tokenEstimator: TokenEstimator,
): Int {
  pack.budgetReport?.estimatedTokens?.let { return it }
  val runtimeTokens =
    tokenEstimator.estimate(
      listOfNotNull(pack.runtimeState?.sceneJson, pack.runtimeState?.relationshipJson, pack.runtimeState?.activeEntitiesJson)
        .joinToString("\n"),
    )
  val openThreadTokens = tokenEstimator.estimate(pack.openThreads.joinToString("\n") { it.content })
  val memoryAtomTokens =
    tokenEstimator.estimate(pack.memoryAtoms.joinToString("\n") { "${it.subject} ${it.predicate} ${it.objectValue} ${it.evidenceQuote}" })
  val compactionTokens = pack.compactionEntries.sumOf { it.tokenEstimate }
  val fallbackSummaryTokens = tokenEstimator.estimate(pack.fallbackSummary?.summaryText.orEmpty())
  val fallbackMemoryTokens = tokenEstimator.estimate(pack.fallbackMemories.joinToString("\n") { it.content })
  return runtimeTokens + openThreadTokens + memoryAtomTokens + compactionTokens + fallbackSummaryTokens + fallbackMemoryTokens
}

private fun percentile(samples: List<Double>, quantile: Double): Double {
  if (samples.isEmpty()) {
    return 0.0
  }
  val sorted = samples.sorted()
  val index = ((sorted.lastIndex) * quantile).toInt().coerceIn(0, sorted.lastIndex)
  return sorted[index]
}

private fun Double.round2(): Double = round(this * 100.0) / 100.0

private fun MemoryAcceptanceScorecard.toJson(): JsonObject =
  JsonObject().apply {
    addProperty("semanticMemoryPrecision", semanticMemoryPrecision)
    addProperty("fallbackRecallPrecision", fallbackRecallPrecision)
    addProperty("episodicCompactionRecallRate", episodicCompactionRecallRate)
    addProperty("openThreadRecallRate", openThreadRecallRate)
    addProperty("continuityPassRate", continuityPassRate)
    addProperty("branchPollutionRate", branchPollutionRate)
    addProperty("driftRepairActivationRate", driftRepairActivationRate)
    addProperty("promptBudgetComplianceRate", promptBudgetComplianceRate)
    addProperty("injectedMemoryTokenMedian", injectedMemoryTokenMedian)
    addProperty("injectedMemoryTokenMax", injectedMemoryTokenMax)
    add(
      "tokenSamples",
      JsonArray().apply {
        tokenSamples.forEach(::add)
      },
    )
  }

private fun MemoryPerformanceScorecard.toJson(): JsonObject =
  JsonObject().apply {
    add("compileMemoryContext", compileMemoryContext.toJson())
    add("extractMemories", extractMemories.toJson())
    add("summarizeSession", summarizeSession.toJson())
    add("continuityRebuild", continuityRebuild.toJson())
  }

private fun LatencyStats.toJson(): JsonObject =
  JsonObject().apply {
    addProperty("iterations", iterations)
    addProperty("medianMs", medianMs)
    addProperty("p95Ms", p95Ms)
    addProperty("maxMs", maxMs)
  }
