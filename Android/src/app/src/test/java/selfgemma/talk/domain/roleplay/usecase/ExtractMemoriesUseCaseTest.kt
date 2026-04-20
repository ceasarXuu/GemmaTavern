package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

class ExtractMemoriesUseCaseTest {
  @Test
  fun addManualMemory_createsPinnedMemoryAndDeduplicatesByHash() =
    runBlocking {
      val memoryRepository = FakeMemoryRepository()
      val conversationRepository = ExtractMemoriesConversationRepository()
      val useCase =
        ExtractMemoriesUseCase(
          memoryRepository = memoryRepository,
          memoryAtomRepository = ExtractMemoriesAtomRepository(),
          openThreadRepository = ExtractMemoriesThreadRepository(),
          runtimeStateRepository = ExtractMemoriesRuntimeStateRepository(),
          conversationRepository = conversationRepository,
          validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
        )
      val session = testSession()
      val role = testRole()

      val first =
        useCase.addManualMemory(
          session = session,
          role = role,
          content = "  We already promised to reach the observatory before dawn.  ",
          category = MemoryCategory.PLOT,
        )
      val second =
        useCase.addManualMemory(
          session = session,
          role = role,
          content = "We already promised to reach the observatory before dawn.",
          category = MemoryCategory.PLOT,
        )

      assertNotNull(first)
      assertNotNull(second)
      assertEquals(first?.id, second?.id)
      assertEquals(1, memoryRepository.memories.size)
      assertTrue(memoryRepository.memories.values.single().pinned)
      assertEquals(
        "We already promised to reach the observatory before dawn.",
        memoryRepository.memories.values.single().content,
      )
      assertEquals(2, conversationRepository.events.size)
      assertTrue(conversationRepository.events.all { it.eventType.name == "MEMORY_UPSERT" })
      assertTrue(conversationRepository.events.last().payloadJson.contains("\"source\":\"manual\""))
    }

  @Test
  fun invoke_userPreferenceCorrection_tombstonesConflictingAtomAndWritesReplacement() =
    runBlocking {
      val session = testSession()
      val role = testRole()
      val memoryRepository =
        FakeMemoryRepository(
          listOf(
            MemoryItem(
              id = "memory-black-coffee",
              roleId = role.id,
              sessionId = session.id,
              category = MemoryCategory.PREFERENCE,
              content = "I prefer black coffee.",
              normalizedHash = "i prefer black coffee.",
              confidence = 0.88f,
              createdAt = 1L,
              updatedAt = 1L,
            ),
          ),
        )
      val atomRepository =
        ExtractMemoriesAtomRepository(
          listOf(
            testPreferenceAtom(
              id = "atom-black-coffee",
              sessionId = session.id,
              roleId = role.id,
              content = "I prefer black coffee.",
            ),
          ),
        )
      val conversationRepository = ExtractMemoriesConversationRepository()
      val useCase =
        ExtractMemoriesUseCase(
          memoryRepository = memoryRepository,
          memoryAtomRepository = atomRepository,
          openThreadRepository = ExtractMemoriesThreadRepository(),
          runtimeStateRepository = ExtractMemoriesRuntimeStateRepository(),
          conversationRepository = conversationRepository,
          validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
        )

      useCase(
        session = session,
        role = role,
        userMessage =
          testMessage(
            id = "user-1",
            sessionId = session.id,
            side = MessageSide.USER,
            content = "I prefer latte instead of black coffee.",
          ),
        assistantMessage = null,
      )

      assertTrue(atomRepository.atoms.values.any { it.id == "atom-black-coffee" && it.tombstone })
      val replacement =
        atomRepository.atoms.values.firstOrNull { !it.tombstone && it.objectValue == "I prefer latte." }
      assertNotNull(replacement)
      assertEquals(MemoryStability.STABLE, replacement!!.stability)
      assertTrue(memoryRepository.memories.values.any { it.content == "I prefer latte." && it.active })
      assertTrue(memoryRepository.memories.values.any { it.id == "memory-black-coffee" && !it.active })
      val correctionEvent =
        conversationRepository.events.firstOrNull { it.eventType == selfgemma.talk.domain.roleplay.model.SessionEventType.MEMORY_CORRECTION_APPLIED }
      assertNotNull(correctionEvent)
      val correctionPayload = JsonParser.parseString(correctionEvent!!.payloadJson).asJsonObject
      assertEquals(1, correctionPayload["correctedAtomCount"].asInt)
      assertEquals("black coffee", correctionPayload["rejectedValue"].asString)
    }

