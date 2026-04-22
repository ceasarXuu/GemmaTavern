package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.model.RoleplayDebugStoredFile
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayDebugExportRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class ExportRoleplayDebugBundleFromSessionUseCaseTest {
  @Test
  fun exportFromSession_writesBundlePointerAndAuditEvent() =
    runBlocking {
      val fixture = createFixture()

      val result =
        fixture.useCase.exportFromSession(
          sessionId = fixture.session.id,
          origin = RoleplayDebugExportOrigin.CHAT_SCREEN,
        )

      assertEquals(fixture.session.id, result.sessionId)
      assertEquals("Bridge", result.sessionTitle)
      assertEquals("Captain Astra", result.roleName)
      assertEquals(2, result.messageCount)
      assertEquals(1, result.toolInvocationCount)
      assertEquals(0, result.externalFactCount)
      assertTrue(result.bundleFile.fileName.startsWith("roleplay-debug-session-1-"))
      assertEquals("latest-debug-export.json", result.pointerFile.fileName)
      assertNotNull(fixture.exportRepository.lastBundleJson)
      assertTrue(
        fixture.exportRepository.lastBundleJson.orEmpty().contains(
          "\"schemaVersion\": \"roleplay_debug_bundle_v2\"",
        )
      )
      assertTrue(
        fixture.exportRepository.lastBundleJson.orEmpty().contains("\"toolName\": \"getDeviceSystemTime\"")
      )
      assertTrue(
        fixture.exportRepository.lastBundleJson.orEmpty().contains("\"eventType\": \"MEMORY_PACK_COMPILED\"")
      )
      assertTrue(
        fixture.exportRepository.lastPointerJson.orEmpty().contains("\"sessionId\": \"session-1\"")
      )
      assertTrue(
        fixture.exportRepository.lastPointerJson.orEmpty().contains("\"externalFactCount\": 0")
      )
      assertTrue(
        fixture.conversationRepository.events.any { event ->
          event.eventType == SessionEventType.EXPORT &&
            event.payloadJson.contains("chat_screen")
        }
      )
    }
}

private data class ExportDebugBundleFixture(
  val session: Session,
  val conversationRepository: ExportDebugConversationRepository,
  val exportRepository: RecordingDebugExportRepository,
  val useCase: ExportRoleplayDebugBundleFromSessionUseCase,
)

private fun createFixture(): ExportDebugBundleFixture {
  val now = System.currentTimeMillis()
  val session =
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
  val role =
    RoleCard(
      id = "role-1",
      name = "Captain Astra",
      summary = "A disciplined starship captain.",
      systemPrompt = "Stay focused.",
      createdAt = now,
      updatedAt = now,
    )
  val conversationRepository =
    ExportDebugConversationRepository(
      session = session,
      role = role,
      messages =
        listOf(
          testMessage(
            id = "user-1",
            sessionId = session.id,
            seq = 1,
            side = MessageSide.USER,
            content = "What time is it?",
          ),
          testMessage(
            id = "assistant-2",
            sessionId = session.id,
            seq = 2,
            side = MessageSide.ASSISTANT,
            content = "Checking the device clock now.",
          ),
        ),
    )
  val exportRepository = RecordingDebugExportRepository()
  val useCase =
    ExportRoleplayDebugBundleFromSessionUseCase(
      dataStoreRepository =
        FakeDataStoreRepository(
          stUserProfile =
            StUserProfile(
              personas = mapOf("captain" to "Captain Mae"),
            ).snapshotSelectedPersona("captain"),
        ),
      conversationRepository = conversationRepository,
      roleRepository = ExportDebugRoleRepository(role),
      toolInvocationRepository = ExportDebugToolInvocationRepository(session.id),
      externalFactRepository = FakeExternalFactRepository(),
      mapper = RoleplayDebugExportMapper(),
      writer =
        WriteRoleplayDebugBundleUseCase(
          repository = exportRepository,
          serializer = RoleplayDebugExportJsonSerializer(),
        ),
      serializer = RoleplayDebugExportJsonSerializer(),
    ).also { exportUseCase ->
      exportUseCase.nowProvider = { 1_713_888_000_000L }
    }
  return ExportDebugBundleFixture(
    session = session,
    conversationRepository = conversationRepository,
    exportRepository = exportRepository,
    useCase = useCase,
  )
}

