package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class CreateRoleplaySessionUseCaseTest {
  @Test
  fun createSession_seedsAssistantOpeningMessage() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-1",
          name = "Iris",
          systemPrompt = "Stay in character.",
          openingLine = "Wrong fallback",
          cardCore = StCharacterCard(name = "Iris", data = StCharacterCardData(first_mes = "<div>Hello</div>")),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    val session =
      CreateRoleplaySessionUseCase(
        dataStoreRepository = FakeDataStoreRepository(),
        conversationRepository = conversationRepository,
        roleRepository = roleRepository,
      ).invoke(roleId = "role-1", modelId = "gemma")

    assertEquals("role-1", session.roleId)
    assertEquals(1, conversationRepository.messages.size)
    assertEquals(MessageSide.ASSISTANT, conversationRepository.messages.single().side)
    assertEquals("<div>Hello</div>", conversationRepository.messages.single().content)
    assertEquals(MessageStatus.COMPLETED, conversationRepository.messages.single().status)
  }

  @Test
  fun createSession_skipsSeedWhenOpeningMessageBlank() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-2",
          name = "Nova",
          systemPrompt = "Stay in character.",
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    CreateRoleplaySessionUseCase(
      dataStoreRepository = FakeDataStoreRepository(),
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
    ).invoke(roleId = "role-2", modelId = "gemma")

    assertTrue(conversationRepository.messages.isEmpty())
  }

  @Test
  fun createSession_fallsBackToAlternateGreeting() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-3",
          name = "Mira",
          systemPrompt = "Stay in character.",
          cardCore =
            StCharacterCard(
              name = "Mira",
              data = StCharacterCardData(first_mes = "", alternate_greetings = listOf("Alt hello", "Alt two")),
            ),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    CreateRoleplaySessionUseCase(
      dataStoreRepository = FakeDataStoreRepository(),
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
    ).invoke(roleId = "role-3", modelId = "gemma")

    assertEquals(1, conversationRepository.messages.size)
    assertEquals("Alt hello", conversationRepository.messages.single().content)
  }

  @Test
  fun createSession_prefersCanonicalOpeningWhenProjectionIsStale() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-4",
          name = "Mira",
          systemPrompt = "Legacy prompt",
          openingLine = "Legacy opening",
          cardCore =
            StCharacterCard(
              name = "Mira",
              data = StCharacterCardData(first_mes = "Canonical opening"),
            ),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    CreateRoleplaySessionUseCase(
      dataStoreRepository = FakeDataStoreRepository(),
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
    ).invoke(roleId = "role-4", modelId = "gemma")

    assertEquals("Canonical opening", conversationRepository.messages.single().content)
  }

  @Test
  fun createSession_substitutesStNameMacrosInOpeningMessage() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-5",
          name = "Catty",
          systemPrompt = "Stay in character.",
          cardCore =
            StCharacterCard(
              name = "Catty",
              data = StCharacterCardData(first_mes = "<USER> looks at {{char}} and {{user}} smiles back."),
            ),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    CreateRoleplaySessionUseCase(
      dataStoreRepository = FakeDataStoreRepository(),
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
    ).invoke(roleId = "role-5", modelId = "gemma")

    assertEquals("User looks at Catty and User smiles back.", conversationRepository.messages.single().content)
  }

  @Test
  fun createSession_persistsSelectedPersonaSnapshotAndUsesItForMacros() = runBlocking {
    val conversationRepository = SessionSeedConversationRepository()
    val roleRepository =
      SessionSeedRoleRepository(
        RoleCard(
          id = "role-6",
          name = "Catty",
          systemPrompt = "Stay in character.",
          cardCore =
            StCharacterCard(
              name = "Catty",
              data = StCharacterCardData(first_mes = "Hello {{user}}."),
            ),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )
    val selectedPersona =
      StUserProfile(
        userAvatarId = "slot-a",
        defaultPersonaId = "slot-b",
        personas = mapOf("slot-a" to "Alice", "slot-b" to "Bob"),
        personaDescriptions =
          mapOf(
            "slot-a" to StPersonaDescriptor(description = "alpha"),
            "slot-b" to StPersonaDescriptor(description = "beta"),
          ),
      ).snapshotSelectedPersona("slot-b")

    val session =
      CreateRoleplaySessionUseCase(
        dataStoreRepository = FakeDataStoreRepository(),
        conversationRepository = conversationRepository,
        roleRepository = roleRepository,
      ).invoke(roleId = "role-6", modelId = "gemma", userProfile = selectedPersona)

    assertEquals("Bob", session.sessionUserProfile?.userName)
    assertEquals("slot-b", session.sessionUserProfile?.userAvatarId)
    assertEquals(1, session.sessionUserProfile?.personas?.size)
    assertEquals("Hello Bob.", conversationRepository.messages.single().content)
  }
}

private class SessionSeedConversationRepository : ConversationRepository {
  private val sessions = linkedMapOf<String, Session>()
  val messages = mutableListOf<Message>()

  override fun observeSessions(): Flow<List<Session>> = MutableStateFlow(emptyList())

  override fun observeMessages(sessionId: String): Flow<List<Message>> = MutableStateFlow(emptyList())

  override suspend fun listMessages(sessionId: String): List<Message> = messages.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = sessions[sessionId]

  override suspend fun createSession(
    roleId: String,
    modelId: String,
    userProfile: StUserProfile?,
  ): Session {
    val session =
      Session(
        id = "session-1",
        roleId = roleId,
        title = "New Session",
        activeModelId = modelId,
        createdAt = 10L,
        updatedAt = 10L,
        lastMessageAt = 10L,
        sessionUserProfile = userProfile,
      )
    sessions[session.id] = session
    return session
  }

  override suspend fun updateSession(session: Session) {
    sessions[session.id] = session
  }

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages += message
    val session = sessions[message.sessionId]
    if (session != null) {
      sessions[message.sessionId] = session.copy(updatedAt = message.updatedAt, lastMessageAt = message.updatedAt)
    }
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

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) = Unit

  override suspend fun nextMessageSeq(sessionId: String): Int = messages.count { it.sessionId == sessionId } + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = null

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = emptyList()

  override suspend fun appendEvent(event: SessionEvent) = Unit
}

private class SessionSeedRoleRepository(private val role: RoleCard) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = MutableStateFlow(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}
