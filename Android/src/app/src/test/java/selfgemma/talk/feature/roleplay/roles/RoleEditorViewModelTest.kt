package selfgemma.talk.feature.roleplay.roles

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData

class RoleEditorViewModelTest {
  @Test
  fun toEditorUiState_preservesBuiltInFlagAcrossImportedRoleProjection() {
    val role =
      RoleCard(
        id = "builtin-role",
        stCard =
          StCharacterCard(
            spec = "chara_card_v2",
            spec_version = "2.0",
            name = "Builtin",
            data = StCharacterCardData(name = "Builtin", system_prompt = "prompt"),
          ),
        builtIn = true,
        interopState = RoleInteropState(sourceFormat = RoleCardSourceFormat.ST_PNG),
        createdAt = 1L,
        updatedAt = 1L,
      )

    val uiState = role.toEditorUiState(isNewRole = false, statusMessage = "imported")

    assertTrue(uiState.builtIn)
    assertFalse(uiState.isNewRole)
    assertTrue(uiState.importedFromStPng)
  }

  @Test
  fun toEditorUiState_projectsStAdvancedFieldsIntoNativeEditorState() {
    val role =
      RoleCard(
        id = "st-role",
        stCard =
          StCharacterCard(
            spec = "chara_card_v2",
            spec_version = "2.0",
            creatorcomment = "legacy notes",
            talkativeness = 0.65,
            fav = true,
            data =
              StCharacterCardData(
                name = "Tomoko",
                description = "awkward gamer",
                personality = "self-aware and sharp",
                scenario = "school rooftop",
                first_mes = "…hi",
                mes_example = "{{char}}: ...",
                creator_notes = "author notes",
                system_prompt = "stay in character",
                post_history_instructions = "keep continuity",
                alternate_greetings = listOf("hello", "hey"),
                creator = "tester",
                character_version = "v3",
                tags = listOf("school", "comedy"),
                character_book =
                  StCharacterBook(
                    name = "Tomoko lore",
                    scan_depth = 3,
                    token_budget = 128,
                    recursive_scanning = true,
                    entries =
                      listOf(
                        StCharacterBookEntry(
                          id = 7,
                          keys = listOf("yuu"),
                          content = "best friend",
                          insertion_order = 2,
                          enabled = true,
                          position = "before_char",
                        ),
                      ),
                  ),
              ),
          ),
        interopState =
          RoleInteropState(
            sourceFormat = RoleCardSourceFormat.ST_JSON,
            sourceSpec = "chara_card_v2",
            sourceSpecVersion = "2.0",
            compatibilityWarnings = listOf("unknown field preserved"),
          ),
        createdAt = 1L,
        updatedAt = 1L,
      )

    val uiState = role.toEditorUiState(isNewRole = false)

    assertEquals("Tomoko", uiState.name)
    assertEquals("awkward gamer", uiState.description)
    assertEquals("self-aware and sharp", uiState.personality)
    assertEquals("school rooftop", uiState.scenario)
    assertEquals("…hi", uiState.firstMessage)
    assertEquals("{{char}}: ...", uiState.messageExample)
    assertEquals("stay in character", uiState.systemPrompt)
    assertEquals("keep continuity", uiState.postHistoryInstructions)
    assertEquals("hello\nhey", uiState.alternateGreetingsText)
    assertEquals("author notes", uiState.creatorNotes)
    assertEquals("tester", uiState.creator)
    assertEquals("v3", uiState.characterVersion)
    assertEquals("school, comedy", uiState.tagsText)
    assertEquals("0.65", uiState.talkativenessText)
    assertTrue(uiState.fav)
    assertEquals(RoleCardSourceFormat.ST_JSON, uiState.sourceFormat)
    assertEquals(1, uiState.compatibilityWarnings.size)
    assertEquals("Tomoko lore", uiState.characterBook.name)
    assertEquals("3", uiState.characterBook.scanDepthText)
    assertEquals("128", uiState.characterBook.tokenBudgetText)
    assertTrue(uiState.characterBook.recursiveScanning)
    assertEquals(1, uiState.characterBook.entries.size)
    assertEquals("yuu", uiState.characterBook.entries.first().keysText)
  }
}