  @Test
  fun invoke_userLocationCorrection_updatesRuntimeStateImmediately() =
    runBlocking {
      val session = testSession()
      val runtimeStateRepository =
        ExtractMemoriesRuntimeStateRepository(
          RuntimeStateSnapshot(
            sessionId = session.id,
            sceneJson = """{"location":"clocktower","goal":"wait for the signal"}""",
            relationshipJson = """{"currentMood":"tense"}""",
            activeEntitiesJson = """{"focus":["user","Captain Astra"]}""",
            updatedAt = 1L,
            sourceMessageId = "assistant-0",
          ),
        )
      val conversationRepository = ExtractMemoriesConversationRepository()
      val useCase =
        ExtractMemoriesUseCase(
          memoryRepository = FakeMemoryRepository(),
          memoryAtomRepository = ExtractMemoriesAtomRepository(),
          openThreadRepository = ExtractMemoriesThreadRepository(),
          runtimeStateRepository = runtimeStateRepository,
          conversationRepository = conversationRepository,
          validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
        )

      useCase(
        session = session,
        role = testRole(),
        userMessage =
          testMessage(
            id = "user-2",
            sessionId = session.id,
            side = MessageSide.USER,
            content = "We're not at the clocktower, we're at the harbor.",
          ),
        assistantMessage = null,
      )

      val updatedSnapshot = runtimeStateRepository.snapshot
      assertNotNull(updatedSnapshot)
      val sceneJson = JsonParser.parseString(updatedSnapshot!!.sceneJson).asJsonObject
      assertEquals("the harbor", sceneJson["location"].asString)
      val correctionEvent =
        conversationRepository.events.firstOrNull { it.eventType == selfgemma.talk.domain.roleplay.model.SessionEventType.MEMORY_CORRECTION_APPLIED }
      assertNotNull(correctionEvent)
      val correctionPayload = JsonParser.parseString(correctionEvent!!.payloadJson).asJsonObject
      assertEquals("clocktower", correctionPayload["locationBefore"].asString)
      assertEquals("the harbor", correctionPayload["locationAfter"].asString)
    }

  @Test
  fun invoke_updatesStructuredRuntimeStateWithSceneRelationshipAndEntitySignals() =
    runBlocking {
      val session = testSession()
      val runtimeStateRepository = ExtractMemoriesRuntimeStateRepository()
      val useCase =
        ExtractMemoriesUseCase(
          memoryRepository = FakeMemoryRepository(),
          memoryAtomRepository = ExtractMemoriesAtomRepository(),
          openThreadRepository = ExtractMemoriesThreadRepository(),
          runtimeStateRepository = runtimeStateRepository,
          conversationRepository = ExtractMemoriesConversationRepository(),
          validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
        )

      useCase(
        session = session,
        role = testRole(),
        userMessage =
          testMessage(
            id = "user-3",
            sessionId = session.id,
            side = MessageSide.USER,
            content = "We're at the harbor. Tonight we need to trade the forged pass to Dockmaster Harlan before dawn.",
          ),
        assistantMessage =
          testMessage(
            id = "assistant-3",
            sessionId = session.id,
            side = MessageSide.ASSISTANT,
            content = "I will protect you and talk to Dockmaster Harlan. I'm worried the patrols are closing in, so keep the beacon key ready.",
          ),
      )

      val snapshot = runtimeStateRepository.snapshot
      assertNotNull(snapshot)

      val sceneJson = JsonParser.parseString(snapshot!!.sceneJson).asJsonObject
      val relationshipJson = JsonParser.parseString(snapshot.relationshipJson).asJsonObject
      val activeEntitiesJson = JsonParser.parseString(snapshot.activeEntitiesJson).asJsonObject

      assertEquals("the harbor", sceneJson["location"].asString)
      assertEquals("before dawn", sceneJson["time"].asString)
      assertEquals("Tonight we need to trade the forged pass to Dockmaster Harlan before dawn", sceneJson["currentGoal"].asString)
      assertEquals("high", sceneJson["dangerLevel"].asString)
      assertEquals("Tonight we need to trade the forged pass to Dockmaster Harlan before dawn", sceneJson["activeTopic"].asString)
      assertTrue(sceneJson.getAsJsonArray("importantItems").any { it.asString == "forged pass" })
      assertTrue(sceneJson.getAsJsonArray("importantItems").any { it.asString == "beacon key" })

      assertEquals("tense", relationshipJson["currentMood"].asString)
      assertTrue(relationshipJson["trust"].asInt >= 3)
      assertTrue(relationshipJson["tension"].asInt >= 2)
      assertTrue(relationshipJson["fear"].asInt >= 2)
      assertTrue(relationshipJson["initiative"].asInt >= 3)

      assertTrue(activeEntitiesJson.getAsJsonArray("present").any { it.asString == "user" })
      assertTrue(activeEntitiesJson.getAsJsonArray("present").any { it.asString == "Captain Astra" })
      assertTrue(activeEntitiesJson.getAsJsonArray("present").any { it.asString == "Dockmaster Harlan" })
      assertTrue(activeEntitiesJson.getAsJsonArray("focus").any { it.asString == "forged pass" })
    }

