package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.runtime.LlmModelHelper
import selfgemma.talk.testing.FakeDataStoreRepository

class RunRoleplayTurnUseCaseTest {
  @Test
  fun runPrepared_delegatesToSendRoleplayMessageUseCaseWhenNoToolsRun() =
    runBlocking {
      val fixture = createFixture()
      val pendingMessage = fixture.enqueuePendingTurn("Hold formation.")

      val result =
        fixture.runRoleplayTurnUseCase.runPrepared(
          pendingMessage = pendingMessage,
          model = fixture.model,
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertEquals(MessageStatus.COMPLETED, result.assistantMessage?.status)
      assertEquals("Hold formation.", result.assistantMessage?.content)
      assertTrue(result.toolInvocations.isEmpty())
      assertTrue(fixture.toolInvocationRepository.invocations.isEmpty())
      assertTrue(
        fixture.conversationRepository.events.none { event ->
          event.eventType == SessionEventType.TOOL_CALL_COMPLETED ||
            event.eventType == SessionEventType.TOOL_RESULT_APPLIED
        }
      )
    }

  @Test
  fun runPrepared_persistsToolTraceBeforeFallingBackToNormalReplyGeneration() =
    runBlocking {
      val fixture =
        createFixture(
          toolOrchestrator =
            RecordingToolOrchestrator(
              toolInvocations =
                listOf(
                  ToolInvocation(
                    id = "tool-1",
                    sessionId = "session-1",
                    turnId = "assistant-seed-2",
                    toolName = "search_wiki",
                    source = ToolExecutionSource.JS_SKILL,
                    status = ToolInvocationStatus.SUCCEEDED,
                    stepIndex = 0,
                    argsJson = """{"topic":"observatory"}""",
                    resultJson = """{"summary":"Observatory summary"}""",
                    resultSummary = "Observatory summary",
                    startedAt = 10L,
                    finishedAt = 20L,
                  ),
                )
            ),
        )
      val pendingMessage = fixture.enqueuePendingTurn("What do you know about the observatory?")

      val result =
        fixture.runRoleplayTurnUseCase.runPrepared(
          pendingMessage = pendingMessage,
          model = fixture.model,
          enableStreamingOutput = false,
          isStopRequested = { false },
        )

      assertEquals(MessageStatus.COMPLETED, result.assistantMessage?.status)
      assertEquals(1, result.toolInvocations.size)
      assertEquals(1, fixture.toolInvocationRepository.invocations.size)
      assertEquals("search_wiki", fixture.toolInvocationRepository.invocations.single().toolName)
      assertTrue(
        fixture.conversationRepository.events.any { event ->
          event.eventType == SessionEventType.TOOL_CALL_STARTED &&
            event.payloadJson.contains("\"toolName\":\"search_wiki\"")
        }
      )
      assertTrue(
        fixture.conversationRepository.events.any { event ->
          event.eventType == SessionEventType.TOOL_CALL_COMPLETED &&
            event.payloadJson.contains("\"toolName\":\"search_wiki\"")
        }
      )
      assertTrue(
        fixture.conversationRepository.events.any { event ->
          event.eventType == SessionEventType.TOOL_RESULT_APPLIED &&
            event.payloadJson.contains("\"invocationCount\":1")
        }
      )
    }
}

private data class RunRoleplayTurnFixture(
  val session: Session,
  val model: Model,
  val sendRoleplayMessageUseCase: SendRoleplayMessageUseCase,
  val runRoleplayTurnUseCase: RunRoleplayTurnUseCase,
  val conversationRepository: TurnConversationRepository,
  val toolInvocationRepository: RecordingToolInvocationRepository,
) {
  suspend fun enqueuePendingTurn(userInput: String): PendingRoleplayMessage {
    val now = System.currentTimeMillis()
    val userMessage =
      Message(
        id = "user-1",
        sessionId = session.id,
        seq = 1,
        side = MessageSide.USER,
        status = MessageStatus.COMPLETED,
        content = userInput,
        createdAt = now,
        updatedAt = now,
      )
    val assistantSeed =
      Message(
        id = "assistant-seed-2",
        sessionId = session.id,
        seq = 2,
        side = MessageSide.ASSISTANT,
        status = MessageStatus.STREAMING,
        accepted = false,
        isCanonical = false,
        content = "",
        parentMessageId = userMessage.id,
        regenerateGroupId = userMessage.id,
        createdAt = now,
        updatedAt = now,
      )
    return checkNotNull(
      sendRoleplayMessageUseCase.enqueuePendingMessage(
        sessionId = session.id,
        stagedTurn =
          StagedRoleplayTurn(
            userMessages = listOf(userMessage),
            assistantMessage = assistantSeed,
            combinedUserInput = userInput,
          ),
      )
    )
  }
}

private fun createFixture(
  toolOrchestrator: RoleplayToolOrchestrator = NoOpRoleplayToolOrchestrator(),
): RunRoleplayTurnFixture {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Observatory",
      activeModelId = "test-model",
      createdAt = now,
      updatedAt = now,
      lastMessageAt = now,
      sessionUserProfile = StUserProfile(),
    )
  val role =
    RoleCard(
      id = "role-1",
      name = "Captain Astra",
      summary = "A disciplined captain.",
      systemPrompt = "Stay in character.",
      createdAt = now,
      updatedAt = now,
    )
  val model =
    Model(
      name = "test-model",
      downloadFileName = "test-model.tflite",
    ).apply {
      instance = Any()
    }
  val conversationRepository = TurnConversationRepository(session = session)
  val roleRepository = TurnRoleRepository(role = role)
  val memoryRepository = TurnMemoryRepository()
  val runtimeStateRepository = TurnRuntimeStateRepository()
  val openThreadRepository = TurnOpenThreadRepository()
  val memoryAtomRepository = TurnMemoryAtomRepository()
  val compactionCacheRepository = TurnCompactionCacheRepository()
  val dataStoreRepository = FakeDataStoreRepository()
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
    ).also { useCase ->
      useCase.runtimeHelperResolver = { ImmediateRuntimeHelper() }
    }
  val toolInvocationRepository = RecordingToolInvocationRepository()
  val runRoleplayTurnUseCase =
    RunRoleplayTurnUseCase(
      sendRoleplayMessageUseCase = sendRoleplayMessageUseCase,
      toolOrchestrator = toolOrchestrator,
      toolInvocationRepository = toolInvocationRepository,
      conversationRepository = conversationRepository,
    )
  return RunRoleplayTurnFixture(
    session = session,
    model = model,
    sendRoleplayMessageUseCase = sendRoleplayMessageUseCase,
    runRoleplayTurnUseCase = runRoleplayTurnUseCase,
    conversationRepository = conversationRepository,
    toolInvocationRepository = toolInvocationRepository,
  )
}

