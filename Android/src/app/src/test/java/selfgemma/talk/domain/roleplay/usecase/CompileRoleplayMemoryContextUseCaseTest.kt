package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

class CompileRoleplayMemoryContextUseCaseTest {
  @Test
  fun invoke_recordsStructuredSelectionsInEventsAndMarksUsage() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = testSession(turnCount = 6, now = now)
      val conversationRepository =
        MemoryContextConversationRepository(
          session = session,
          summary =
            SessionSummary(
              sessionId = session.id,
              version = 2,
              coveredUntilSeq = 8,
              summaryText = "Fallback summary should stay unused when structured recall is healthy.",
              tokenEstimate = 18,
              updatedAt = now,
            ),
        )
      val runtimeStateRepository =
        MemoryContextRuntimeStateRepository(
          snapshot =
            RuntimeStateSnapshot(
              sessionId = session.id,
              sceneJson = """{"location":"Observatory roof","weather":"cold"}""",
              relationshipJson = """{"user":"trusted ally"}""",
              activeEntitiesJson = """["Astra","Mae","repair drone"]""",
              updatedAt = now,
              sourceMessageId = "assistant-2",
            ),
        )
      val openThreadRepository =
        MemoryContextOpenThreadRepository(
          listOf(
            testOpenThread(
              id = "thread-promise",
              sessionId = session.id,
              type = OpenThreadType.PROMISE,
              content = "Return to the observatory before dawn.",
              priority = 10,
              now = now,
            ),
            testOpenThread(
              id = "thread-resolved",
              sessionId = session.id,
              type = OpenThreadType.TASK,
              content = "Already finished.",
              priority = 1,
              status = OpenThreadStatus.RESOLVED,
              now = now,
            ),
          ),
        )
      val memoryAtomRepository =
        MemoryContextMemoryAtomRepository(
          listOf(
            testMemoryAtom(
              id = "atom-observatory",
              sessionId = session.id,
              roleId = session.roleId,
              subject = "Captain Astra",
              predicate = "promised",
              objectValue = "to return to the observatory before dawn",
              evidenceQuote = "We promised we'd be back before sunrise.",
              now = now,
            ),
          ),
        )
      val memoryRepository =
        FakeLegacyMemoryRepository(
          listOf(
            testLegacyMemory(
              id = "legacy-unused",
              roleId = session.roleId,
              sessionId = session.id,
              content = "Legacy fallback should not be used in this scenario.",
              now = now,
            ),
          ),
        )

