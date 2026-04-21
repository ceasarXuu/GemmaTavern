package selfgemma.talk.feature.roleplay.chat

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.domain.roleplay.usecase.CompileRoleplayMemoryContextUseCase
import selfgemma.talk.domain.roleplay.usecase.CompileRuntimeRoleProfileUseCase
import selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCase
import selfgemma.talk.domain.roleplay.usecase.NoOpRoleplayToolOrchestrator
import selfgemma.talk.domain.roleplay.usecase.PrepareRoleplayEditUseCase
import selfgemma.talk.domain.roleplay.usecase.PrepareRoleplayRegenerationUseCase
import selfgemma.talk.domain.roleplay.usecase.PromptAssembler
import selfgemma.talk.domain.roleplay.usecase.RebuildRoleplayContinuityUseCase
import selfgemma.talk.domain.roleplay.usecase.RollbackRoleplayContinuityUseCase
import selfgemma.talk.domain.roleplay.usecase.RunRoleplayTurnUseCase
import selfgemma.talk.domain.roleplay.usecase.SendRoleplayMessageUseCase
import selfgemma.talk.domain.roleplay.usecase.SummarizeSessionUseCase
import selfgemma.talk.domain.roleplay.usecase.TokenEstimator
import selfgemma.talk.domain.roleplay.usecase.ValidateMemoryAtomCandidateUseCase
import selfgemma.talk.testing.FakeDataStoreRepository
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatSide
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RoleplayChatViewModelTest {
  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun rollbackToMessage_blocksWhenGenerationIsInProgress() =
    runTest {
      val fixture = createFixture()
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()
      fixture.setMetaState(inProgress = true)

      fixture.viewModel.rollbackToMessage("assistant-2")
      advanceUntilIdle()

      assertEquals(
        "Wait for queued or in-progress turns to finish before rewinding.",
        fixture.viewModel.uiState.value.errorMessage,
      )
      assertTrue(fixture.conversationRepository.events.isEmpty())
      assertEquals(2, fixture.conversationRepository.messages.value.size)
      uiCollector.cancel()
    }

  @Test
  fun editMessageFromHere_blocksWhenPendingMessagesExist() =
    runTest {
      val fixture = createFixture()
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()
      fixture.setMetaState(
        pendingMessages =
          listOf(
            fixture.queuedUserMessage(
              testMessage(
                id = "queued-user",
                sessionId = fixture.session.id,
                seq = 3,
                side = MessageSide.USER,
                content = "queued draft",
              ),
            ),
          ),
      )

      fixture.viewModel.editMessageFromHere("user-1")
      advanceUntilIdle()

      assertEquals(
        "Wait for queued or in-progress turns to finish before editing.",
        fixture.viewModel.uiState.value.errorMessage,
      )
      assertEquals("", fixture.viewModel.uiState.value.draft)
      assertTrue(fixture.conversationRepository.events.isEmpty())
      uiCollector.cancel()
    }

  @Test
  fun regenerateAssistantMessage_blocksWhenPendingMessagesExist() =
    runTest {
      val fixture = createFixture()
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()
      fixture.setMetaState(
        pendingMessages =
          listOf(
            fixture.queuedUserMessage(
              testMessage(
                id = "queued-user",
                sessionId = fixture.session.id,
                seq = 3,
                side = MessageSide.USER,
                content = "queued draft",
              ),
            ),
          ),
      )

      fixture.viewModel.regenerateAssistantMessage("assistant-2", testModel())
      advanceUntilIdle()

      assertEquals(
        "Wait for queued or in-progress turns to finish before regenerating.",
        fixture.viewModel.uiState.value.errorMessage,
      )
      assertFalse(fixture.viewModel.uiState.value.inProgress)
      assertTrue(fixture.conversationRepository.events.isEmpty())
      assertEquals(2, fixture.conversationRepository.messages.value.size)
      uiCollector.cancel()
    }

  @Test
  fun sendMessage_queuesInterruptMergeWhileAssistantReplyIsInProgress() =
    runTest {
      val fixture = createFixture()
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()
      fixture.setMetaState(inProgress = true)

      fixture.setDraft("Interrupt and merge this turn.")
      fixture.viewModel.sendMessage(testModel())
      advanceUntilIdle()

      assertTrue(fixture.viewModel.uiState.value.inProgress)
      assertTrue(fixture.viewModel.uiState.value.hasPendingSends)
      assertEquals("", fixture.viewModel.uiState.value.draft)
      assertTrue(
        fixture.viewModel.uiState.value.messages.any { message ->
          message.side == MessageSide.USER && message.content == "Interrupt and merge this turn."
        }
      )
      assertTrue(fixture.isStopRequested())
      assertNull(fixture.viewModel.uiState.value.errorMessage)
      uiCollector.cancel()
    }

  @Test
  fun sendChatMessages_queuesInterruptMergeForAudioAttachmentWhileAssistantReplyIsInProgress() =
    runTest {
      val fixture = createFixture(appContext = TestAppContext())
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()
      fixture.setMetaState(inProgress = true)

      fixture.viewModel.sendChatMessages(
        model = testModel(),
        messages =
          listOf(
            ChatMessageAudioClip(
              audioData = byteArrayOf(1, 2, 3, 4),
              sampleRate = 16_000,
              side = ChatSide.USER,
            ),
          ),
      )
      advanceUntilIdle()

      assertTrue(fixture.viewModel.uiState.value.inProgress)
      assertTrue(fixture.viewModel.uiState.value.hasPendingSends)
      assertTrue(fixture.isStopRequested())
      assertNull(fixture.viewModel.uiState.value.errorMessage)
      assertTrue(
        fixture.viewModel.uiState.value.messages.any { message ->
          message.side == MessageSide.USER &&
            message.kind == MessageKind.AUDIO &&
            message.content == "Shared an audio clip."
        }
      )
      uiCollector.cancel()
    }

  @Test
  fun uiState_updatesWhenToolInvocationsArrive() =
    runTest {
      val fixture = createFixture()
      val uiCollector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()

      fixture.toolInvocationRepository.upsert(
        ToolInvocation(
          id = "tool-1",
          sessionId = fixture.session.id,
          turnId = "assistant-2",
          toolName = "search_weather",
          source = ToolExecutionSource.NATIVE,
          status = ToolInvocationStatus.SUCCEEDED,
          stepIndex = 0,
          startedAt = 10L,
          finishedAt = 20L,
        )
      )
      advanceUntilIdle()

      assertEquals(1, fixture.viewModel.uiState.value.toolInvocations.size)
      assertEquals("search_weather", fixture.viewModel.uiState.value.toolInvocations.single().toolName)
      uiCollector.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
  private val dispatcher = StandardTestDispatcher()

  override fun starting(description: Description) {
    Dispatchers.setMain(dispatcher)
  }

  override fun finished(description: Description) {
    Dispatchers.resetMain()
  }
}

private data class RoleplayChatViewModelFixture(
  val viewModel: RoleplayChatViewModel,
  val conversationRepository: ViewModelConversationRepository,
  val toolInvocationRepository: ViewModelToolInvocationRepository,
  val session: Session,
) {
  fun queuedUserMessage(message: Message): Any {
    val clazz = Class.forName("selfgemma.talk.feature.roleplay.chat.QueuedUserMessage")
    val constructor = clazz.getDeclaredConstructor(Message::class.java, Boolean::class.javaPrimitiveType)
    constructor.isAccessible = true
    return constructor.newInstance(message, false)
  }

  fun setMetaState(
    pendingMessages: List<Any> = emptyList(),
    inProgress: Boolean = false,
    errorMessage: String? = null,
  ) {
    val metaStateField = RoleplayChatViewModel::class.java.getDeclaredField("metaState")
    metaStateField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val metaStateFlow = metaStateField.get(viewModel) as MutableStateFlow<Any>
    val metaStateClass = Class.forName("selfgemma.talk.feature.roleplay.chat.RoleplayChatMetaState")
    val constructor =
      metaStateClass.getDeclaredConstructor(
        SessionSummary::class.java,
        List::class.java,
        RoleplayContinuityDebugState::class.java,
        List::class.java,
        Boolean::class.javaPrimitiveType,
        String::class.java,
      )
    constructor.isAccessible = true
    metaStateFlow.value =
      constructor.newInstance(
        null,
        emptyList<MemoryItem>(),
        RoleplayContinuityDebugState(),
        pendingMessages,
        inProgress,
        errorMessage,
      )
  }

  fun setDraft(value: String) {
    val draftField = RoleplayChatViewModel::class.java.getDeclaredField("draft")
    draftField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val draftFlow = draftField.get(viewModel) as MutableStateFlow<String>
    draftFlow.value = value
  }

  fun isStopRequested(): Boolean {
    val stopRequestedField = RoleplayChatViewModel::class.java.getDeclaredField("stopRequested")
    stopRequestedField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stopRequestedFlow = stopRequestedField.get(viewModel) as MutableStateFlow<Boolean>
    return stopRequestedFlow.value
  }
}

private class TestAppContext :
  ContextWrapper(null) {
  private val filesDirectory: File =
    Files.createTempDirectory("roleplay-chat-viewmodel-test").toFile().apply { mkdirs() }

  override fun getFilesDir(): File = filesDirectory
}

private fun createFixture(appContext: ContextWrapper = ContextWrapper(null)): RoleplayChatViewModelFixture {
  val now = System.currentTimeMillis()
  val session = testSession(now)
  val role = testRole(now)
  val conversationRepository =
    ViewModelConversationRepository(
      session = session,
      initialMessages =
        listOf(
          testMessage(
            id = "user-1",
            sessionId = session.id,
            seq = 1,
            side = MessageSide.USER,
            content = "Open the route.",
          ),
          testMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "I will open the route.",
          ),
        ),
    )
  val roleRepository = ViewModelRoleRepository(role)
  val memoryRepository = ViewModelMemoryRepository()
  val runtimeStateRepository = ViewModelRuntimeStateRepository()
  val openThreadRepository = ViewModelOpenThreadRepository()
  val memoryAtomRepository = ViewModelMemoryAtomRepository()
  val compactionCacheRepository = ViewModelCompactionCacheRepository()
  val dataStoreRepository =
    FakeDataStoreRepository(
      stUserProfile =
        StUserProfile(
          personas = mapOf("captain" to "Captain Mae"),
        ).snapshotSelectedPersona("captain"),
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
  val summarizeSessionUseCase =
    SummarizeSessionUseCase(
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      compactionCacheRepository = compactionCacheRepository,
      tokenEstimator = TokenEstimator(),
    )
  val rebuildUseCase =
    RebuildRoleplayContinuityUseCase(
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      runtimeStateRepository = runtimeStateRepository,
      memoryAtomRepository = memoryAtomRepository,
      openThreadRepository = openThreadRepository,
      compactionCacheRepository = compactionCacheRepository,
      extractMemoriesUseCase = extractMemoriesUseCase,
      summarizeSessionUseCase = summarizeSessionUseCase,
    )
  val rollbackUseCase =
    RollbackRoleplayContinuityUseCase(
      conversationRepository = conversationRepository,
      rebuildRoleplayContinuityUseCase = rebuildUseCase,
    )
  val sendRoleplayMessageUseCase =
    SendRoleplayMessageUseCase(
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
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
  val toolInvocationRepository = ViewModelToolInvocationRepository()
  val runRoleplayTurnUseCase =
    RunRoleplayTurnUseCase(
      sendRoleplayMessageUseCase = sendRoleplayMessageUseCase,
      toolOrchestrator = NoOpRoleplayToolOrchestrator(),
      toolInvocationRepository = toolInvocationRepository,
      conversationRepository = conversationRepository,
    )

  val viewModel =
    RoleplayChatViewModel(
      savedStateHandle = SavedStateHandle(mapOf("sessionId" to session.id)),
      appContext = appContext,
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      memoryRepository = memoryRepository,
      runtimeStateRepository = runtimeStateRepository,
      openThreadRepository = openThreadRepository,
      memoryAtomRepository = memoryAtomRepository,
      compactionCacheRepository = compactionCacheRepository,
      toolInvocationRepository = toolInvocationRepository,
      runRoleplayTurnUseCase = runRoleplayTurnUseCase,
      extractMemoriesUseCase = extractMemoriesUseCase,
      rollbackRoleplayContinuityUseCase = rollbackUseCase,
      prepareRoleplayEditUseCase =
        PrepareRoleplayEditUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase = rebuildUseCase,
        ),
      prepareRoleplayRegenerationUseCase =
        PrepareRoleplayRegenerationUseCase(
          conversationRepository = conversationRepository,
          rollbackRoleplayContinuityUseCase = rollbackUseCase,
          sendRoleplayMessageUseCase = sendRoleplayMessageUseCase,
        ),
    )
  viewModel.elapsedRealtimeProvider = { 1_000L }
  viewModel.ioDispatcher = Dispatchers.Main
  viewModel.defaultDispatcher = Dispatchers.Main

  return RoleplayChatViewModelFixture(
    viewModel = viewModel,
    conversationRepository = conversationRepository,
    toolInvocationRepository = toolInvocationRepository,
    session = session,
  )
}

private class ViewModelConversationRepository(
  private val session: Session,
  initialMessages: List<Message>,
) : ConversationRepository {
  private val sessions = MutableStateFlow(listOf(session))
  val messages = MutableStateFlow(initialMessages.toList())
  val events = mutableListOf<SessionEvent>()
  private var summary: SessionSummary? = null

  override fun observeSessions(): Flow<List<Session>> = sessions

  override fun observeMessages(sessionId: String): Flow<List<Message>> = flowOf(messages.value.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> = messages.value.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.value.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.value.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) = Unit

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages.value = messages.value + message
  }

  override suspend fun updateMessage(message: Message) {
    messages.value = messages.value.map { current -> if (current.id == message.id) message else current }
  }

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

  override suspend fun nextMessageSeq(sessionId: String): Int = listMessages(sessionId).size + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = summary

  override suspend fun upsertSummary(summary: SessionSummary) {
    this.summary = summary
  }

  override suspend fun deleteSummary(sessionId: String) {
    summary = null
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class ViewModelRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class ViewModelMemoryRepository : MemoryRepository {
  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> = emptyList()

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> = emptyList()

  override suspend fun upsert(memory: MemoryItem) = Unit

  override suspend fun deactivate(memoryId: String) = Unit

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) = Unit

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> = emptyList()
}

private class ViewModelRuntimeStateRepository : RuntimeStateRepository {
  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? = null

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit
}

private class ViewModelOpenThreadRepository : OpenThreadRepository {
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

private class ViewModelMemoryAtomRepository : MemoryAtomRepository {
  override suspend fun listBySession(sessionId: String): List<MemoryAtom> = emptyList()

  override suspend fun upsert(atom: MemoryAtom) = Unit

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) = Unit

  override suspend fun tombstone(memoryId: String, updatedAt: Long) = Unit

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) = Unit

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> = emptyList()
}