private class ExportDebugConversationRepository(
  private val session: Session,
  private val role: RoleCard,
  messages: List<Message>,
) : ConversationRepository {
  private val sessionsFlow = MutableStateFlow(listOf(session))
  private val messagesFlow = MutableStateFlow(messages)
  val events =
    mutableListOf(
      SessionEvent(
        id = "event-memory-pack",
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_PACK_COMPILED,
        payloadJson = """{"tokenBudget":1024}""",
        createdAt = session.updatedAt,
      )
    )
  private val summary =
    SessionSummary(
      sessionId = session.id,
      version = 2,
      coveredUntilSeq = 2,
      summaryText = "The bridge exchange was brief.",
      tokenEstimate = 42,
      updatedAt = session.updatedAt,
    )

  override fun observeSessions(): Flow<List<Session>> = sessionsFlow

  override fun observeMessages(sessionId: String): Flow<List<Message>> =
    flowOf(messagesFlow.value.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> =
    messagesFlow.value.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    listMessages(sessionId).filter { it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? =
    messagesFlow.value.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) {
    sessionsFlow.value = listOf(session)
  }

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messagesFlow.value = messagesFlow.value + message
  }

  override suspend fun updateMessage(message: Message) {
    messagesFlow.value = messagesFlow.value.map { current -> if (current.id == message.id) message else current }
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

  override suspend fun getSummary(sessionId: String): SessionSummary? = summary.takeIf { session.id == sessionId }

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class ExportDebugRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class ExportDebugToolInvocationRepository(
  private val sessionId: String,
) : ToolInvocationRepository {
  private val invocations =
    listOf(
      ToolInvocation(
        id = "tool-1",
        sessionId = sessionId,
        turnId = "assistant-2",
        toolName = "getDeviceSystemTime",
        source = ToolExecutionSource.NATIVE,
        status = ToolInvocationStatus.SUCCEEDED,
        stepIndex = 0,
        argsJson = "{}",
        resultJson = """{"time24h":"18:07"}""",
        resultSummary = "18:07",
        startedAt = 1_000L,
        finishedAt = 1_050L,
      )
    )

  override fun observeBySession(sessionId: String): Flow<List<ToolInvocation>> =
    flowOf(invocations.filter { it.sessionId == sessionId })

  override suspend fun listBySession(sessionId: String): List<ToolInvocation> =
    invocations.filter { it.sessionId == sessionId }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation> =
    invocations.filter { it.sessionId == sessionId && it.turnId == turnId }

  override suspend fun upsert(invocation: ToolInvocation) = Unit
}

private class RecordingDebugExportRepository : RoleplayDebugExportRepository {
  var lastBundleJson: String? = null
  var lastPointerJson: String? = null

  override suspend fun writeBundle(displayName: String, content: ByteArray): RoleplayDebugStoredFile {
    lastBundleJson = content.toString(Charsets.UTF_8)
    return RoleplayDebugStoredFile(
      fileName = displayName,
      relativePath = "Download/GemmaTavern/debug-exports/$displayName",
      adbPath = "/sdcard/Download/GemmaTavern/debug-exports/$displayName",
      contentUri = "content://debug/$displayName",
    )
  }

  override suspend fun writeLatestPointer(content: ByteArray): RoleplayDebugStoredFile {
    lastPointerJson = content.toString(Charsets.UTF_8)
    return RoleplayDebugStoredFile(
      fileName = "latest-debug-export.json",
      relativePath = "Download/GemmaTavern/debug-exports/latest-debug-export.json",
      adbPath = "/sdcard/Download/GemmaTavern/debug-exports/latest-debug-export.json",
      contentUri = "content://debug/latest-debug-export.json",
    )
  }
}

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
