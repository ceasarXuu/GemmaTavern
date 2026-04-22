package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StUserProfile

class DeviceSystemTimeToolTest {
  @Test
  fun getDeviceSystemTime_recordsInvocationAndExternalFact() {
    val tool =
      DeviceSystemTimeTool().apply {
        snapshotProvider = {
          DeviceSystemTimeSnapshot(
            gregorianDate = "2026-04-22",
            weekdayLocalized = "\u661f\u671f\u4e8c",
            weekdayIso = 2,
            lunarDate = "\u4e09\u6708\u521d\u516d",
            time24h = "18:07",
            hour = 18,
            minute = 7,
            epochMillis = 1_713_810_420_000L,
            timeZoneId = "Asia/Shanghai",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingMessage("Tell me the real device time."),
        collector = collector,
      )

    val result =
      (toolSet as DeviceSystemTimeTool.DeviceSystemTimeToolSetAccess).getDeviceSystemTimeForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals("2026-04-22", result["gregorianDate"])
    assertEquals("\u661f\u671f\u4e8c", result["weekdayLocalized"])
    assertEquals(2, result["weekdayIso"])
    assertEquals("\u4e09\u6708\u521d\u516d", result["lunarDate"])
    assertEquals(1, invocations.size)
    assertEquals("getDeviceSystemTime", invocations.single().toolName)
    assertTrue(
      invocations.single().resultSummary!!.contains("2026-04-22 \u661f\u671f\u4e8c 18:07"),
    )
    assertTrue(invocations.single().resultSummary!!.contains("\u519c\u5386\u4e09\u6708\u521d\u516d"))
    assertEquals(1, externalFacts.size)
    assertEquals("device.system_time", externalFacts.single().factType)
    assertTrue(externalFacts.single().content.contains("Asia/Shanghai"))
    assertTrue(externalFacts.single().ephemeral)
  }

  @Test
  fun formatLunarDate_rendersLeapMonthPrefix() {
    assertEquals(
      "\u95f0\u4e09\u6708\u521d\u4e94",
      DeviceSystemTimeSnapshot.formatLunarDate(month = 2, day = 5, isLeapMonth = true),
    )
    assertEquals(
      "\u51ac\u6708\u4e09\u5341",
      DeviceSystemTimeSnapshot.formatLunarDate(month = 10, day = 30, isLeapMonth = false),
    )
  }

  @Test
  fun formatWeekday_returnsLocalizedWeekday() {
    assertEquals("\u661f\u671f\u4e00", DeviceSystemTimeSnapshot.formatWeekday(dayOfWeekIso = 1))
    assertEquals("\u661f\u671f\u56db", DeviceSystemTimeSnapshot.formatWeekday(dayOfWeekIso = 4))
  }
}

private fun pendingMessage(input: String): PendingRoleplayMessage {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Time",
      activeModelId = "model-1",
      createdAt = now,
      updatedAt = now,
      lastMessageAt = now,
      sessionUserProfile = StUserProfile(),
    )
  val userMessage =
    Message(
      id = "user-1",
      sessionId = session.id,
      seq = 1,
      side = MessageSide.USER,
      content = input,
      status = MessageStatus.COMPLETED,
      createdAt = now,
      updatedAt = now,
    )
  val assistantSeed =
    Message(
      id = "assistant-2",
      sessionId = session.id,
      seq = 2,
      side = MessageSide.ASSISTANT,
      status = MessageStatus.STREAMING,
      accepted = false,
      isCanonical = false,
      content = "",
      parentMessageId = userMessage.id,
      regenerateGroupId = userMessage.id,
      createdAt = now,
      updatedAt = now,
    )
  return PendingRoleplayMessage(
    session = session,
    userMessages = listOf(userMessage),
    assistantSeed = assistantSeed,
    combinedUserInput = input,
  )
}
