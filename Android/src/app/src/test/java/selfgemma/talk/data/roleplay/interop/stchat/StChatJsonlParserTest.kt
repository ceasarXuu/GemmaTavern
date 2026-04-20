package selfgemma.talk.data.roleplay.interop.stchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StChatJsonlParserTest {
  private val parser = StChatJsonlParser()
  private val serializer = StChatJsonlSerializer()

  @Test
  fun parse_reads_header_and_messages() {
    val parsed =
      parser.parse(
        """
        {"chat_metadata":{"integrity":"abc"},"user_name":"You","character_name":"Iris"}
        {"name":"You","is_user":true,"is_system":false,"send_date":"2026-01-01T00:00:00Z","mes":"Hello","extra":{"mood":"calm"}}
        {"name":"Iris","is_user":false,"is_system":false,"send_date":"2026-01-01T00:00:10Z","mes":"Start with facts.","extra":{"token_count":10},"swipes":["Start with facts.","We begin with evidence."],"swipe_id":1}
        """.trimIndent()
      )

    assertTrue(parsed.chatMetadataJson.contains("integrity"))
    assertEquals("You", parsed.userName)
    assertEquals("Iris", parsed.characterName)
    assertEquals(2, parsed.messages.size)
    assertEquals("Hello", parsed.messages[0].text)
    assertTrue(parsed.messages[1].swipesJson?.contains("evidence") == true)
  }

  @Test
  fun serialize_writes_jsonl_shape() {
    val jsonl =
      serializer.serialize(
        chatMetadataJson = """{"integrity":"abc"}""",
        userName = "You",
        characterName = "Iris",
        messages =
          listOf(
            StChatMessage(
              name = "You",
              isUser = true,
              isSystem = false,
              sendDate = "2026-01-01T00:00:00Z",
              text = "Hello",
            ),
            StChatMessage(
              name = "Iris",
              isUser = false,
              isSystem = false,
              sendDate = "2026-01-01T00:00:10Z",
              text = "Start with facts.",
              swipesJson = """["Start with facts.","We begin with evidence."]""",
              swipeId = 1,
            )
          ),
      )

    val lines = jsonl.lines()
    assertTrue(lines.first().contains("chat_metadata"))
    assertTrue(lines[1].contains(""""is_user":true"""))
    assertTrue(lines[2].contains(""""swipe_id":1"""))
  }
}
