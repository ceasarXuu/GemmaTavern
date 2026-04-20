package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class StChatSessionInteropUseCaseTest {
  @Test
  fun importIntoSession_replacesMessagesAndPersistsMetadata() = runBlocking {
    val session =
      Session(
        id = "session-1",
        roleId = "role-1",
        title = "Existing",
        activeModelId = "model-a",
        createdAt = 1L,
        updatedAt = 1L,
        lastMessageAt = 1L,
      )
    val repository = StChatFakeConversationRepository(session = session)
    val importUseCase =
      ImportStChatJsonlIntoSessionUseCase(
        conversationRepository = repository,
        importStChatJsonlFromUriUseCase =
          ImportStChatJsonlFromUriUseCase(
            documentRepository =
              FakeDocumentRepository(
                reads =
                  mapOf(
                    "content://chat.jsonl" to
                      """
                      {"chat_metadata":{"source":"st"},"user_name":"User","character_name":"Alice"}
                      {"name":"User","is_user":true,"is_system":false,"send_date":"2025-01-01T00:00:00Z","mes":"Hi"}
                      {"name":"Alice","is_user":false,"is_system":false,"send_date":"2025-01-01T00:00:01Z","mes":"Hello"}
                      """
                        .trimIndent()
                  )
              ),
            importStChatJsonlUseCase = ImportStChatJsonlUseCase(),
          ),
      )

    importUseCase.importIntoSession(sessionId = "session-1", uri = "content://chat.jsonl", now = 42L)

    assertEquals(2, repository.messages.size)
    assertEquals("""{"source":"st"}""", repository.session?.interopChatMetadataJson)
    assertEquals(42L, repository.session?.updatedAt)
  }

  @Test
  fun exportFromSession_usesPersistedMetadataAndMessages() = runBlocking {
    val session =
      Session(
        id = "session-1",
        roleId = "role-1",
        title = "Existing",
        activeModelId = "model-a",
        createdAt = 1L,
        updatedAt = 1L,
        lastMessageAt = 1L,
        interopChatMetadataJson = """{"source":"st"}""",
        sessionUserProfile =
          StUserProfile(
            personas = mapOf("session-persona" to "Session Bob"),
          ).snapshotSelectedPersona("session-persona"),
      )
    val messages =
      listOf(
        Message(
          id = "m1",
          sessionId = "session-1",
          seq = 1,
          side = MessageSide.USER,
          content = "Hi",
          createdAt = 1L,
          updatedAt = 1L,
        )
      )
    val repository = StChatFakeConversationRepository(session = session, initialMessages = messages)
    val documentRepository = FakeDocumentRepository()
    val exportUseCase =
      ExportStChatJsonlFromSessionUseCase(
        dataStoreRepository =
          FakeDataStoreRepository(
            stUserProfile =
              StUserProfile(
                personas = mapOf("global-persona" to "Global User"),
              ).snapshotSelectedPersona("global-persona"),
          ),
        conversationRepository = repository,
        roleRepository = FakeRoleRepository(),
        exportStChatJsonlToUriUseCase =
          ExportStChatJsonlToUriUseCase(
            documentRepository = documentRepository,
            exportStChatJsonlUseCase = ExportStChatJsonlUseCase(),
          ),
      )

    exportUseCase.exportFromSession(sessionId = "session-1", uri = "content://out.jsonl")

    val output = documentRepository.writes["content://out.jsonl"]
    requireNotNull(output)
    assertEquals(true, output.contains(""""source":"st""""))
    assertEquals(true, output.contains(""""name":"Session Bob""""))
    assertEquals(true, output.contains(""""mes":"Hi""""))
  }
}

private class StChatFakeConversationRepository(
  var session: Session?,
  initialMessages: List<Message> = emptyList(),
) : ConversationRepository {
  val messages = initialMessages.toMutableList()

  override fun observeSessions(): Flow<List<Session>> = flowOf(listOfNotNull(session))

  override fun observeMessages(sessionId: String): Flow<List<Message>> = flowOf(messages.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> = messages.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session?.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session = error("Not used")

  override suspend fun updateSession(session: Session) {
    this.session = session
  }

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages += message
  }

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

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    this.messages.clear()
    this.messages.addAll(messages)
  }

  override suspend fun nextMessageSeq(sessionId: String): Int = messages.size + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = null

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = emptyList()

  override suspend fun appendEvent(event: SessionEvent) = Unit
}

private class FakeRoleRepository : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(emptyList())

  override suspend fun getRole(roleId: String): RoleCard {
    return RoleCard(
      id = roleId,
      name = "Alice",
      systemPrompt = "Stay in character.",
      createdAt = 1L,
      updatedAt = 1L,
    )
  }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class FakeDocumentRepository(
  private val reads: Map<String, String> = emptyMap(),
) : RoleplayInteropDocumentRepository {
  val writes = mutableMapOf<String, String>()

  override suspend fun readText(uri: String): String {
    return reads[uri] ?: error("Missing fake read for $uri")
  }

  override suspend fun writeText(uri: String, content: String) {
    writes[uri] = content
  }

  override suspend fun readBytes(uri: String): ByteArray {
    return readText(uri).toByteArray()
  }

  override suspend fun writeBytes(uri: String, content: ByteArray) {
    writes[uri] = content.decodeToString()
  }

  override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata {
    return RoleplayInteropDocumentMetadata(displayName = uri.substringAfterLast('/'))
  }
}
