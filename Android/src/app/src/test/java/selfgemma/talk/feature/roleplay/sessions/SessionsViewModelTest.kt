package selfgemma.talk.feature.roleplay.sessions

import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayDebugStoredFile
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayDebugExportRepository
import selfgemma.talk.domain.roleplay.usecase.EnsureRoleplaySeedDataUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportRoleplayDebugBundleFromSessionUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportStChatJsonlFromSessionUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportStChatJsonlToUriUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportStChatJsonlUseCase
import selfgemma.talk.domain.roleplay.usecase.FakeExternalFactRepository
import selfgemma.talk.domain.roleplay.usecase.ImportStChatJsonlFromUriUseCase
import selfgemma.talk.domain.roleplay.usecase.ImportStChatJsonlIntoSessionUseCase
import selfgemma.talk.domain.roleplay.usecase.ImportStChatJsonlUseCase
import selfgemma.talk.domain.roleplay.usecase.RoleplayDebugExportJsonSerializer
import selfgemma.talk.domain.roleplay.usecase.RoleplayDebugExportMapper
import selfgemma.talk.domain.roleplay.usecase.RoleplaySeedCatalog
import selfgemma.talk.domain.roleplay.usecase.WriteRoleplayDebugBundleUseCase
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.testing.FakeDataStoreRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
  @get:Rule
  val mainDispatcherRule = SessionsMainDispatcherRule()

  @Test
  fun exportDebugBundle_autoDismissesStatusMessage() =
    runTest {
      val fixture = createFixture()
      val collector = backgroundScope.launch { fixture.viewModel.uiState.collect() }
      advanceUntilIdle()

      fixture.viewModel.exportDebugBundle(fixture.session.id)
      runCurrent()

      val exportedFileName = fixture.debugExportRepository.lastBundleFile?.fileName
      assertEquals(
        "Debug bundle exported for Bridge (session-1): $exportedFileName",
        fixture.viewModel.uiState.value.statusMessage,
      )
      assertNull(fixture.viewModel.uiState.value.errorMessage)
      advanceTimeBy(SESSIONS_STATUS_MESSAGE_AUTO_DISMISS_MS)
      runCurrent()
      assertNull(fixture.viewModel.uiState.value.statusMessage)
      collector.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsMainDispatcherRule : TestWatcher() {
  private val dispatcher = StandardTestDispatcher()

  override fun starting(description: Description) {
    kotlinx.coroutines.Dispatchers.setMain(dispatcher)
  }

  override fun finished(description: Description) {
    kotlinx.coroutines.Dispatchers.resetMain()
  }
}

private data class SessionsFixture(
  val viewModel: SessionsViewModel,
  val session: Session,
  val debugExportRepository: SessionsRecordingDebugExportRepository,
)

private fun createFixture(): SessionsFixture {
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
      lastAssistantMessageExcerpt = "Route is set.",
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
  val conversationRepository = SessionsConversationRepository(session = session)
  val roleRepository = SessionsRoleRepository(role)
  val debugExportRepository = SessionsRecordingDebugExportRepository()
  val dataStoreRepository =
    FakeDataStoreRepository(
      stUserProfile = StUserProfile(personas = mapOf("captain" to "Captain Mae")),
    )
  val exportDebugUseCase =
    ExportRoleplayDebugBundleFromSessionUseCase(
      dataStoreRepository = dataStoreRepository,
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      toolInvocationRepository = SessionsToolInvocationRepository(),
      externalFactRepository = FakeExternalFactRepository(),
      mapper = RoleplayDebugExportMapper(),
      writer =
        WriteRoleplayDebugBundleUseCase(
          repository = debugExportRepository,
          serializer = RoleplayDebugExportJsonSerializer(),
        ),
      serializer = RoleplayDebugExportJsonSerializer(),
    ).also { useCase ->
      useCase.nowProvider = { 1_713_888_000_000L }
    }
  val interopDocumentRepository = SessionsInteropDocumentRepository()
  val viewModel =
    SessionsViewModel(
      appContext = ContextWrapper(null),
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      ensureRoleplaySeedData =
        EnsureRoleplaySeedDataUseCase(
          roleRepository = roleRepository,
          roleplaySeedCatalog = EmptySeedCatalog(),
        ),
      importStChatJsonlIntoSessionUseCase =
        ImportStChatJsonlIntoSessionUseCase(
          conversationRepository = conversationRepository,
          importStChatJsonlFromUriUseCase =
            ImportStChatJsonlFromUriUseCase(
              documentRepository = interopDocumentRepository,
              importStChatJsonlUseCase = ImportStChatJsonlUseCase(),
            ),
        ),
      exportStChatJsonlFromSessionUseCase =
        ExportStChatJsonlFromSessionUseCase(
          dataStoreRepository = dataStoreRepository,
          conversationRepository = conversationRepository,
          roleRepository = roleRepository,
          exportStChatJsonlToUriUseCase =
            ExportStChatJsonlToUriUseCase(
              documentRepository = interopDocumentRepository,
              exportStChatJsonlUseCase = ExportStChatJsonlUseCase(),
            ),
        ),
      exportRoleplayDebugBundleFromSessionUseCase = exportDebugUseCase,
    )
  viewModel.stringResolver =
    { resId, args ->
      when (resId) {
        R.string.sessions_unknown_role -> "Unknown role"
        R.string.sessions_no_messages -> "No messages yet"
        R.string.sessions_status_imported -> "ST chat imported"
        R.string.sessions_error_import_failed -> "Failed to import ST chat"
        R.string.sessions_status_exported -> "ST chat exported"
        R.string.sessions_error_export_failed -> "Failed to export ST chat"
        R.string.roleplay_debug_export_error -> "Failed to export the debug bundle"
        R.string.roleplay_debug_export_status ->
          "Debug bundle exported for ${args[0]} (${args[1]}): ${args[2]}"
        else -> error("Unexpected string resource request: $resId")
      }
    }
  return SessionsFixture(
    viewModel = viewModel,
    session = session,
    debugExportRepository = debugExportRepository,
  )
}

private class SessionsConversationRepository(
  private val session: Session,
) : ConversationRepository {
  private val sessionsFlow = MutableStateFlow(listOf(session))
  private val messages =
    listOf(
      Message(
        id = "assistant-1",
        sessionId = session.id,
        seq = 1,
        side = MessageSide.ASSISTANT,
        status = MessageStatus.COMPLETED,
        content = "Route is set.",
        createdAt = session.updatedAt,
        updatedAt = session.updatedAt,
      )
    )

  override fun observeSessions(): Flow<List<Session>> = sessionsFlow

  override fun observeMessages(sessionId: String): Flow<List<Message>> =
    flowOf(messages.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    listMessages(sessionId).filter { it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) {
    sessionsFlow.value = listOf(session)
  }

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

  override suspend fun nextMessageSeq(sessionId: String): Int = 2

  override suspend fun getSummary(sessionId: String): SessionSummary? = null

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = emptyList()

  override suspend fun appendEvent(event: SessionEvent) = Unit
}

private class SessionsRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  private val rolesFlow = MutableStateFlow(listOf(role))

  override fun observeRoles(): Flow<List<RoleCard>> = rolesFlow

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) {
    rolesFlow.value = listOf(role)
  }

  override suspend fun deleteRole(roleId: String) = Unit
}

