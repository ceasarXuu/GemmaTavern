package selfgemma.talk.data.roleplay.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import selfgemma.talk.data.roleplay.db.RoleplayDatabase
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

@RunWith(AndroidJUnit4::class)
class RoomConversationRepositoryTest {
  private lateinit var database: RoleplayDatabase
  private lateinit var repository: RoomConversationRepository

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    database =
      Room.inMemoryDatabaseBuilder(context, RoleplayDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    repository =
      RoomConversationRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        sessionSummaryDao = database.sessionSummaryDao(),
        sessionEventDao = database.sessionEventDao(),
      )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun appendMessage_keepsMessageRowAfterSessionMetadataSync() = runBlocking {
    val now = 1_700_000_000_000L
    val roleId = "role-1"
    val sessionId = "session-1"

    database.roleDao().upsert(
      RoleEntity(
        id = roleId,
        name = "Mili",
        systemPrompt = "Stay in character.",
        createdAt = now,
        updatedAt = now,
      )
    )
    database.sessionDao().upsert(
      SessionEntity(
        id = sessionId,
        roleId = roleId,
        title = "New Session",
        activeModelId = "Gemma-4-E2B-it",
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
      )
    )

    repository.appendMessage(
      Message(
        id = "message-1",
        sessionId = sessionId,
        seq = 1,
        side = MessageSide.USER,
        status = MessageStatus.COMPLETED,
        content = "hello there",
        createdAt = now,
        updatedAt = now,
      )
    )

    val savedMessages = database.messageDao().listLatest(sessionId = sessionId, limit = 10)
    assertEquals(1, savedMessages.size)
    assertEquals("hello there", savedMessages.single().content)

    val savedSession = database.sessionDao().getById(sessionId)
    assertNotNull(savedSession)
    assertEquals("hello there", savedSession?.lastUserMessageExcerpt)
  }
}