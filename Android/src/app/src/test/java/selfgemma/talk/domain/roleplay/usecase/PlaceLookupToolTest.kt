package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceLookupToolTest {
  @Test
  fun placeLookup_recordsInvocationAndExternalFact() {
    val tool =
      PlaceLookupTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        locationBiasProvider = { null }
        lookupProvider = { _, _ ->
          PlaceLookupResult(
            displayName = "The Bund, Shanghai, China",
            latitude = 31.24,
            longitude = 121.49,
            addressSummary = "Shanghai, China",
            category = "tourism",
            type = "attraction",
            mapUrl = "https://www.openstreetmap.org/example",
            biasUsed = false,
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingToolMessage("外滩在哪"),
        collector = collector,
      )

    val result =
      (toolSet as PlaceLookupTool.PlaceLookupToolSetAccess)
        .placeLookupOrMapContextForTest("The Bund")
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals("The Bund, Shanghai, China", result["displayName"])
    assertEquals("placeLookupOrMapContext", invocations.single().toolName)
    assertTrue(externalFacts.single().content.contains("OpenStreetMap"))
  }
}
