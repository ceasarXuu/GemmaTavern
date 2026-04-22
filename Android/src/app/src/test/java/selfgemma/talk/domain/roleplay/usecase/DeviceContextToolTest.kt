package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StUserProfile

class DeviceContextToolTest {
  @Test
  fun getDeviceContext_recordsInvocationAndExternalFact() {
    val tool =
      DeviceContextTool(ContextWrapper(null)).apply {
        snapshotProvider = {
          DeviceContextSnapshot(
            localeTag = "zh-CN",
            localeDisplayName = "中文 (中国)",
            languageCode = "zh",
            regionCode = "CN",
            weekday = "wednesday",
            weekdayLocalized = "星期三",
            uses24HourClock = true,
            hourCycle = "24h",
            timeZoneId = "Asia/Shanghai",
            currentDate = "2026-04-22",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingContextMessage("你现在的地区设置是什么？"),
        collector = collector,
      )

    val result =
      (toolSet as DeviceContextTool.DeviceContextToolSetAccess).getDeviceContextForTest()
    val invocation = collector.snapshotInvocations().single()
    val externalFact = collector.snapshotExternalFacts().single()

    assertEquals("zh-CN", result["localeTag"])
    assertEquals("星期三", result["weekdayLocalized"])
    assertEquals("24h", result["hourCycle"])
    assertEquals("getDeviceContext", invocation.toolName)
    assertTrue(invocation.resultSummary!!.contains("zh-CN"))
    assertTrue(externalFact.content.contains("中文 (中国)"))
    assertTrue(externalFact.ephemeral)
  }
}

private fun pendingContextMessage(input: String): PendingRoleplayMessage {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Context",
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
