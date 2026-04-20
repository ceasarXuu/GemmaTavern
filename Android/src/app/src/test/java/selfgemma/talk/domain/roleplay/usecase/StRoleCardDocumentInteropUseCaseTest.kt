package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import java.util.Base64
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class StRoleCardDocumentInteropUseCaseTest {
  @Test
  fun importFromUri_readsPngRoleCard() = runBlocking {
    val repository = FakeRoleCardDocumentRepository()
    val fakeContext = FakeImportContext()
    repository.metadata["content://cards/iris.png"] =
      RoleplayInteropDocumentMetadata(displayName = "iris.png", mimeType = "image/png")
    repository.metadata["content://cards/embedded.png"] =
      RoleplayInteropDocumentMetadata(displayName = "embedded.png", mimeType = "image/png")
    repository.byteDocuments["content://cards/iris.png"] =
      ExportStRoleCardToUriUseCase(
        documentRepository = repository,
        exportStV2RoleCardUseCase = ExportStV2RoleCardUseCase(),
      ).run {
        val role =
          RoleCard(
            id = "role-1",
            name = "Iris",
            summary = "Archivist",
            systemPrompt = "Stay in character.",
            createdAt = 1L,
            updatedAt = 1L,
          )
        exportToUri("content://cards/embedded.png", role)
        repository.byteDocuments.getValue("content://cards/embedded.png")
      }

    val imported =
      ImportStRoleCardFromUriUseCase(
        appContext = fakeContext,
        documentRepository = repository,
        importStV2RoleCardUseCase = ImportStV2RoleCardUseCase(),
    ).importFromUri("content://cards/iris.png", now = 5L)

    assertEquals("Iris", imported.name)
    assertFalse(imported.avatarUri.isNullOrBlank())
    assertFalse(imported.avatarUri!!.startsWith("content://"))
    assertTrue(File(imported.avatarUri!!).exists())
    assertEquals(imported.avatarUri, imported.mediaProfile?.primaryAvatar?.uri)
    assertEquals(RoleCardSourceFormat.ST_PNG, imported.interopState?.sourceFormat)
  }

  @Test
  fun importFromUri_normalizesLegacyCardLikeSillyTavern() = runBlocking {
    val repository = FakeRoleCardDocumentRepository().apply {
      metadata["content://cards/legacy.json"] =
        RoleplayInteropDocumentMetadata(displayName = "legacy.json", mimeType = "application/json")
      documents["content://cards/legacy.json"] =
        """
        {
          "name": "Catty",
          "description": "Street cat turned catboy.",
          "personality": "Feral and skittish.",
          "scenario": "Taken in from a shelter.",
          "first_mes": "<div>Hello</div>",
          "mes_example": "<START>\n{{user}}: Hi",
          "creator_notes": "legacy notes",
          "system_prompt": "Protect {{user}}.",
          "post_history_instructions": "Stay close to {{char}}.",
          "alternate_greetings": ["Hi {{user}}", "Hey there"],
          "tags": ["NSFW", "Catboy"],
          "character_book": {"entries":[{"id":1}]},
          "extensions": {"depth_prompt":{"prompt":"Help {{user}}.","depth":2,"role":"system"}}
        }
        """.trimIndent()
    }

    val imported =
      ImportStRoleCardFromUriUseCase(
        appContext = FakeImportContext(),
        documentRepository = repository,
        importStV2RoleCardUseCase = ImportStV2RoleCardUseCase(),
      ).importFromUri("content://cards/legacy.json", now = 9L)

    assertEquals("Catty", imported.name)
    assertEquals("Street cat turned catboy.", imported.summary)
    assertEquals("<div>Hello</div>", imported.openingLine)
    assertEquals(listOf("NSFW", "Catboy"), imported.tags)
    assertEquals("legacy notes", imported.cardCore?.data?.creator_notes)
    assertEquals("Protect {{user}}.", imported.cardCore?.data?.system_prompt)
    assertEquals("Stay close to {{char}}.", imported.cardCore?.data?.post_history_instructions)
    assertEquals(listOf("Hi {{user}}", "Hey there"), imported.cardCore?.data?.alternate_greetings)
    assertTrue(imported.cardCore?.data?.character_book != null)
    assertTrue(imported.cardCore?.data?.extensions?.has("depth_prompt") == true)
  }

  @Test
  fun exportToUri_writesPngWhenTargetIsPng() = runBlocking {
    val repository = FakeRoleCardDocumentRepository().apply {
      metadata["content://cards/astra.png"] =
        RoleplayInteropDocumentMetadata(displayName = "astra.png", mimeType = "image/png")
    }
    val useCase =
      ExportStRoleCardToUriUseCase(
        documentRepository = repository,
        exportStV2RoleCardUseCase = ExportStV2RoleCardUseCase(),
      )

    useCase.exportToUri(
      uri = "content://cards/astra.png",
      role =
        RoleCard(
          id = "role-1",
          name = "Captain Astra",
          summary = "Mission-first captain",
          systemPrompt = "Stay immersive.",
          createdAt = 1L,
          updatedAt = 1L,
        ),
    )

    assertTrue(repository.byteDocuments.getValue("content://cards/astra.png").isNotEmpty())
  }

  @Test
  fun exportToUri_usesPrimaryAvatarFromMediaProfile() = runBlocking {
    val repository =
      FakeRoleCardDocumentRepository().apply {
        metadata["content://cards/export.png"] =
          RoleplayInteropDocumentMetadata(displayName = "export.png", mimeType = "image/png")
        byteDocuments["content://images/avatar.png"] =
          Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO8BzZQAAAAASUVORK5CYII="
          )
      }
    val useCase =
      ExportStRoleCardToUriUseCase(
        documentRepository = repository,
        exportStV2RoleCardUseCase = ExportStV2RoleCardUseCase(),
      )

    useCase.exportToUri(
      uri = "content://cards/export.png",
      role =
        RoleCard(
          id = "role-2",
          name = "Nova",
          summary = "Scout",
          systemPrompt = "Stay immersive.",
          mediaProfile =
            RoleMediaProfile(
              primaryAvatar =
                RoleMediaAsset(
                  id = "avatar-1",
                  kind = RoleMediaKind.PRIMARY_AVATAR,
                  uri = "content://images/avatar.png",
                  source = RoleMediaSource.LOCAL_PICKER,
                  createdAt = 1L,
                  updatedAt = 1L,
                )
            ),
          createdAt = 1L,
          updatedAt = 1L,
        ),
    )

    assertTrue(repository.byteDocuments.getValue("content://cards/export.png").isNotEmpty())
  }
}

private class FakeRoleCardDocumentRepository : RoleplayInteropDocumentRepository {
  val documents = mutableMapOf<String, String>()
  val byteDocuments = mutableMapOf<String, ByteArray>()
  val metadata = mutableMapOf<String, RoleplayInteropDocumentMetadata>()

  override suspend fun readText(uri: String): String {
    return documents[uri] ?: byteDocuments[uri]?.decodeToString() ?: error("Missing fake document for $uri")
  }

  override suspend fun writeText(uri: String, content: String) {
    documents[uri] = content
  }

  override suspend fun readBytes(uri: String): ByteArray {
    return byteDocuments[uri] ?: documents[uri]?.toByteArray() ?: error("Missing fake bytes for $uri")
  }

  override suspend fun writeBytes(uri: String, content: ByteArray) {
    byteDocuments[uri] = content
  }

  override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata {
    return metadata[uri] ?: RoleplayInteropDocumentMetadata(displayName = uri.substringAfterLast('/'))
  }
}

private class FakeImportContext : ContextWrapper(null) {
  private val rootDir =
    createTempDir(prefix = "st-import-test").apply {
      deleteOnExit()
    }

  override fun getFilesDir(): File = rootDir
}
