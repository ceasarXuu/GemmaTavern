package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat

class StV2RoleCardInteropUseCaseTest {
  private val importUseCase = ImportStV2RoleCardUseCase()
  private val exportUseCase = ExportStV2RoleCardUseCase()

  @Test
  fun import_builds_role_card_with_canonical_payloads() {
    val imported =
      importUseCase.importFromJson(
        rawJson =
          """
          {
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": {
              "name": "Iris Vale",
              "description": "Noir archivist",
              "personality": "Observant and dryly funny",
              "scenario": "Rain-soaked archive city",
              "first_mes": "Start with what you know.",
              "mes_example": "User: Help\nIris: Facts first.",
              "creator_notes": "test import",
              "system_prompt": "Stay in character.",
              "post_history_instructions": "Keep continuity.",
              "alternate_greetings": ["Start with what you know."],
              "tags": ["noir", "mystery"],
              "creator": "tester",
              "character_version": "3",
              "extensions": {"depth_prompt":{"depth":4}}
            }
          }
          """.trimIndent(),
        now = 1234L,
      )

    assertEquals("Iris Vale", imported.name)
    assertEquals("Noir archivist", imported.summary)
    assertEquals("chara_card_v2", imported.cardCore?.spec)
    assertEquals(RoleCardSourceFormat.ST_JSON, imported.interopState?.sourceFormat)
    assertEquals(1234L, imported.createdAt)
    assertTrue(imported.exampleDialogues.isNotEmpty())
  }

  @Test
  fun export_prefers_current_role_fields_when_serializing() {
    val json =
      exportUseCase.exportToJson(
        RoleCard(
          id = "role-1",
          name = "Captain Astra",
          summary = "Mission-first captain",
          systemPrompt = "Stay immersive.",
          personaDescription = "Calm and decisive.",
          worldSettings = "Deep-space survey mission.",
          openingLine = "Crew report.",
          exampleDialogues = listOf("User: Status?\nAstra: Stable."),
          tags = listOf("sci-fi", "captain"),
          createdAt = 1L,
          updatedAt = 2L,
        )
      )

    assertTrue(json.contains(""""spec": "chara_card_v2""""))
    assertTrue(json.contains(""""name": "Captain Astra""""))
    assertTrue(json.contains(""""description": "Mission-first captain""""))
    assertTrue(json.contains(""""first_mes": "Crew report.""""))
  }
}