      val pack =
        CompileRoleplayMemoryContextUseCase(
          conversationRepository = conversationRepository,
          runtimeStateRepository = runtimeStateRepository,
          openThreadRepository = openThreadRepository,
          memoryAtomRepository = memoryAtomRepository,
          memoryRepository = memoryRepository,
          compactionCacheRepository = MemoryContextCompactionCacheRepository(),
          tokenEstimator = TokenEstimator(),
        )(
          session = session,
          role = testRole(memoryMaxItems = 4, now = now),
          recentMessages =
            listOf(
              testMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 8,
                side = MessageSide.ASSISTANT,
                content = "The observatory beacon is still online, and we owe that promise.",
                now = now,
              ),
            ),
          pendingUserInput = "Do you remember our promise to return to the observatory before dawn?",
        )

      assertNotNull(pack.runtimeState)
      assertEquals(1, pack.openThreads.size)
      assertEquals(1, pack.memoryAtoms.size)
      assertNull(pack.fallbackSummary)
      assertTrue(pack.fallbackMemories.isEmpty())
      assertEquals("user_question", pack.retrievalIntent.reason)
      assertTrue(pack.retrievalIntent.includeOpenThreads)
      assertTrue(pack.retrievalIntent.query.contains("observatory"))
      assertTrue(pack.retrievalIntent.query.contains("promise"))

      assertEquals(listOf("atom-observatory"), memoryAtomRepository.markedUsedIds)
      assertTrue(memoryRepository.markedUsedIds.isEmpty())

      assertEquals(
        listOf(
          SessionEventType.MEMORY_PLANNER_TRIGGERED,
          SessionEventType.MEMORY_QUERY_EXECUTED,
          SessionEventType.MEMORY_PACK_COMPILED,
        ),
        conversationRepository.events.map(SessionEvent::eventType),
      )

      val queryPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_QUERY_EXECUTED }
          .payloadJson
          .parseJsonObject()
      assertTrue(queryPayload["runtimeStateHit"].asBoolean)
      assertEquals(1, queryPayload["openThreadCount"].asInt)
      assertEquals(1, queryPayload["memoryAtomCount"].asInt)
      assertEquals(0, queryPayload["fallbackMemoryCount"].asInt)
      assertEquals(1, queryPayload.getAsJsonArray("openThreadMatches").size())
      assertEquals("thread-promise", queryPayload.getAsJsonArray("openThreadMatches")[0].asJsonObject["id"].asString)
      assertEquals("atom-observatory", queryPayload.getAsJsonArray("memoryAtomMatches")[0].asJsonObject["id"].asString)
      assertEquals(0, queryPayload.getAsJsonArray("fallbackMemoryMatches").size())

      val compiledPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
          .payloadJson
          .parseJsonObject()
      assertTrue(compiledPayload.getAsJsonObject("runtimeState")["present"].asBoolean)
      assertEquals(1, compiledPayload.getAsJsonArray("openThreads").size())
      assertEquals(1, compiledPayload.getAsJsonArray("memoryAtoms").size())
      assertFalse(compiledPayload.getAsJsonObject("fallbackSummary")["present"].asBoolean)
      assertEquals(0, compiledPayload.getAsJsonArray("fallbackMemories").size())
      assertTrue(compiledPayload["runtimeStateTokens"].asInt > 0)
      assertTrue(compiledPayload["openThreadTokens"].asInt > 0)
      assertTrue(compiledPayload["memoryAtomTokens"].asInt > 0)
    }

  @Test
  fun invoke_fallsBackToSummaryAndLegacyMemoriesWhenStructuredRecallMissing() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = testSession(turnCount = 5, now = now)
      val summary =
        SessionSummary(
          sessionId = session.id,
          version = 3,
          coveredUntilSeq = 12,
          summaryText = "Astra and Mae agreed to reach the relay tower before sunrise.",
          tokenEstimate = 14,
          updatedAt = now,
        )
      val conversationRepository = MemoryContextConversationRepository(session = session, summary = summary)
      val memoryAtomRepository = MemoryContextMemoryAtomRepository(emptyList())
      val memoryRepository =
        FakeLegacyMemoryRepository(
          listOf(
            testLegacyMemory(
              id = "legacy-relay",
              roleId = session.roleId,
              sessionId = session.id,
              content = "The relay tower route was mapped through the greenhouse.",
              now = now,
            ),
            testLegacyMemory(
              id = "legacy-sunrise",
              roleId = session.roleId,
              sessionId = session.id,
              content = "They promised to arrive before sunrise.",
              now = now,
            ),
          ),
        )

      val pack =
        CompileRoleplayMemoryContextUseCase(
          conversationRepository = conversationRepository,
          runtimeStateRepository = MemoryContextRuntimeStateRepository(snapshot = null),
          openThreadRepository = MemoryContextOpenThreadRepository(emptyList()),
          memoryAtomRepository = memoryAtomRepository,
          memoryRepository = memoryRepository,
          compactionCacheRepository = MemoryContextCompactionCacheRepository(),
          tokenEstimator = TokenEstimator(),
        )(
          session = session,
          role = testRole(memoryMaxItems = 3, now = now),
          recentMessages =
            listOf(
              testMessage(
                id = "user-11",
                sessionId = session.id,
                seq = 11,
                side = MessageSide.USER,
                content = "Remind me how we planned to reach the relay tower before sunrise.",
                now = now,
              ),
            ),
          pendingUserInput = "Remind me how we planned to reach the relay tower before sunrise.",
        )

      assertNull(pack.runtimeState)
      assertTrue(pack.openThreads.isEmpty())
      assertTrue(pack.memoryAtoms.isEmpty())
      assertNotNull(pack.fallbackSummary)
      assertEquals(summary.summaryText, pack.fallbackSummary!!.summaryText)
      assertEquals(summary.version, pack.fallbackSummary!!.version)
      assertEquals(summary.coveredUntilSeq, pack.fallbackSummary!!.coveredUntilSeq)
      assertEquals(2, pack.fallbackMemories.size)
      assertEquals("explicit_recall", pack.retrievalIntent.reason)

      assertTrue(memoryAtomRepository.markedUsedIds.isEmpty())
      assertEquals(listOf("legacy-relay", "legacy-sunrise"), memoryRepository.markedUsedIds)

      val queryPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_QUERY_EXECUTED }
          .payloadJson
          .parseJsonObject()
      assertFalse(queryPayload["runtimeStateHit"].asBoolean)
      assertEquals(0, queryPayload["openThreadCount"].asInt)
      assertEquals(0, queryPayload["memoryAtomCount"].asInt)
      assertEquals(2, queryPayload["fallbackMemoryCount"].asInt)
      assertEquals(2, queryPayload.getAsJsonArray("fallbackMemoryMatches").size())

      val compiledPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
          .payloadJson
          .parseJsonObject()
      assertFalse(compiledPayload.getAsJsonObject("runtimeState")["present"].asBoolean)
      assertTrue(compiledPayload.getAsJsonObject("fallbackSummary")["present"].asBoolean)
      assertEquals(summary.coveredUntilSeq, compiledPayload.getAsJsonObject("fallbackSummary")["coveredUntilSeq"].asInt)
      assertEquals(2, compiledPayload.getAsJsonArray("fallbackMemories").size())
      assertTrue(compiledPayload["fallbackSummaryTokens"].asInt > 0)
      assertTrue(compiledPayload["fallbackMemoryTokens"].asInt > 0)
    }

  @Test
  fun invoke_mergesCompactionCacheIntoFallbackSummaryForEpisodicRecall() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = testSession(turnCount = 18, now = now)
      val summary =
        SessionSummary(
          sessionId = session.id,
          version = 4,
          coveredUntilSeq = 18,
          summaryText = "Astra and Mae have kept the observatory promise alive, but the hatch mystery remains unresolved.",
          tokenEstimate = 18,
          updatedAt = now,
        )
      val conversationRepository = MemoryContextConversationRepository(session = session, summary = summary)
      val compactionCacheRepository =
        MemoryContextCompactionCacheRepository(
          listOf(
            CompactionCacheEntry(
              id = "compact-scene-1",
              sessionId = session.id,
              rangeStartMessageId = "message-1",
              rangeEndMessageId = "message-12",
              summaryType = CompactionSummaryType.SCENE,
              compactText = "Scene window: - Captain Mae: We hid the forged pass near the harbor gate. - Assistant: The beacon key stayed with Astra during the retreat.",
              sourceHash = "hash-1",
              tokenEstimate = 24,
              updatedAt = now,
            ),
          ),
        )

      val pack =
        CompileRoleplayMemoryContextUseCase(
          conversationRepository = conversationRepository,
          runtimeStateRepository = MemoryContextRuntimeStateRepository(snapshot = null),
          openThreadRepository = MemoryContextOpenThreadRepository(emptyList()),
          memoryAtomRepository = MemoryContextMemoryAtomRepository(emptyList()),
          memoryRepository = FakeLegacyMemoryRepository(emptyList()),
          compactionCacheRepository = compactionCacheRepository,
          tokenEstimator = TokenEstimator(),
        )(
          session = session,
          role = testRole(memoryMaxItems = 3, now = now),
          recentMessages =
            listOf(
              testMessage(
                id = "assistant-18",
                sessionId = session.id,
                seq = 18,
                side = MessageSide.ASSISTANT,
                content = "The forged pass is still the cleanest way back to the observatory.",
                now = now,
              ),
            ),
          pendingUserInput = "What happened to the forged pass and the beacon key during our last retreat?",
        )

      assertEquals(1, pack.compactionEntries.size)
      assertNotNull(pack.fallbackSummary)
      assertTrue(pack.fallbackSummary!!.summaryText.contains("Compacted history:"))
      assertTrue(pack.fallbackSummary!!.summaryText.contains("scene: Scene window: - Captain Mae: We hid the forged pass near the harbor gate."))

      val queryPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_QUERY_EXECUTED }
          .payloadJson
          .parseJsonObject()
      assertEquals(1, queryPayload["compactionCount"].asInt)
      assertEquals(1, queryPayload.getAsJsonArray("compactionMatches").size())

      val compiledPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
          .payloadJson
          .parseJsonObject()
      assertEquals(1, compiledPayload["compactionCount"].asInt)
      assertEquals(1, compiledPayload.getAsJsonArray("compactionEntries").size())
      assertTrue(compiledPayload.getAsJsonObject("fallbackSummary")["summary"].asString.contains("Compacted history"))
    }

  @Test
  fun invoke_trimsMemoryPackForAggressiveBudgetAndKeepsHighestSignalSelections() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = testSession(turnCount = 18, now = now)
      val conversationRepository = MemoryContextConversationRepository(session = session)
      val runtimeStateRepository =
        MemoryContextRuntimeStateRepository(
          snapshot =
            RuntimeStateSnapshot(
              sessionId = session.id,
              sceneJson = """{"location":"Observatory roof","goal":"return before dawn"}""",
              relationshipJson = """{"currentMood":"tense","trust":"fragile but real"}""",
              activeEntitiesJson = """["Astra","Mae","observatory beacon"]""",
              updatedAt = now,
              sourceMessageId = "assistant-4",
            ),
        )
      val openThreadRepository =
        MemoryContextOpenThreadRepository(
          listOf(
            testOpenThread(
              id = "thread-irrelevant",
              sessionId = session.id,
              type = OpenThreadType.TASK,
              content = "Sort the supply manifests for the cargo bay before the quartermaster starts the next inspection cycle.",
              priority = 22,
              now = now,
            ),
            testOpenThread(
              id = "thread-relevant",
              sessionId = session.id,
              type = OpenThreadType.PROMISE,
              content = "Return to the observatory before dawn and explain the family crest once the beacon is stable again.",
              priority = 95,
              now = now,
            ),
            testOpenThread(
              id = "thread-mystery",
              sessionId = session.id,
              type = OpenThreadType.MYSTERY,
              content = "Work out why the maintenance hatch was left unlocked during the storm.",
              priority = 31,
              now = now,
            ),
          ),
        )
      val memoryAtomRepository =
        MemoryContextMemoryAtomRepository(
          listOf(
            testMemoryAtom(
              id = "atom-episode",
              sessionId = session.id,
              roleId = session.roleId,
              subject = "scene",
              predicate = "event",
              objectValue = "They crossed the flooded greenhouse tunnel while alarms echoed through the broken glass canopy.",
              evidenceQuote = "We crossed the greenhouse tunnel while the alarms were going off.",
              now = now,
            ).copy(
              namespace = MemoryNamespace.EPISODIC,
              salience = 0.42f,
            ),
            testMemoryAtom(
              id = "atom-preference",
              sessionId = session.id,
              roleId = session.roleId,
              subject = "user",
              predicate = "preference",
              objectValue = "prefers black coffee before dawn, especially before returning to the observatory",
              evidenceQuote = "I need black coffee before dawn if we're going back there.",
              now = now,
            ).copy(
              namespace = MemoryNamespace.SEMANTIC,
              salience = 0.94f,
            ),
            testMemoryAtom(
              id = "atom-world",
              sessionId = session.id,
              roleId = session.roleId,
              subject = "observatory beacon",
              predicate = "status",
              objectValue = "still unstable after the storm and likely to fail again without manual calibration",
              evidenceQuote = "The beacon is still unstable after the storm.",
              now = now,
            ).copy(
              namespace = MemoryNamespace.WORLD,
              salience = 0.67f,
            ),
          ),
        )

      val pack =
        CompileRoleplayMemoryContextUseCase(
          conversationRepository = conversationRepository,
          runtimeStateRepository = runtimeStateRepository,
          openThreadRepository = openThreadRepository,
          memoryAtomRepository = memoryAtomRepository,
          memoryRepository = FakeLegacyMemoryRepository(emptyList()),
          compactionCacheRepository = MemoryContextCompactionCacheRepository(),
          tokenEstimator = TokenEstimator(),
        )(
          session = session,
          role = testRole(memoryMaxItems = 4, now = now),
          recentMessages =
            listOf(
              testMessage(
                id = "assistant-4",
                sessionId = session.id,
                seq = 16,
                side = MessageSide.ASSISTANT,
                content = "The observatory beacon is still unstable, but I have not forgotten the promise.",
                now = now,
              ),
            ),
          pendingUserInput = "Do you remember our promise to return to the observatory before dawn, and that I need black coffee first?",
          contextProfile =
            ModelContextProfile(
              contextWindowTokens = 1024,
              reservedOutputTokens = 384,
              reservedThinkingTokens = 0,
              safetyMarginTokens = 256,
            ),
          budgetMode = PromptBudgetMode.AGGRESSIVE,
        )

      assertNotNull(pack.runtimeState)
      assertEquals(listOf("thread-relevant"), pack.openThreads.map { it.id })
      assertEquals(listOf("atom-preference"), pack.memoryAtoms.map { it.id })
      assertNotNull(pack.budgetReport)
      assertEquals(PromptBudgetMode.AGGRESSIVE, pack.budgetReport!!.mode)
      assertTrue(pack.budgetReport!!.estimatedTokens <= pack.budgetReport!!.targetTokens)
      assertTrue(pack.budgetReport!!.droppedOpenThreadCount >= 2)
      assertTrue(pack.budgetReport!!.droppedMemoryAtomCount >= 2)

      val compiledPayload =
        conversationRepository.events
          .first { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
          .payloadJson
          .parseJsonObject()
      assertEquals("AGGRESSIVE", compiledPayload.getAsJsonObject("budget")["mode"].asString)
      assertEquals(1, compiledPayload.getAsJsonArray("openThreads").size())
      assertEquals("thread-relevant", compiledPayload.getAsJsonArray("openThreads")[0].asJsonObject["id"].asString)
      assertEquals(1, compiledPayload.getAsJsonArray("memoryAtoms").size())
      assertEquals("atom-preference", compiledPayload.getAsJsonArray("memoryAtoms")[0].asJsonObject["id"].asString)
    }
}

