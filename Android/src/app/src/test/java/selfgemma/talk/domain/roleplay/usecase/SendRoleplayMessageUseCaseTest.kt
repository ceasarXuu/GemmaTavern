package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
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
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.runtime.LlmModelHelper
import selfgemma.talk.testing.FakeDataStoreRepository

class SendRoleplayMessageUseCaseTest {
  @Test
  fun invoke_backfillsCharacterKernelForLegacyRolesBeforeGeneration() =
    runBlocking {
      val fixture = createSendRoleplayFixture(runtimeHelper = StableRuntimeHelper())

      val result =
        fixture.useCase(
          sessionId = fixture.session.id,
          model = fixture.model,
          userInput = "Keep the plan tight.",
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertNotNull(result.assistantMessage)
      assertEquals(MessageStatus.COMPLETED, result.assistantMessage!!.status)
      assertEquals(1, fixture.roleRepository.savedRoles.size)
      val savedRole = fixture.roleRepository.savedRoles.single()
      assertNotNull(savedRole.runtimeProfile)
      assertNotNull(savedRole.runtimeProfile!!.characterKernel)
      assertTrue(savedRole.runtimeProfile!!.compiledCorePrompt.isNotBlank())
    }

  @Test
  fun invoke_recompilesMemoryContextWithAggressiveBudgetAfterResetOverflow() =
    runBlocking {
      val runtimeHelper = OverflowOnFirstResetRuntimeHelper()
      val fixture = createSendRoleplayFixture(runtimeHelper = runtimeHelper)

      val result =
        fixture.useCase(
          sessionId = fixture.session.id,
          model = fixture.model,
          userInput = "Do you still remember our promise to return to the observatory before dawn?",
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertNotNull(result.assistantMessage)
      assertEquals(MessageStatus.COMPLETED, result.assistantMessage!!.status)
      assertEquals("I remember the observatory promise.", result.assistantMessage!!.content)
      assertTrue(result.assistantMessage!!.accepted)
      assertEquals(2, runtimeHelper.resetCalls)
      assertEquals(1, runtimeHelper.runInferenceCalls)

      val compiledEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
      assertEquals(2, compiledEvents.size)
      assertEquals("FULL", compiledEvents[0].payloadJson.parseJsonObject().getAsJsonObject("budget")["mode"].asString)
      assertEquals("AGGRESSIVE", compiledEvents[1].payloadJson.parseJsonObject().getAsJsonObject("budget")["mode"].asString)

      val overflowEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.CONTEXT_OVERFLOW_RECOVERED }
      assertEquals(1, overflowEvents.size)
      assertEquals("reset", overflowEvents[0].payloadJson.parseJsonObject()["stage"].asString)

      val firstCompiledIndex = fixture.conversationRepository.events.indexOfFirst { it == compiledEvents[0] }
      val overflowIndex = fixture.conversationRepository.events.indexOfFirst { it == overflowEvents[0] }
      val secondCompiledIndex = fixture.conversationRepository.events.indexOfFirst { it == compiledEvents[1] }
      assertTrue(firstCompiledIndex in 0 until overflowIndex)
      assertTrue(overflowIndex in 0 until secondCompiledIndex)
    }

  @Test
  fun invoke_recompilesMemoryContextWithAggressiveBudgetAfterInferenceOverflow() =
    runBlocking {
      val runtimeHelper = OverflowOnFirstInferenceRuntimeHelper()
      val fixture = createSendRoleplayFixture(runtimeHelper = runtimeHelper)

      val result =
        fixture.useCase(
          sessionId = fixture.session.id,
          model = fixture.model,
          userInput = "Do you still remember our promise to return to the observatory before dawn?",
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertNotNull(result.assistantMessage)
      assertEquals(MessageStatus.COMPLETED, result.assistantMessage!!.status)
      assertEquals("I remember the observatory promise after retry.", result.assistantMessage!!.content)
      assertTrue(result.assistantMessage!!.accepted)
      assertEquals(2, runtimeHelper.resetCalls)
      assertEquals(2, runtimeHelper.runInferenceCalls)

      val compiledEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }
      assertEquals(2, compiledEvents.size)
      assertEquals("FULL", compiledEvents[0].payloadJson.parseJsonObject().getAsJsonObject("budget")["mode"].asString)
      assertEquals("AGGRESSIVE", compiledEvents[1].payloadJson.parseJsonObject().getAsJsonObject("budget")["mode"].asString)

      val overflowEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.CONTEXT_OVERFLOW_RECOVERED }
      assertEquals(1, overflowEvents.size)
      assertEquals("inference", overflowEvents[0].payloadJson.parseJsonObject()["stage"].asString)

      val firstCompiledIndex = fixture.conversationRepository.events.indexOfFirst { it == compiledEvents[0] }
      val overflowIndex = fixture.conversationRepository.events.indexOfFirst { it == overflowEvents[0] }
      val secondCompiledIndex = fixture.conversationRepository.events.indexOfFirst { it == compiledEvents[1] }
      assertTrue(firstCompiledIndex in 0 until overflowIndex)
      assertTrue(overflowIndex in 0 until secondCompiledIndex)
    }

