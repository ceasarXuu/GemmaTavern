package selfgemma.talk.domain.roleplay.model

import org.junit.Test
import org.junit.Assert.assertEquals

class RoleplayModelsTest {
  @Test
  fun constructor_mergesFallbackFieldsIntoPartialCanonicalCard() {
    val role =
      RoleCard(
        id = "role-1",
        name = "Fallback Name",
        summary = "Fallback summary",
        systemPrompt = "Fallback prompt",
        personaDescription = "Fallback persona",
        worldSettings = "Fallback world",
        openingLine = "Fallback opening",
        exampleDialogues = listOf("Ex 1", "Ex 2"),
        tags = listOf("tag-a", "tag-b"),
        cardCore = StCharacterCard(name = "Canonical Name"),
        createdAt = 1L,
        updatedAt = 1L,
      )

    assertEquals("Canonical Name", role.name)
    assertEquals("Fallback summary", role.summary)
    assertEquals("Fallback prompt", role.systemPrompt)
    assertEquals("Fallback persona", role.personaDescription)
    assertEquals("Fallback world", role.worldSettings)
    assertEquals("Fallback opening", role.openingLine)
    assertEquals(listOf("Ex 1", "Ex 2"), role.exampleDialogues)
    assertEquals(listOf("tag-a", "tag-b"), role.tags)
    assertEquals("Fallback prompt", role.stCard.data?.system_prompt)
  }
}