private class MemoryContextConversationRepository(
  private val session: Session,
  private val summary: SessionSummary? = null,
  private val messages: List<Message> = emptyList(),
) : ConversationRepository {
  val events = mutableListOf<SessionEvent>()

  override fun observeSessions(): Flow<List<Session>> {
    return flowOf(listOf(session))
  }

  override fun observeMessages(sessionId: String): Flow<List<Message>> {
    return flowOf(messages.filter { it.sessionId == sessionId })
  }

  override suspend fun listMessages(sessionId: String): List<Message> {
    return messages.filter { it.sessionId == sessionId }
  }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> {
    return messages.filter { it.sessionId == sessionId && it.isCanonical }
  }

  override suspend fun getMessage(messageId: String): Message? {
    return messages.firstOrNull { it.id == messageId }
  }

  override suspend fun getSession(sessionId: String): Session? {
    return session.takeIf { it.id == sessionId }
  }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) {
    error("Not needed in this test")
  }

  override suspend fun archiveSession(sessionId: String) {
    error("Not needed in this test")
  }

  override suspend fun deleteSession(sessionId: String) {
    error("Not needed in this test")
  }

  override suspend fun appendMessage(message: Message) {
    error("Not needed in this test")
  }

  override suspend fun updateMessage(message: Message) {
    error("Not needed in this test")
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    error("Not needed in this test")
  }

  override suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    error("Not needed in this test")
  }

  override suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    error("Not needed in this test")
  }

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    error("Not needed in this test")
  }

  override suspend fun nextMessageSeq(sessionId: String): Int {
    error("Not needed in this test")
  }

  override suspend fun getSummary(sessionId: String): SessionSummary? {
    return summary?.takeIf { it.sessionId == sessionId }
  }

  override suspend fun upsertSummary(summary: SessionSummary) {
    error("Not needed in this test")
  }

  override suspend fun deleteSummary(sessionId: String) {
    error("Not needed in this test")
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> {
    return events.filter { it.sessionId == sessionId }
  }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class MemoryContextRuntimeStateRepository(
  private val snapshot: RuntimeStateSnapshot?,
) : RuntimeStateRepository {
  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? {
    return snapshot?.takeIf { it.sessionId == sessionId }
  }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    error("Not needed in this test")
  }

  override suspend fun deleteBySession(sessionId: String) {
    error("Not needed in this test")
  }
}

