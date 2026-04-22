package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSnapshotToolTest {
  @Test
  fun createToolProvider_returnsNullWhenCalendarToolsUnavailable() {
    val tool =
      CalendarSnapshotTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { false }
      }

    val provider =
      tool.createToolProvider(
        pendingMessage = pendingToolMessage("今天还有什么安排"),
        collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2"),
      )

    assertNull(provider)
  }

  @Test
  fun getCalendarSnapshot_recordsInvocationAndExternalFact() {
    val tool =
      CalendarSnapshotTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { true }
        snapshotProvider = {
          CalendarSnapshot(
            windowStart = "2026-04-22 09:00",
            windowEnd = "2026-04-25 09:00",
            events =
              listOf(
                CalendarEventSnapshot(
                  title = "Dentist",
                  startAt = "2026-04-22 15:30",
                  endAt = "2026-04-22 16:30",
                  isAllDay = false,
                  location = "Clinic",
                ),
              ),
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingToolMessage("今天还有安排吗"),
        collector = collector,
      )

    val result =
      (toolSet as CalendarSnapshotTool.CalendarSnapshotToolSetAccess).getCalendarSnapshotForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals(1, result["eventCount"])
    assertEquals("getCalendarSnapshot", invocations.single().toolName)
    assertTrue(externalFacts.single().content.contains("Dentist"))
  }
}
