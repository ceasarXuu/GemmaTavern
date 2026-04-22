package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class SummarizeSessionUseCaseTest {
  @Test
  fun invoke_writesSummaryAndSummaryUpdateEvent() =
    runBlocking {
      val now = System.currentTimeMillis()
      val conversationRepository =
        SummaryConversationRepository(
          session =
            Session(
              id = "session-1",
              roleId = "role-1",
              title = "Docking Bay",
              activeModelId = "gemma-3n",
              createdAt = now,
              updatedAt = now,
              lastMessageAt = now,
              sessionUserProfile =
                StUserProfile(
                  personas = mapOf("captain" to "Captain Mae"),
                ).snapshotSelectedPersona("captain"),
            ),
          messages =
            listOf(
              Message(
                id = "message-1",
                sessionId = "session-1",
                seq = 1,
                side = MessageSide.USER,
                content = "We need a clean exit route.",
                status = MessageStatus.COMPLETED,
                createdAt = now,
                updatedAt = now,
              ),
              Message(
                id = "message-2",
                sessionId = "session-1",
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "The cargo elevator still works if we move now.",
                status = MessageStatus.INTERRUPTED,
                createdAt = now,
                updatedAt = now,
              ),
            ),
        )
      val compactionCacheRepository = SummaryCompactionCacheRepository()

      SummarizeSessionUseCase(
        FakeDataStoreRepository(),
        conversationRepository,
        compactionCacheRepository,
        TokenEstimator(),
      )("session-1")

      val summary = conversationRepository.savedSummary
      assertNotNull(summary)
      assertTrue(summary!!.summaryText.contains("Stable synopsis:"))
      assertTrue(summary.summaryText.contains("Captain Mae: We need a clean exit route."))
      assertTrue(summary.summaryText.contains("Assistant: The cargo elevator still works if we move now."))
      assertEquals(1, summary.version)
      assertEquals(1, conversationRepository.events.size)
      assertEquals("session-1", conversationRepository.events.single().sessionId)
      assertTrue(compactionCacheRepository.entries.isEmpty())
    }

  @Test
  fun invoke_buildsCompactionCacheForOlderCanonicalMessages() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session =
        Session(
          id = "session-2",
          roleId = "role-1",
          title = "Harbor",
          activeModelId = "gemma-3n",
          createdAt = now,
          updatedAt = now,
          lastMessageAt = now,
        )
      val messages =
        (1..14).map { index ->
          Message(
            id = "message-$index",
            sessionId = session.id,
            seq = index,
            side = if (index % 2 == 0) MessageSide.ASSISTANT else MessageSide.USER,
            content =
              if (index == 5) {
                "Hours later, we regrouped at the harbor checkpoint."
              } else {
                "Turn $index keeps the beacon key and forged pass in play."
              },
            status = MessageStatus.COMPLETED,
            createdAt = now + index,
            updatedAt = now + index,
          )
        }
      val conversationRepository = SummaryConversationRepository(session = session, messages = messages)
      val compactionCacheRepository = SummaryCompactionCacheRepository()

      SummarizeSessionUseCase(
        FakeDataStoreRepository(),
        conversationRepository,
        compactionCacheRepository,
        TokenEstimator(),
      )(session.id)

      assertEquals(1, compactionCacheRepository.entries.size)
      val entry = compactionCacheRepository.entries.single()
      assertEquals(session.id, entry.sessionId)
      assertEquals("message-1", entry.rangeStartMessageId)
      assertEquals("message-6", entry.rangeEndMessageId)
      assertEquals(CompactionSummaryType.SCENE, entry.summaryType)
      assertTrue(entry.compactText.contains("Scene window:"))
      assertTrue(conversationRepository.events.any { it.eventType == selfgemma.talk.domain.roleplay.model.SessionEventType.MEMORY_COMPACTION_UPSERTED })
    }

  @Test
  fun invoke_excludesEphemeralToolBackedTurnFromStableSynopsis() =
    runBlocking {
      val now = System.currentTimeMillis()
      val conversationRepository =
        SummaryConversationRepository(
          session =
            Session(
              id = "session-tool",
              roleId = "role-1",
              title = "Time Check",
              activeModelId = "gemma-3n",
              createdAt = now,
              updatedAt = now,
              lastMessageAt = now,
              sessionUserProfile = StUserProfile(),
            ),
          messages =
            listOf(
              Message(
                id = "message-1",
                sessionId = "session-tool",
                seq = 1,
                side = MessageSide.USER,
                content = "We still need a safe route out.",
                status = MessageStatus.COMPLETED,
                createdAt = now,
                updatedAt = now,
              ),
              Message(
                id = "message-2",
                sessionId = "session-tool",
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "The east corridor is still open.",
                status = MessageStatus.COMPLETED,
                createdAt = now,
                updatedAt = now,
              ),
              Message(
                id = "message-3",
                sessionId = "session-tool",
                seq = 3,
                side = MessageSide.USER,
                content = "What time is it right now?",
                status = MessageStatus.COMPLETED,
                createdAt = now,
                updatedAt = now,
              ),
              Message(
                id = "message-4",
                sessionId = "session-tool",
                seq = 4,
                side = MessageSide.ASSISTANT,
                content = "It is Thursday at 01:37 right now.",
                status = MessageStatus.COMPLETED,
                metadataJson =
                  mergeRoleplayToolTurnMetadata(
                    metadataJson = null,
                    metadata =
                      RoleplayToolTurnMetadata(
                        userMessageIds = listOf("message-3"),
                        toolNames = listOf("getDeviceSystemTime"),
                        externalFactIds = listOf("fact-1"),
                        excludeFromStableSynopsis = true,
                        externalFactCount = 1,
                      ),
                  ),
                createdAt = now,
                updatedAt = now,
              ),
            ),
        )

      SummarizeSessionUseCase(
        FakeDataStoreRepository(),
        conversationRepository,
        SummaryCompactionCacheRepository(),
        TokenEstimator(),
      )("session-tool")

      val summary = requireNotNull(conversationRepository.savedSummary)
      assertTrue(summary.summaryText.contains("We still need a safe route out."))
      assertTrue(summary.summaryText.contains("The east corridor is still open."))
      assertTrue(!summary.summaryText.contains("What time is it right now?"))
      assertTrue(!summary.summaryText.contains("It is Thursday at 01:37 right now."))
    }
}

private class SummaryConversationRepository(
  private val session: Session,
  private val messages: List<Message>,
) : ConversationRepository {
  var savedSummary: SessionSummary? = null
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
    return savedSummary?.takeIf { it.sessionId == sessionId }
  }

  override suspend fun upsertSummary(summary: SessionSummary) {
    savedSummary = summary
  }

  override suspend fun deleteSummary(sessionId: String) {
    savedSummary = savedSummary?.takeUnless { it.sessionId == sessionId }
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> {
    return events.filter { it.sessionId == sessionId }
  }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class SummaryCompactionCacheRepository : CompactionCacheRepository {
  val entries = mutableListOf<CompactionCacheEntry>()

  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> {
    return entries.filter { it.sessionId == sessionId }
  }

  override suspend fun upsert(entry: CompactionCacheEntry) {
    entries.removeAll { it.id == entry.id }
    entries += entry
  }

  override suspend fun deleteBySession(sessionId: String) {
    entries.removeAll { it.sessionId == sessionId }
  }
}
