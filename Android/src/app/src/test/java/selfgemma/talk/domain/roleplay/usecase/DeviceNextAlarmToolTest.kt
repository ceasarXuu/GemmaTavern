package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StUserProfile

class DeviceNextAlarmToolTest {
  @Test
  fun getNextAlarmHint_recordsInvocationAndExternalFact() {
    val tool =
      DeviceNextAlarmTool(ContextWrapper(null)).apply {
        snapshotProvider = {
          DeviceNextAlarmSnapshot(
            hasNextAlarm = true,
            alarmDate = "2026-04-23",
            alarmTime = "07:30",
            minutesUntilAlarm = 810,
            isWithin24Hours = true,
            timeZoneId = "Asia/Shanghai",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingAlarmMessage("我明天几点得起？"),
        collector = collector,
      )

    val result =
      (toolSet as DeviceNextAlarmTool.DeviceNextAlarmToolSetAccess).getNextAlarmHintForTest()
    val invocation = collector.snapshotInvocations().single()
    val externalFact = collector.snapshotExternalFacts().single()

    assertEquals(true, result["hasNextAlarm"])
    assertEquals("2026-04-23", result["alarmDate"])
    assertEquals("getNextAlarmHint", invocation.toolName)
    assertTrue(invocation.resultSummary!!.contains("07:30"))
    assertTrue(externalFact.content.contains("810"))
    assertTrue(externalFact.ephemeral)
  }

  @Test
  fun fromTriggerTime_formatsLocalAlarmHint() {
    val now = ZonedDateTime.of(2026, 4, 22, 18, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    val snapshot =
      DeviceNextAlarmSnapshot.fromTriggerTime(
        triggerAtMillis = now.plusHours(10).toInstant().toEpochMilli(),
        zoneId = "Asia/Shanghai",
        locale = Locale.CHINA,
        is24HourClock = true,
        now = now,
      )

    assertEquals(true, snapshot.hasNextAlarm)
    assertEquals("2026-04-23", snapshot.alarmDate)
    assertEquals("04:00", snapshot.alarmTime)
    assertEquals(600, snapshot.minutesUntilAlarm)
    assertTrue(snapshot.isWithin24Hours)
  }
}

private fun pendingAlarmMessage(input: String): PendingRoleplayMessage {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Alarm",
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