  private fun testRole(): RoleCard {
    val now = System.currentTimeMillis()
    return RoleCard(
      id = "role-1",
      name = "Captain Astra",
      summary = "A disciplined starship captain.",
      systemPrompt = "Remain calm and strategic.",
      createdAt = now,
      updatedAt = now,
    )
  }

  private fun testSession(): Session {
    val now = System.currentTimeMillis()
    return Session(
      id = "session-1",
      roleId = "role-1",
      title = "Bridge Briefing",
      activeModelId = "gemma-3n",
      createdAt = now,
      updatedAt = now,
      lastMessageAt = now,
    )
  }

  private fun testMessage(
    id: String,
    sessionId: String,
    side: MessageSide,
    content: String,
  ): Message {
    val now = System.currentTimeMillis()
    return Message(
      id = id,
      sessionId = sessionId,
      seq = 1,
      side = side,
      status = selfgemma.talk.domain.roleplay.model.MessageStatus.COMPLETED,
      content = content,
      createdAt = now,
      updatedAt = now,
    )
  }

  private fun testPreferenceAtom(
    id: String,
    sessionId: String,
    roleId: String,
    content: String,
  ): MemoryAtom {
    val now = System.currentTimeMillis()
    return MemoryAtom(
      id = id,
      sessionId = sessionId,
      roleId = roleId,
      plane = MemoryPlane.SHARED,
      namespace = MemoryNamespace.SEMANTIC,
      subject = "user",
      predicate = "preference",
      objectValue = content,
      normalizedObjectValue = content.lowercase(),
      stability = MemoryStability.STABLE,
      epistemicStatus = MemoryEpistemicStatus.SELF_REPORT,
      branchScope = MemoryBranchScope.ACCEPTED_ONLY,
      sourceMessageIds = listOf("user-0"),
      evidenceQuote = content,
      createdAt = now,
      updatedAt = now,
    )
  }
}

private class FakeMemoryRepository : MemoryRepository {
  val memories = linkedMapOf<String, MemoryItem>()

  constructor(seed: List<MemoryItem> = emptyList()) {
    seed.forEach { memories[it.id] = it }
  }

  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> {
    return memories.values.filter { it.roleId == roleId && it.sessionId == null }
  }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> {
    return memories.values.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(memory: MemoryItem) {
    memories[memory.id] = memory
  }

  override suspend fun deactivate(memoryId: String) {
    val current = memories[memoryId] ?: return
    memories[memoryId] = current.copy(active = false)
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    memoryIds.forEach { id ->
      val current = memories[id] ?: return@forEach
      memories[id] = current.copy(lastUsedAt = usedAt)
    }
  }

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> {
    return memories.values.take(limit)
  }
}

private class ExtractMemoriesAtomRepository : MemoryAtomRepository {
  val atoms = linkedMapOf<String, MemoryAtom>()

  constructor(seed: List<MemoryAtom> = emptyList()) {
    seed.forEach { atoms[it.id] = it }
  }

  override suspend fun listBySession(sessionId: String): List<MemoryAtom> {
    return atoms.values.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(atom: MemoryAtom) {
    atoms[atom.id] = atom
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) = Unit

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    val current = atoms[memoryId] ?: return
    atoms[memoryId] = current.copy(tombstone = true, updatedAt = updatedAt)
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) = Unit

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> {
    return atoms.values.filter { it.sessionId == sessionId && it.roleId == roleId && !it.tombstone }.take(limit)
  }
}

private class ExtractMemoriesThreadRepository : OpenThreadRepository {
  override suspend fun listBySession(sessionId: String): List<OpenThread> = emptyList()

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> = emptyList()

  override suspend fun upsert(thread: OpenThread) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) = Unit
}

private class ExtractMemoriesRuntimeStateRepository : RuntimeStateRepository {
  var snapshot: RuntimeStateSnapshot? = null

  constructor(snapshot: RuntimeStateSnapshot? = null) {
    this.snapshot = snapshot
  }

  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? =
    snapshot?.takeIf { it.sessionId == sessionId }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun deleteBySession(sessionId: String) {
    snapshot = snapshot?.takeUnless { it.sessionId == sessionId }
  }
}

private class ExtractMemoriesConversationRepository : ConversationRepository {
  val events = mutableListOf<SessionEvent>()

  override fun observeSessions() = kotlinx.coroutines.flow.flowOf(emptyList<Session>())

  override fun observeMessages(sessionId: String) = kotlinx.coroutines.flow.flowOf(emptyList<Message>())

  override suspend fun listMessages(sessionId: String): List<Message> = emptyList()

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> = emptyList()

  override suspend fun getMessage(messageId: String): Message? = null

  override suspend fun getSession(sessionId: String): Session? = null

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) = Unit

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) = Unit

  override suspend fun updateMessage(message: Message) = Unit

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? = null

  override suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int = 0

  override suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int = 0

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) = Unit

  override suspend fun nextMessageSeq(sessionId: String): Int = 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = null

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}