private class RecordingToolOrchestrator(
  private val toolInvocations: List<ToolInvocation>,
) : RoleplayToolOrchestrator {
  override suspend fun execute(request: RoleplayToolExecutionRequest): RoleplayToolExecutionResult {
    return RoleplayToolExecutionResult(toolInvocations = toolInvocations)
  }
}

private class RecordingToolInvocationRepository : ToolInvocationRepository {
  val invocations = mutableListOf<ToolInvocation>()

  override fun observeBySession(sessionId: String): Flow<List<ToolInvocation>> {
    return flowOf(invocations.filter { it.sessionId == sessionId })
  }

  override suspend fun listBySession(sessionId: String): List<ToolInvocation> {
    return invocations.filter { it.sessionId == sessionId }
  }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation> {
    return invocations.filter { it.sessionId == sessionId && it.turnId == turnId }
  }

  override suspend fun upsert(invocation: ToolInvocation) {
    invocations.removeAll { it.id == invocation.id }
    invocations += invocation
  }
}

private class ImmediateRuntimeHelper : LlmModelHelper {
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
    resultListener("Hold formation.", false, null)
    resultListener("", true, null)
  }

  override fun stopResponse(model: Model) = Unit
}

private class TurnConversationRepository(
  private val session: Session,
) : ConversationRepository {
  private val sessions = MutableStateFlow(listOf(session))
  private val messages = mutableListOf<Message>()
  val events = mutableListOf<SessionEvent>()
  private var summary: SessionSummary? = null

  override fun observeSessions(): Flow<List<Session>> = sessions

  override fun observeMessages(sessionId: String): Flow<List<Message>> = flowOf(messages.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> = messages.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) = Unit

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages += message
  }

  override suspend fun updateMessage(message: Message) {
    val index = messages.indexOfFirst { it.id == message.id }
    if (index >= 0) {
      messages[index] = message
    } else {
      messages += message
    }
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    val message = getMessage(messageId) ?: return null
    val acceptedMessage =
      message.copy(
        accepted = true,
        isCanonical = true,
        updatedAt = acceptedAt,
      )
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

private class TurnRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class TurnMemoryRepository : MemoryRepository {
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

private class TurnRuntimeStateRepository : RuntimeStateRepository {
  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? = null

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit
}

private class TurnOpenThreadRepository : OpenThreadRepository {
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

private class TurnMemoryAtomRepository : MemoryAtomRepository {
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

private class TurnCompactionCacheRepository : CompactionCacheRepository {
  override suspend fun listBySession(sessionId: String) =
    emptyList<selfgemma.talk.domain.roleplay.model.CompactionCacheEntry>()

  override suspend fun upsert(entry: selfgemma.talk.domain.roleplay.model.CompactionCacheEntry) = Unit

  override suspend fun deleteBySession(sessionId: String) = Unit
}
