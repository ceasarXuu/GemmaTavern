package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

class StChatJsonlInteropUseCaseTest {
  private val importUseCase = ImportStChatJsonlUseCase()
  private val exportUseCase = ExportStChatJsonlUseCase()

  @Test
  fun import_maps_st_messages_into_domain_messages() {
    val imported =
      importUseCase.importFromJsonl(
        sessionId = "session-1",
        rawJsonl =
          """
          {"chat_metadata":{"integrity":"abc"}}
          {"name":"User","is_user":true,"is_system":false,"send_date":"2026-01-01T00:00:00Z","mes":"Hello","extra":{"mood":"calm"}}
          {"name":"Iris","is_user":false,"is_system":false,"send_date":"2026-01-01T00:00:10Z","mes":"Start with facts.","extra":{"token_count":10}}
          """.trimIndent(),
        now = 100L,
      )

    assertTrue(imported.chatMetadataJson.contains("integrity"))
    assertEquals(2, imported.messages.size)
    assertEquals(MessageSide.USER, imported.messages[0].side)
    assertEquals(MessageSide.ASSISTANT, imported.messages[1].side)
    assertTrue(imported.messages[1].metadataJson?.contains("token_count") == true)
  }

  @Test
  fun export_rehydrates_jsonl_from_domain_messages() {
    val jsonl =
      exportUseCase.exportToJsonl(
        chatMetadataJson = """{"integrity":"xyz"}""",
        userName = "User",
        roleName = "Iris",
        messages =
          listOf(
            Message(
              id = "m1",
              sessionId = "session-1",
              seq = 1,
              side = MessageSide.USER,
              status = MessageStatus.COMPLETED,
              content = "Hello",
              createdAt = 1L,
              updatedAt = 1L,
            ),
            Message(
              id = "m2",
              sessionId = "session-1",
              seq = 2,
              side = MessageSide.ASSISTANT,
              status = MessageStatus.COMPLETED,
              content = "Start with facts.",
              createdAt = 2L,
              updatedAt = 2L,
            )
          ),
      )

    assertTrue(jsonl.contains("chat_metadata"))
    assertTrue(jsonl.contains(""""name""""))
    assertTrue(jsonl.contains("User"))
    assertTrue(jsonl.contains("Iris"))
    assertTrue(jsonl.contains("Start with facts."))
  }
}
