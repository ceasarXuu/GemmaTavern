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
            lunarDate = "三月初六",
            time24h = "18:07",
            hour = 18,
            minute = 7,
            timeZoneId = "Asia/Shanghai",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingMessage("随便问一句"),
        collector = collector,
      )

    val result =
      (toolSet as DeviceSystemTimeTool.DeviceSystemTimeToolSetAccess).getDeviceSystemTimeForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals("2026-04-22", result["gregorianDate"])
    assertEquals("三月初六", result["lunarDate"])
    assertEquals(1, invocations.size)
    assertEquals("getDeviceSystemTime", invocations.single().toolName)
    assertTrue(invocations.single().resultSummary!!.contains("2026-04-22 18:07"))
    assertEquals(1, externalFacts.size)
    assertTrue(externalFacts.single().content.contains("Asia/Shanghai"))
    assertTrue(externalFacts.single().ephemeral)
  }

  @Test
  fun formatLunarDate_rendersLeapMonthPrefix() {
    assertEquals("闰三月初五", DeviceSystemTimeSnapshot.formatLunarDate(month = 2, day = 5, isLeapMonth = true))
    assertEquals("冬月三十", DeviceSystemTimeSnapshot.formatLunarDate(month = 10, day = 30, isLeapMonth = false))
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