private class MemoryContextMemoryAtomRepository(
  atoms: List<MemoryAtom>,
) : MemoryAtomRepository {
  private val storedAtoms = atoms.toMutableList()
  val markedUsedIds = mutableListOf<String>()

  override suspend fun listBySession(sessionId: String): List<MemoryAtom> {
    return storedAtoms.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(atom: MemoryAtom) {
    storedAtoms.removeAll { it.id == atom.id }
    storedAtoms += atom
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    markedUsedIds += memoryIds
  }

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    error("Not needed in this test")
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) {
    error("Not needed in this test")
  }

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> {
    return storedAtoms
      .filter { it.sessionId == sessionId && it.roleId == roleId && !it.tombstone }
      .take(limit)
  }
}

private class MemoryContextOpenThreadRepository(
  threads: List<OpenThread>,
) : OpenThreadRepository {
  private val storedThreads = threads.toMutableList()

  override suspend fun listBySession(sessionId: String): List<OpenThread> {
    return storedThreads.filter { it.sessionId == sessionId }
  }

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> {
    return storedThreads.filter { it.sessionId == sessionId && it.status == status }
  }

  override suspend fun upsert(thread: OpenThread) {
    storedThreads.removeAll { it.id == thread.id }
    storedThreads += thread
  }

  override suspend fun deleteBySession(sessionId: String) {
    error("Not needed in this test")
  }

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) {
    error("Not needed in this test")
  }
}

