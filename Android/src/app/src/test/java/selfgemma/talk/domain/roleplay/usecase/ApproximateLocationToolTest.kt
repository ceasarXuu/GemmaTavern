package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproximateLocationToolTest {
  @Test
  fun createToolProvider_returnsNullWhenLocationToolsUnavailable() {
    val tool =
      ApproximateLocationTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { false }
      }

    val provider =
      tool.createToolProvider(
        pendingMessage = pendingToolMessage("你在哪"),
        collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2"),
      )

    assertNull(provider)
  }

  @Test
  fun getApproximateLocation_recordsInvocationAndExternalFact() {
    val tool =
      ApproximateLocationTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { true }
        snapshotProvider = {
          ApproximateLocationSnapshot(
            displayName = "Shanghai, China",
            latitude = 31.23,
            longitude = 121.47,
            locality = "Shanghai",
            adminArea = "Shanghai",
            countryName = "China",
            countryCode = "CN",
            accuracyMeters = 1200,
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingToolMessage("你现在在哪"),
        collector = collector,
      )

    val result =
      (toolSet as ApproximateLocationTool.ApproximateLocationToolSetAccess)
        .getApproximateLocationForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals("Shanghai, China", result["displayName"])
    assertEquals("getApproximateLocation", invocations.single().toolName)
    assertTrue(invocations.single().resultSummary!!.contains("Shanghai"))
    assertTrue(externalFacts.single().content.contains("Shanghai"))
  }

  @Test
  fun roundCoordinate_roundsToTwoDecimals() {
    assertEquals(31.23, ApproximateLocationSnapshot.roundCoordinate(31.22987), 0.0)
  }
}
