package selfgemma.talk.feature.roleplay.sessions

import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedSessionsViewModelTest {
  @get:Rule
  val mainDispatcherRule = SessionsMainDispatcherRule()

  @Test
  fun restoreSession_movesSessionOutOfArchivedList() = runTest {
    val now = System.currentTimeMillis()
    val archivedSession =
      Session(
        id = "session-archived",
        roleId = "role-1",
        title = "Bridge",
        activeModelId = "gemma-3n",
        archived = true,
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
        lastAssistantMessageExcerpt = "Ready to resume.",
      )
    val repository = ArchivedSessionsConversationRepository(archivedSession)
    val roleRepository = ArchivedSessionsRoleRepository(
      RoleCard(
        id = "role-1",
        name = "Captain Astra",
        summary = "A disciplined starship captain.",
        systemPrompt = "Stay focused.",
        createdAt = now,
        updatedAt = now,
      )
    )

    val viewModel =
      ArchivedSessionsViewModel(
        appContext = ContextWrapper(null),
        conversationRepository = repository,
        roleRepository = roleRepository,
      )
    val collector = backgroundScope.launch { viewModel.uiState.collect { } }
    advanceUntilIdle()

    assertTrue(repository.observeArchivedSessions().first().size == 1)
    var restored = false

    viewModel.restoreSession(archivedSession.id) { restored = true }
    advanceUntilIdle()

    assertTrue(restored)
    assertTrue(repository.restoredSessionIds.contains(archivedSession.id))
    assertTrue(viewModel.uiState.value.sessions.isEmpty())
    collector.cancel()
  }
}

private class ArchivedSessionsConversationRepository(
  session: Session,
) : ConversationRepository {
  private val archivedSessionsFlow = MutableStateFlow(listOf(session))
  val restoredSessionIds = mutableListOf<String>()

  override fun observeSessions(): Flow<List<Session>> = flowOf(emptyList())

  override fun observeArchivedSessions(): Flow<List<Session>> = archivedSessionsFlow

  override fun observeMessages(sessionId: String): Flow<List<Message>> = flowOf(emptyList())

  override suspend fun listMessages(sessionId: String): List<Message> = emptyList()

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> = emptyList()

  override suspend fun getMessage(messageId: String): Message? = null

  override suspend fun getSession(sessionId: String): Session? =
    archivedSessionsFlow.value.firstOrNull { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) = Unit

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun restoreSession(sessionId: String) {
    restoredSessionIds += sessionId
    archivedSessionsFlow.value = archivedSessionsFlow.value.filterNot { it.id == sessionId }
  }

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

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = emptyList()

  override suspend fun appendEvent(event: SessionEvent) = Unit
}

private class ArchivedSessionsRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}
