package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.usecase.toStMacroContext
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class StSampleCardsRegressionTest {
  private val promptAssembler = PromptAssembler(TokenEstimator())

  @Test
  fun importedSampleCards_match_expected_runtime_entrypoints() = runBlocking {
    val cardsDir = locateSampleCardsDirOrNull()
    assumeTrue("tests/st_cards fixtures are not present in this checkout", cardsDir != null)
    val resolvedCardsDir = requireNotNull(cardsDir)
    val repository = LocalFileRoleCardDocumentRepository(resolvedCardsDir)
    val importUseCase =
      ImportStRoleCardFromUriUseCase(
        appContext = SampleCardsImportContext(),
        documentRepository = repository,
        importStV2RoleCardUseCase = ImportStV2RoleCardUseCase(),
      )

    val sampleFiles = Files.list(resolvedCardsDir).use { stream -> stream.sorted().toList() }
    assertEquals(10, sampleFiles.size)
    val reportLines = mutableListOf<String>()

    sampleFiles.forEach { file ->
      val imported = importUseCase.importFromUri(file.toString(), now = 100L)
      val cardData = imported.cardCore?.data
      val macroContext = imported.toStMacroContext()
      val expectedSeed =
        macroContext.substitute(
          cardData?.first_mes
            ?.takeIf(String::isNotBlank)
            ?: cardData?.alternate_greetings?.firstOrNull().orEmpty()
        )
      val prompt =
        promptAssembler.assemble(
          role = imported,
          summary = null,
          memories = emptyList<MemoryItem>(),
          recentMessages = emptyList<Message>(),
          pendingUserInput = "",
        )
      val seededMessages =
        SampleSessionSeedConversationRepository().also { conversationRepository ->
          CreateRoleplaySessionUseCase(
            dataStoreRepository = FakeDataStoreRepository(),
            conversationRepository = conversationRepository,
            roleRepository = SampleSessionSeedRoleRepository(imported),
          ).invoke(roleId = imported.id, modelId = "gemma")
        }.messages

      val reportLine =
        buildString {
          append(file.fileName)
          append(" | role=")
          append(imported.name)
          append(" | seedChars=")
          append(expectedSeed.length)
          append(" | loreEntries=")
          append(cardData?.character_book?.entries?.size ?: 0)
          append(" | htmlSeed=")
          append(expectedSeed.contains('<'))
          append(" | promptChars=")
          append(prompt.length)
        }
      reportLines += reportLine

      assertTrue("${file.fileName} name should not be blank", imported.name.isNotBlank())
      assertFalse("${file.fileName} should not reintroduce suggested opening tone", prompt.contains("[Suggested Opening Tone]"))

      if (expectedSeed.isBlank()) {
        assertTrue("${file.fileName} should not seed a blank opening", seededMessages.isEmpty())
      } else {
        assertEquals("${file.fileName} should seed exactly one assistant opener", 1, seededMessages.size)
        assertEquals(expectedSeed.trim(), seededMessages.single().content.trim())
        assertFalse("${file.fileName} seeded opener should not keep {{char}}", seededMessages.single().content.contains("{{char}}"))
        assertFalse("${file.fileName} seeded opener should not keep {{user}}", seededMessages.single().content.contains("{{user}}"))
        assertFalse("${file.fileName} seeded opener should not keep <USER>", seededMessages.single().content.contains("<USER>"))
      }

      if (expectedSeed.contains("<div", ignoreCase = true) || expectedSeed.contains("<img", ignoreCase = true)) {
        val openerSnippet =
          expectedSeed.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(64)
        assertFalse(
          "${file.fileName} should not leak raw opener HTML into prompt",
          openerSnippet.isNotBlank() && prompt.contains(openerSnippet),
        )
      }

      cardData?.post_history_instructions
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { postHistory ->
          assertTrue("${file.fileName} should preserve post-history instructions", prompt.contains(postHistory))
        }

      assertFalse("${file.fileName} prompt should not keep {{char}}", prompt.contains("{{char}}"))
      assertFalse("${file.fileName} prompt should not keep {{user}}", prompt.contains("{{user}}"))
    }

    Files.createDirectories(testArtifactsDir(resolvedCardsDir))
    Files.write(
      testArtifactsDir(resolvedCardsDir).resolve("st-sample-cards-regression.txt"),
      reportLines.joinToString(System.lineSeparator()).toByteArray(UTF_8),
    )
    Unit
  }

  private fun locateSampleCardsDirOrNull(): Path? {
    val current = Paths.get("").toAbsolutePath().normalize()
    return generateSequence(current) { it.parent }
      .map { it.resolve("tests").resolve("st_cards") }
      .firstOrNull { Files.isDirectory(it) }
  }

  private fun testArtifactsDir(cardsDir: Path): Path {
    return cardsDir.parent.parent
      .resolve("Android")
      .resolve("src")
      .resolve("app")
      .resolve("build")
      .resolve("test-results")
      .resolve("testDebugUnitTest")
  }
}

private class LocalFileRoleCardDocumentRepository(private val cardsDir: Path) : RoleplayInteropDocumentRepository {
  override suspend fun readText(uri: String): String {
    return String(Files.readAllBytes(Paths.get(uri)), UTF_8)
  }

  override suspend fun writeText(uri: String, content: String) {
    error("Not needed for sample card regression tests: $uri")
  }

  override suspend fun readBytes(uri: String): ByteArray {
    return Files.readAllBytes(Paths.get(uri))
  }

  override suspend fun writeBytes(uri: String, content: ByteArray) {
    error("Not needed for sample card regression tests: $uri")
  }

  override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata {
    val path = Paths.get(uri)
    require(path.startsWith(cardsDir)) { "Unexpected card path: $uri" }
    return RoleplayInteropDocumentMetadata(
      displayName = path.fileName.toString(),
      mimeType = "image/png",
    )
  }
}

private class SampleSessionSeedConversationRepository : ConversationRepository {
  private val sessions = linkedMapOf<String, Session>()
  val messages = mutableListOf<Message>()

  override fun observeSessions(): Flow<List<Session>> = MutableStateFlow(emptyList())

  override fun observeMessages(sessionId: String): Flow<List<Message>> = MutableStateFlow(messages.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> = messages.filter { it.sessionId == sessionId }

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId && it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = sessions[sessionId]

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    val session =
      Session(
        id = "session-$roleId",
        roleId = roleId,
        title = "Sample Session",
        activeModelId = modelId,
        createdAt = 100L,
        updatedAt = 100L,
        lastMessageAt = 100L,
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

  override suspend fun nextMessageSeq(sessionId: String): Int = this.messages.count { it.sessionId == sessionId } + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = null

  override suspend fun upsertSummary(summary: SessionSummary) = Unit

  override suspend fun deleteSummary(sessionId: String) = Unit

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = emptyList()

  override suspend fun appendEvent(event: SessionEvent) = Unit
}

private class SampleSessionSeedRoleRepository(private val role: RoleCard) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = MutableStateFlow(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class SampleCardsImportContext : ContextWrapper(null) {
  private val rootDir =
    createTempDir(prefix = "st-sample-cards").apply {
      deleteOnExit()
    }

  override fun getFilesDir(): File = rootDir
}