private class ViewModelCompactionCacheRepository : CompactionCacheRepository {
  override suspend fun listBySession(sessionId: String) = emptyList<selfgemma.talk.domain.roleplay.model.CompactionCacheEntry>()

  override suspend fun upsert(entry: selfgemma.talk.domain.roleplay.model.CompactionCacheEntry) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit
}

private class ViewModelToolInvocationRepository : ToolInvocationRepository {
  private val invocations = MutableStateFlow<List<ToolInvocation>>(emptyList())

  override fun observeBySession(sessionId: String): Flow<List<ToolInvocation>> {
    return invocations.map { currentInvocations ->
      currentInvocations.filter { it.sessionId == sessionId }
    }
  }

  override suspend fun listBySession(sessionId: String): List<ToolInvocation> =
    invocations.value.filter { it.sessionId == sessionId }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation> =
    invocations.value.filter { it.sessionId == sessionId && it.turnId == turnId }

  override suspend fun upsert(invocation: ToolInvocation) {
    invocations.value =
      invocations.value
        .filterNot { current -> current.id == invocation.id }
        .plus(invocation)
        .sortedWith(compareBy<ToolInvocation>({ it.startedAt }, { it.stepIndex }, { it.id }))
  }
}

private fun testSession(now: Long): Session =
  Session(
    id = "session-1",
    roleId = "role-1",
    title = "Bridge",
    activeModelId = "gemma-3n",
    createdAt = now,
    updatedAt = now,
    lastMessageAt = now,
    sessionUserProfile =
      StUserProfile(
        personas = mapOf("captain" to "Captain Mae"),
      ).snapshotSelectedPersona("captain"),
  )

private fun testRole(now: Long): RoleCard =
  RoleCard(
    id = "role-1",
    name = "Captain Astra",
    summary = "A disciplined starship captain.",
    systemPrompt = "Stay focused.",
    createdAt = now,
    updatedAt = now,
  )

private fun testMessage(
  id: String,
  sessionId: String,
  seq: Int,
  side: MessageSide,
  content: String,
): Message {
  val now = System.currentTimeMillis()
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

private fun testModel(): Model = Model(name = "test-model", downloadFileName = "test-model.tflite")
