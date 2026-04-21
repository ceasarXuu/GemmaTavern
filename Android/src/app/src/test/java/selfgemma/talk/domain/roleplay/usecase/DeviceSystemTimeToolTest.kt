package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StUserProfile

class DeviceSystemTimeToolTest {
  @Test
  fun maybeExecute_returnsInvocationAndExternalFactForTimeQuery() {
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

    val result =
      kotlinx.coroutines.runBlocking {
        tool.maybeExecute(
          request =
            RoleplayToolExecutionRequest(
              pendingMessage = pendingMessage("现在几点了，顺便告诉我农历。"),
              model = testModel(),
              enableStreamingOutput = false,
              isStopRequested = { false },
            ),
          stepIndex = 0,
        )
      }

    assertNotNull(result)
    assertEquals("get_device_system_time", result!!.toolInvocation.toolName)
    assertTrue(result.toolInvocation.resultSummary!!.contains("2026-04-22 18:07"))
    assertTrue(result.toolInvocation.resultSummary!!.contains("农历三月初六"))
    assertEquals(1, result.externalFacts.size)
    assertTrue(result.externalFacts.single().content.contains("Asia/Shanghai"))
    assertTrue(result.externalFacts.single().ephemeral)
  }

  @Test
  fun isTimeQuery_requiresCurrentTimeIntent() {
    val tool = DeviceSystemTimeTool()

    assertTrue(tool.isTimeQuery("当前系统时间是多少？"))
    assertTrue(tool.isTimeQuery("今天农历几月几号"))
    assertTrue(tool.isTimeQuery("what time is it right now"))
    assertFalse(tool.isTimeQuery("我们没有多少时间了"))
    assertFalse(tool.isTimeQuery("把时间线整理一下"))
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

private fun testModel(): Model {
  return Model(
    name = "model-1",
    downloadFileName = "model.tflite",
  ).apply {
    instance = Any()
  }
}
