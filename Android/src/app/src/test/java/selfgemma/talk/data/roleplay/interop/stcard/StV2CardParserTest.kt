package selfgemma.talk.data.roleplay.interop.stcard

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData

class StV2CardParserTest {
  private val parser = StV2CardParser()
  private val serializer = StV2CardSerializer()

  @Test
  fun parse_reads_core_fields_from_v2_json() {
    val parsed =
      parser.parse(
        """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "Seraphina",
            "description": "A gentle forest guardian.",
            "personality": "Warm and observant.",
            "scenario": "A moonlit healing grove.",
            "first_mes": "You finally woke up.",
            "mes_example": "User: Hello\nSeraphina: Welcome back.",
            "creator_notes": "Imported test card",
            "system_prompt": "Stay in character.",
            "post_history_instructions": "Prefer continuity.",
            "alternate_greetings": ["Hello there.", "You are safe now."],
            "tags": ["fantasy", "healer"],
            "creator": "tester",
            "character_version": "1.2",
            "x_prompt_hint": "keep the moonlit tone",
            "extensions": {"depth_prompt": {"depth": 4}},
            "character_book": {
              "name": "Lore",
              "entries": [
                {
                  "id": 1,
                  "keys": ["grove"],
                  "content": "The grove shifts with moonlight."
                }
              ]
            }
          }
        }
        """.trimIndent()
      )

    assertEquals("Seraphina", parsed.card.name)
    assertEquals("chara_card_v2", parsed.card.spec)
    assertEquals("Warm and observant.", parsed.card.personality)
    assertEquals(2, parsed.card.data?.alternate_greetings?.size)
    assertEquals(1, parsed.card.data?.character_book?.entries?.size)
    assertTrue(parsed.interopState.rawUnknownDataJson?.contains("x_prompt_hint") == true)
    assertTrue(parsed.interopState.rawCardJson?.contains("chara_card_v2") == true)
  }

  @Test
  fun export_preserves_unknown_fields_from_interop_state() {
    val json =
      selfgemma.talk.domain.roleplay.usecase.ExportStV2RoleCardUseCase().exportToJson(
        selfgemma.talk.domain.roleplay.model.RoleCard(
          id = "role-unknown",
          stCard =
            StCharacterCard(
              spec = "chara_card_v2",
              spec_version = "2.0",
              name = "Nova",
              data =
                StCharacterCardData(
                  name = "Nova",
                  description = "Scout",
                  extensions = JsonObject().apply { addProperty("world", "baseline") },
                ),
            ),
          interopState =
            selfgemma.talk.domain.roleplay.model.RoleInteropState(
              rawUnknownTopLevelJson = """{"x_top":"kept"}""",
              rawUnknownDataJson = """{"x_data":"kept"}""",
              rawUnknownExtensionsJson = """{"x_ext":"kept"}""",
            ),
          createdAt = 1L,
          updatedAt = 1L,
        )
      )

    assertTrue(json.contains(""""x_top": "kept""""))
    assertTrue(json.contains(""""x_data": "kept""""))
    assertTrue(json.contains(""""x_ext": "kept""""))
  }

  @Test
  fun serialize_writes_v2_shape_with_legacy_mirror_fields() {
    val json =
      serializer.serialize(
        StCharacterCard(
          spec = "chara_card_v2",
          spec_version = "2.0",
          name = "Captain Astra",
          description = "Mission-first captain.",
          personality = "Calm and decisive.",
          scenario = "Deep-space survey mission.",
          first_mes = "Crew report.",
          mes_example = "User: Status?\nAstra: Stable.",
          data =
            StCharacterCardData(
              name = "Captain Astra",
              description = "Mission-first captain.",
              personality = "Calm and decisive.",
              scenario = "Deep-space survey mission.",
              first_mes = "Crew report.",
              mes_example = "User: Status?\nAstra: Stable.",
              creator_notes = "seed",
              system_prompt = "Stay immersive.",
              post_history_instructions = "Keep continuity.",
              alternate_greetings = listOf("Crew report.", "What changed on my watch?"),
              tags = listOf("sci-fi", "captain"),
              creator = "selfgemma",
              character_version = "2.0",
              character_book =
                StCharacterBook(
                  name = "Ship Notes",
                  entries =
                    listOf(
                      StCharacterBookEntry(
                        id = 1,
                        keys = listOf("Meridian"),
                        content = "The Meridian is an aging survey ship."
                      )
                    )
                ),
              extensions = JsonObject().apply {
                add("depth_prompt", JsonObject().apply { addProperty("depth", 4) })
              },
            ),
        )
      )

    assertTrue(json.contains(""""spec": "chara_card_v2""""))
    assertTrue(json.contains(""""spec_version": "2.0""""))
    assertTrue(json.contains(""""name": "Captain Astra""""))
    assertTrue(json.contains(""""first_mes": "Crew report.""""))
    assertTrue(json.contains(""""system_prompt": "Stay immersive.""""))
    assertTrue(json.contains(""""alternate_greetings""""))
  }
}