  @Test
  fun invoke_appliesStyleRepairPromptOnTurnAfterDetectedDrift() =
    runBlocking {
      val runtimeHelper =
        CapturingSequentialRuntimeHelper(
          responses =
            listOf(
              "As an AI assistant, I can help you coordinate the breach.",
              "We move before dawn. Stay low and keep the beacon dark.",
            ),
        )
      val fixture = createSendRoleplayFixture(runtimeHelper = runtimeHelper)

      val firstResult =
        fixture.useCase(
          sessionId = fixture.session.id,
          model = fixture.model,
          userInput = "How do we breach the observatory?",
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertNotNull(firstResult.assistantMessage)
      assertEquals(MessageStatus.COMPLETED, firstResult.assistantMessage!!.status)

      val driftEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.ROLE_DRIFT_DETECTED }
      assertEquals(1, driftEvents.size)
      val driftPayload = driftEvents.single().payloadJson.parseJsonObject()
      assertEquals(firstResult.assistantMessage!!.id, driftPayload["sourceMessageId"].asString)
      assertTrue(driftPayload.getAsJsonArray("signals").map { it.asString }.contains("assistant_meta"))

      val secondResult =
        fixture.useCase(
          sessionId = fixture.session.id,
          model = fixture.model,
          userInput = "Then lead the entry team.",
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertNotNull(secondResult.assistantMessage)
      assertEquals(MessageStatus.COMPLETED, secondResult.assistantMessage!!.status)
      assertEquals("We move before dawn. Stay low and keep the beacon dark.", secondResult.assistantMessage!!.content)
      assertEquals(2, runtimeHelper.resetPrompts.size)
      assertTrue(runtimeHelper.resetPrompts[1].contains("Style repair:"))
      assertTrue(runtimeHelper.resetPrompts[1].contains("Do not mention being an AI"))

      val repairEvents =
        fixture.conversationRepository.events.filter { it.eventType == SessionEventType.ROLE_STYLE_REPAIR_APPLIED }
      assertEquals(1, repairEvents.size)
      val repairPayload = repairEvents.single().payloadJson.parseJsonObject()
      assertEquals(firstResult.assistantMessage!!.id, repairPayload["sourceMessageId"].asString)
      assertTrue(repairPayload["prompt"].asString.contains("Style repair:"))
    }

}

private class OverflowOnFirstResetRuntimeHelper : LlmModelHelper {
  var resetCalls: Int = 0
  var runInferenceCalls: Int = 0

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
    resetCalls += 1
    if (resetCalls == 1) {
      throw IllegalStateException("input tokens exceed context window")
    }
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
    runInferenceCalls += 1
    resultListener("I remember the observatory promise.", false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private class StableRuntimeHelper : LlmModelHelper {
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
  ) = Unit

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
    resultListener("Hold the line.", false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private class CapturingSequentialRuntimeHelper(
  private val responses: List<String>,
) : LlmModelHelper {
  val resetPrompts = mutableListOf<String>()
  var runInferenceCalls: Int = 0

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
    val response = responses.getOrElse(runInferenceCalls) { responses.lastOrNull().orEmpty() }
    runInferenceCalls += 1
    resultListener(response, false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private class OverflowOnFirstInferenceRuntimeHelper : LlmModelHelper {
  var resetCalls: Int = 0
  var runInferenceCalls: Int = 0

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
    resetCalls += 1
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
    runInferenceCalls += 1
    if (runInferenceCalls == 1) {
      onError("input tokens exceed context window")
      return
    }
    resultListener("I remember the observatory promise after retry.", false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private data class SendRoleplayMessageTestFixture(
  val useCase: SendRoleplayMessageUseCase,
  val conversationRepository: SendMessageConversationRepository,
  val roleRepository: SendMessageRoleRepository,
  val session: Session,
  val model: Model,
)

private fun createSendRoleplayFixture(
  runtimeHelper: LlmModelHelper,
): SendRoleplayMessageTestFixture {
  val now = System.currentTimeMillis()
  val session = sendTestSession(turnCount = 8, now = now)
  val role = sendTestRole(memoryMaxItems = 4, now = now)
  val conversationRepository =
    SendMessageConversationRepository(
      session = session,
      initialMessages =
        listOf(
          sendTestMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "We still owe the observatory promise before dawn.",
            now = now,
          ),
        ),
    )
  val runtimeStateRepository =
    SendMessageRuntimeStateRepository(
      snapshot =
        RuntimeStateSnapshot(
          sessionId = session.id,
          sceneJson = """{"location":"Observatory roof","goal":"return before dawn"}""",
          relationshipJson = """{"currentMood":"tense but trusting"}""",
          activeEntitiesJson = """["Astra","Mae","observatory beacon"]""",
          updatedAt = now,
          sourceMessageId = "assistant-2",
        ),
    )
  val openThreadRepository =
    SendMessageOpenThreadRepository(
      listOf(
        sendTestOpenThread(
          id = "thread-promise",
          sessionId = session.id,
          content = "Return to the observatory before dawn.",
          now = now,
        ),
      ),
    )
  val memoryAtomRepository =
    SendMessageMemoryAtomRepository(
      listOf(
        sendTestMemoryAtom(
          id = "atom-promise",
          sessionId = session.id,
          roleId = role.id,
          objectValue = "to return to the observatory before dawn",
          evidenceQuote = "We still owe the observatory promise before dawn.",
          now = now,
        ),
      ),
    )
  val memoryRepository = SendMessageMemoryRepository(emptyList())
  val compactionCacheRepository = SendMessageCompactionCacheRepository()
  val dataStoreRepository = FakeDataStoreRepository(stUserProfile = StUserProfile())
  val roleRepository = SendMessageRoleRepository(role)
  val summarizeSessionUseCase =
    SummarizeSessionUseCase(
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      compactionCacheRepository = compactionCacheRepository,
      tokenEstimator = TokenEstimator(),
    )
  val extractMemoriesUseCase =
    ExtractMemoriesUseCase(
      memoryRepository = memoryRepository,
      memoryAtomRepository = memoryAtomRepository,
      openThreadRepository = openThreadRepository,
      runtimeStateRepository = runtimeStateRepository,
      conversationRepository = conversationRepository,
      validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
    )
  val useCase =
    SendRoleplayMessageUseCase(
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      toolOrchestrator = NoOpRoleplayToolOrchestrator(),
      compileRuntimeRoleProfileUseCase = CompileRuntimeRoleProfileUseCase(TokenEstimator()),
      promptAssembler = PromptAssembler(TokenEstimator()),
      compileRoleplayMemoryContextUseCase =
        CompileRoleplayMemoryContextUseCase(
          conversationRepository = conversationRepository,
          runtimeStateRepository = runtimeStateRepository,
          openThreadRepository = openThreadRepository,
          memoryAtomRepository = memoryAtomRepository,
          memoryRepository = memoryRepository,
          compactionCacheRepository = compactionCacheRepository,
          tokenEstimator = TokenEstimator(),
        ),
      summarizeSessionUseCase = summarizeSessionUseCase,
      extractMemoriesUseCase = extractMemoriesUseCase,
    )
  useCase.runtimeHelperResolver = { runtimeHelper }
  val model =
    Model(
      name = "test-model",
      downloadFileName = "test-model.bin",
      llmMaxToken = 4096,
    ).apply {
      instance = Any()
    }
  return SendRoleplayMessageTestFixture(
    useCase = useCase,
    conversationRepository = conversationRepository,
    roleRepository = roleRepository,
    session = session,
    model = model,
  )
}

private class SendMessageCompactionCacheRepository : CompactionCacheRepository {
  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> = emptyList()

  override suspend fun upsert(entry: CompactionCacheEntry) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit
}

private class SendMessageConversationRepository(
  session: Session,
  initialMessages: List<Message>,
) : ConversationRepository {
  private val sessions = MutableStateFlow(listOf(session))
  val messages = MutableStateFlow(initialMessages.toList())
  val events = mutableListOf<SessionEvent>()
  private var summary: SessionSummary? = null

  override fun observeSessions(): Flow<List<Session>> = sessions

  override fun observeMessages(sessionId: String): Flow<List<Message>> =
    flowOf(messages.value.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> =
    messages.value.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.value.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? =
    messages.value.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? =
    sessions.value.firstOrNull { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) {
    sessions.value = sessions.value.map { current -> if (current.id == session.id) session else current }
  }

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages.value = messages.value + message
  }

  override suspend fun updateMessage(message: Message) {
    messages.value = messages.value.map { current -> if (current.id == message.id) message else current }
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    val acceptedMessage =
      messages.value.firstOrNull { it.id == messageId }?.copy(
        accepted = true,
        isCanonical = true,
        updatedAt = acceptedAt,
      ) ?: return null
    updateMessage(acceptedMessage)
    return acceptedMessage
  }

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

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    this.messages.value = this.messages.value.filterNot { it.sessionId == sessionId } + messages
  }

  override suspend fun nextMessageSeq(sessionId: String): Int =
    listMessages(sessionId).size + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? =
    summary?.takeIf { it.sessionId == sessionId }

  override suspend fun upsertSummary(summary: SessionSummary) {
    this.summary = summary
  }

  override suspend fun deleteSummary(sessionId: String) {
    summary = null
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> =
    events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class SendMessageRoleRepository(
  role: RoleCard,
) : RoleRepository {
  private var currentRole: RoleCard = role
  val savedRoles = mutableListOf<RoleCard>()

  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(currentRole))

  override suspend fun getRole(roleId: String): RoleCard? = currentRole.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) {
    currentRole = role
    savedRoles += role
  }

  override suspend fun deleteRole(roleId: String) = Unit
}

private class SendMessageRuntimeStateRepository(
  private var snapshot: RuntimeStateSnapshot?,
) : RuntimeStateRepository {
  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? =
    snapshot?.takeIf { it.sessionId == sessionId }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun deleteBySession(sessionId: String) {
    snapshot = snapshot?.takeUnless { it.sessionId == sessionId }
  }
}

private class SendMessageOpenThreadRepository(
  threads: List<OpenThread>,
) : OpenThreadRepository {
  private val storedThreads = threads.toMutableList()

  override suspend fun listBySession(sessionId: String): List<OpenThread> =
    storedThreads.filter { it.sessionId == sessionId }

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> =
    storedThreads.filter { it.sessionId == sessionId && it.status == status }

  override suspend fun upsert(thread: OpenThread) {
    storedThreads.removeAll { it.id == thread.id }
    storedThreads += thread
  }

  override suspend fun deleteBySession(sessionId: String) {
    storedThreads.removeAll { it.sessionId == sessionId }
  }

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) {
    storedThreads.replaceAll { thread ->
      if (thread.id != threadId) {
        thread
      } else {
        thread.copy(
          status = status,
          resolvedByMessageId = resolvedByMessageId,
          updatedAt = updatedAt,
        )
      }
    }
  }
}

private class SendMessageMemoryAtomRepository(
  atoms: List<MemoryAtom>,
) : MemoryAtomRepository {
  private val storedAtoms = atoms.toMutableList()

  override suspend fun listBySession(sessionId: String): List<MemoryAtom> =
    storedAtoms.filter { it.sessionId == sessionId }

  override suspend fun upsert(atom: MemoryAtom) {
    storedAtoms.removeAll { it.id == atom.id }
    storedAtoms += atom
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    storedAtoms.replaceAll { atom ->
      if (atom.id !in memoryIds) {
        atom
      } else {
        atom.copy(lastUsedAt = usedAt, updatedAt = usedAt)
      }
    }
  }

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    storedAtoms.replaceAll { atom ->
      if (atom.id != memoryId) {
        atom
      } else {
        atom.copy(tombstone = true, updatedAt = updatedAt)
      }
    }
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) {
    storedAtoms.replaceAll { atom ->
      if (atom.sessionId != sessionId) {
        atom
      } else {
        atom.copy(tombstone = true, updatedAt = updatedAt)
      }
    }
  }

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> =
    storedAtoms
      .filter { it.sessionId == sessionId && it.roleId == roleId && !it.tombstone }
      .take(limit)
}

private class SendMessageMemoryRepository(
  memories: List<MemoryItem>,
) : MemoryRepository {
  private val storedMemories = memories.toMutableList()

  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> =
    storedMemories.filter { it.roleId == roleId && it.sessionId == null }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> =
    storedMemories.filter { it.sessionId == sessionId }

  override suspend fun upsert(memory: MemoryItem) {
    storedMemories.removeAll { it.id == memory.id }
    storedMemories += memory
  }

  override suspend fun deactivate(memoryId: String) {
    storedMemories.replaceAll { memory ->
      if (memory.id != memoryId) {
        memory
      } else {
        memory.copy(active = false)
      }
    }
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    storedMemories.replaceAll { memory ->
      if (memory.id !in memoryIds) {
        memory
      } else {
        memory.copy(lastUsedAt = usedAt, updatedAt = usedAt)
      }
    }
  }

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> =
    storedMemories
      .filter { it.roleId == roleId && it.active && (sessionId == null || it.sessionId == sessionId) }
      .take(limit)
}

private fun sendTestSession(turnCount: Int, now: Long): Session =
  Session(
    id = "session-1",
    roleId = "role-1",
    title = "Observatory Run",
    activeModelId = "gemma-3n",
    createdAt = now,
    updatedAt = now,
    lastMessageAt = now,
    turnCount = turnCount,
  )

private fun sendTestRole(memoryMaxItems: Int, now: Long): RoleCard =
  RoleCard(
    id = "role-1",
    name = "Captain Astra",
    summary = "A disciplined starship captain.",
    systemPrompt = "Stay focused on continuity and tactical realism.",
    memoryEnabled = true,
    memoryMaxItems = memoryMaxItems,
    createdAt = now,
    updatedAt = now,
  )

private fun sendTestMessage(
  id: String,
  sessionId: String,
  seq: Int,
  side: MessageSide,
  content: String,
  now: Long,
): Message =
  Message(
    id = id,
    sessionId = sessionId,
    seq = seq,
    side = side,
    status = MessageStatus.COMPLETED,
    content = content,
    createdAt = now,
    updatedAt = now,
  )

private fun sendTestOpenThread(
  id: String,
  sessionId: String,
  content: String,
  now: Long,
): OpenThread =
  OpenThread(
    id = id,
    sessionId = sessionId,
    type = OpenThreadType.PROMISE,
    content = content,
    owner = OpenThreadOwner.SHARED,
    priority = 10,
    status = OpenThreadStatus.OPEN,
    sourceMessageIds = listOf("assistant-2"),
    createdAt = now,
    updatedAt = now,
  )

private fun sendTestMemoryAtom(
  id: String,
  sessionId: String,
  roleId: String,
  objectValue: String,
  evidenceQuote: String,
  now: Long,
): MemoryAtom =
  MemoryAtom(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = MemoryPlane.CANON,
    namespace = MemoryNamespace.PROMISE,
    subject = "Captain Astra",
    predicate = "promised",
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

private fun String.parseJsonObject(): JsonObject = JsonParser.parseString(this).asJsonObject