private class SessionsToolInvocationRepository : ToolInvocationRepository {
  override fun observeBySession(sessionId: String) = flowOf(emptyList<selfgemma.talk.domain.roleplay.model.ToolInvocation>())

  override suspend fun listBySession(sessionId: String) = emptyList<selfgemma.talk.domain.roleplay.model.ToolInvocation>()

  override suspend fun listByTurn(sessionId: String, turnId: String) = emptyList<selfgemma.talk.domain.roleplay.model.ToolInvocation>()

  override suspend fun upsert(invocation: selfgemma.talk.domain.roleplay.model.ToolInvocation) = Unit
}

private class SessionsInteropDocumentRepository : RoleplayInteropDocumentRepository {
  override suspend fun readText(uri: String): String = ""

  override suspend fun writeText(uri: String, content: String) = Unit

  override suspend fun readBytes(uri: String): ByteArray = byteArrayOf()

  override suspend fun writeBytes(uri: String, content: ByteArray) = Unit

  override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata =
    RoleplayInteropDocumentMetadata(displayName = null, mimeType = null)
}

private class SessionsRecordingDebugExportRepository : RoleplayDebugExportRepository {
  var lastBundleFile: RoleplayDebugStoredFile? = null

  override suspend fun writeBundle(displayName: String, content: ByteArray): RoleplayDebugStoredFile {
    return RoleplayDebugStoredFile(
      fileName = displayName,
      relativePath = "Download/GemmaTavern/debug-exports/$displayName",
      adbPath = "/sdcard/Download/GemmaTavern/debug-exports/$displayName",
      contentUri = "content://debug/$displayName",
    ).also { lastBundleFile = it }
  }

  override suspend fun writeLatestPointer(content: ByteArray): RoleplayDebugStoredFile {
    return RoleplayDebugStoredFile(
      fileName = "latest-debug-export.json",
      relativePath = "Download/GemmaTavern/debug-exports/latest-debug-export.json",
      adbPath = "/sdcard/Download/GemmaTavern/debug-exports/latest-debug-export.json",
      contentUri = "content://debug/latest-debug-export.json",
    )
  }
}

private class EmptySeedCatalog : RoleplaySeedCatalog {
  override fun defaultRoles(now: Long, defaultModelId: String?): List<RoleCard> = emptyList()
}
