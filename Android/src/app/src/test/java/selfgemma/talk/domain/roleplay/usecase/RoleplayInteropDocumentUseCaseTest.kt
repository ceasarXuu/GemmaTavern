package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class RoleplayInteropDocumentUseCaseTest {
  private val repository = FakeRoleplayInteropDocumentRepository()

  @Test
  fun import_role_card_from_uri_reads_document_repository() = runBlocking {
    repository.documents["content://cards/iris.json"] =
      """
      {
        "spec": "chara_card_v2",
        "spec_version": "2.0",
        "data": {
          "name": "Iris",
          "description": "Archivist",
          "personality": "Sharp",
          "scenario": "Rainy city",
          "first_mes": "Start with what you know.",
          "mes_example": "User: Hi\nIris: Facts first.",
          "creator_notes": "imported",
          "system_prompt": "Stay in character.",
          "post_history_instructions": "Maintain continuity.",
          "alternate_greetings": ["Start with what you know."],
          "tags": ["noir"]
        }
      }
      """.trimIndent()

    val role =
      ImportStV2RoleCardFromUriUseCase(repository, ImportStV2RoleCardUseCase())
        .importFromUri("content://cards/iris.json", now = 7L)

    assertEquals("Iris", role.name)
    assertEquals(7L, role.createdAt)
  }

  @Test
  fun export_chat_jsonl_to_uri_writes_document_repository() = runBlocking {
    ExportStChatJsonlToUriUseCase(repository, ExportStChatJsonlUseCase())
      .exportToUri(
        uri = "content://chats/session-1.jsonl",
        chatMetadataJson = """{"integrity":"abc"}""",
        userName = "User",
        roleName = "Iris",
        messages =
          listOf(
            Message(
              id = "m1",
              sessionId = "s1",
              seq = 1,
              side = MessageSide.USER,
              status = MessageStatus.COMPLETED,
              content = "Hello",
              createdAt = 1L,
              updatedAt = 1L,
            )
          ),
      )

    val output = repository.documents["content://chats/session-1.jsonl"].orEmpty()
    assertTrue(output.contains("chat_metadata"))
    assertTrue(output.contains("Hello"))
  }

  @Test
  fun export_role_card_to_uri_writes_document_repository() = runBlocking {
    ExportStV2RoleCardToUriUseCase(repository, ExportStV2RoleCardUseCase())
      .exportToUri(
        uri = "content://cards/astra.json",
        role =
          RoleCard(
            id = "r1",
            name = "Astra",
            summary = "Captain",
            systemPrompt = "Stay immersive.",
            createdAt = 1L,
            updatedAt = 1L,
          ),
      )

    val output = repository.documents["content://cards/astra.json"].orEmpty()
    assertTrue(output.contains("chara_card_v2"))
    assertTrue(output.contains("Astra"))
  }

  private class FakeRoleplayInteropDocumentRepository : RoleplayInteropDocumentRepository {
    val documents = linkedMapOf<String, String>()
    val byteDocuments = linkedMapOf<String, ByteArray>()

    override suspend fun readText(uri: String): String {
      return documents[uri] ?: error("No fake document for uri=$uri")
    }

    override suspend fun writeText(uri: String, content: String) {
      documents[uri] = content
    }

    override suspend fun readBytes(uri: String): ByteArray {
      return byteDocuments[uri] ?: documents[uri]?.toByteArray() ?: error("No fake document for uri=$uri")
    }

    override suspend fun writeBytes(uri: String, content: ByteArray) {
      byteDocuments[uri] = content
    }

    override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata {
      return RoleplayInteropDocumentMetadata(displayName = uri.substringAfterLast('/'))
    }
  }
}
