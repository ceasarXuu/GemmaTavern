package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaQueryToolTest {
  @Test
  fun queryWikipedia_recordsInvocationAndExternalFact() {
    val tool =
      WikipediaQueryTool(ContextWrapper(null)).apply {
        localeProvider = { Locale.ENGLISH }
        lookupProvider = { _, _ ->
          WikipediaLookupResult(
            title = "Gemma",
            summary = "Gemma is a fictional innkeeper.",
            pageUrl = "https://example.com/gemma",
            languageCode = "en",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingToolMessage("Gemma 是谁"),
        collector = collector,
      )

    val result =
      (toolSet as WikipediaQueryTool.WikipediaQueryToolSetAccess).queryWikipediaForTest("Gemma")
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals("Gemma", result["title"])
    assertEquals("queryWikipedia", invocations.single().toolName)
    assertTrue(externalFacts.single().content.contains("fictional innkeeper"))
  }

  @Test
  fun sanitizeWikipediaSnippet_removesTags() {
    assertEquals(
      "Gemma is great",
      WikipediaLookupResult.sanitizeWikipediaSnippet("<span>Gemma</span> is great"),
    )
  }
}