private class MemoryContextCompactionCacheRepository(
  entries: List<CompactionCacheEntry> = emptyList(),
) : CompactionCacheRepository {
  private val storedEntries = entries.toMutableList()

  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> {
    return storedEntries.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(entry: CompactionCacheEntry) {
    storedEntries.removeAll { it.id == entry.id }
    storedEntries += entry
  }

  override suspend fun deleteBySession(sessionId: String) {
    storedEntries.removeAll { it.sessionId == sessionId }
  }
}

private class FakeLegacyMemoryRepository(
  memories: List<MemoryItem>,
) : MemoryRepository {
  private val storedMemories = memories.toMutableList()
  val markedUsedIds = mutableListOf<String>()

  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> {
    return storedMemories.filter { it.roleId == roleId && it.sessionId == null }
  }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> {
    return storedMemories.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(memory: MemoryItem) {
    storedMemories.removeAll { it.id == memory.id }
    storedMemories += memory
  }

  override suspend fun deactivate(memoryId: String) {
    error("Not needed in this test")
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    markedUsedIds += memoryIds
  }

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> {
    return storedMemories
      .filter { it.roleId == roleId && (sessionId == null || it.sessionId == sessionId) && it.active }
      .take(limit)
  }
}

private fun testSession(turnCount: Int, now: Long): Session {
  return Session(
    id = "session-1",
    roleId = "role-1",
    title = "Observatory Run",
    activeModelId = "gemma-3n",
    createdAt = now,
    updatedAt = now,
    lastMessageAt = now,
    turnCount = turnCount,
  )
}

private fun testRole(memoryMaxItems: Int, now: Long): RoleCard {
  return RoleCard(
    id = "role-1",
    name = "Captain Astra",
    summary = "A disciplined starship captain.",
    systemPrompt = "Stay focused on continuity and tactical realism.",
    memoryEnabled = true,
    memoryMaxItems = memoryMaxItems,
    createdAt = now,
    updatedAt = now,
  )
}

private fun testMessage(
  id: String,
  sessionId: String,
  seq: Int,
  side: MessageSide,
  content: String,
  now: Long,
): Message {
  return Message(
    id = id,
    sessionId = sessionId,
    seq = seq,
    side = side,
    status = MessageStatus.COMPLETED,
    content = content,
    createdAt = now,
    updatedAt = now,
  )
}

private fun testOpenThread(
  id: String,
  sessionId: String,
  type: OpenThreadType,
  content: String,
  priority: Int,
  now: Long,
  status: OpenThreadStatus = OpenThreadStatus.OPEN,
): OpenThread {
  return OpenThread(
    id = id,
    sessionId = sessionId,
    type = type,
    content = content,
    owner = OpenThreadOwner.SHARED,
    priority = priority,
    status = status,
    sourceMessageIds = listOf("assistant-2"),
    createdAt = now,
    updatedAt = now,
  )
}

private fun testMemoryAtom(
  id: String,
  sessionId: String,
  roleId: String,
  subject: String,
  predicate: String,
  objectValue: String,
  evidenceQuote: String,
  now: Long,
): MemoryAtom {
  return MemoryAtom(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = MemoryPlane.CANON,
    namespace = MemoryNamespace.PROMISE,
    subject = subject,
    predicate = predicate,
    objectValue = objectValue,
    normalizedObjectValue = objectValue.lowercase(),
    stability = MemoryStability.STABLE,
    epistemicStatus = MemoryEpistemicStatus.OBSERVED,
    confidence = 0.92f,
    branchScope = MemoryBranchScope.ACCEPTED_ONLY,
    sourceMessageIds = listOf("assistant-2"),
    evidenceQuote = evidenceQuote,
    createdAt = now,
    updatedAt = now,
  )
}

private fun testLegacyMemory(
  id: String,
  roleId: String,
  sessionId: String,
  content: String,
  now: Long,
): MemoryItem {
  return MemoryItem(
    id = id,
    roleId = roleId,
    sessionId = sessionId,
    category = MemoryCategory.PLOT,
    content = content,
    normalizedHash = content.lowercase(),
    confidence = 0.8f,
    createdAt = now,
    updatedAt = now,
  )
}

private fun String.parseJsonObject(): JsonObject {
  return JsonParser.parseString(this).asJsonObject
}
