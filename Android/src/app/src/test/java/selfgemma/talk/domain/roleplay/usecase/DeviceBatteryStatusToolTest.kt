package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StUserProfile

class DeviceBatteryStatusToolTest {
  @Test
  fun getDeviceBatteryStatus_recordsInvocationAndExternalFact() {
    val tool =
      DeviceBatteryStatusTool(ContextWrapper(null)).apply {
        snapshotProvider = {
          DeviceBatteryStatusSnapshot(
            batteryPercent = 42,
            isCharging = true,
            chargeStatus = "charging",
            chargeSource = "usb",
            isBatterySaverEnabled = false,
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingBatteryMessage("手机还撑得住吗？"),
        collector = collector,
      )

    val result =
      (toolSet as DeviceBatteryStatusTool.DeviceBatteryStatusToolSetAccess).getDeviceBatteryStatusForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals(42, result["batteryPercent"])
    assertEquals(true, result["isCharging"])
    assertEquals("charging", result["chargeStatus"])
    assertEquals("getDeviceBatteryStatus", invocations.single().toolName)
    assertTrue(invocations.single().resultSummary!!.contains("Battery 42%"))
    assertEquals(1, externalFacts.size)
    assertTrue(externalFacts.single().content.contains("42%"))
    assertTrue(externalFacts.single().ephemeral)
  }

  @Test
  fun formatChargeStatusAndSource_coverKnownValues() {
    assertEquals("charging", DeviceBatteryStatusSnapshot.formatChargeStatus(BatteryManager.BATTERY_STATUS_CHARGING))
    assertEquals("full", DeviceBatteryStatusSnapshot.formatChargeStatus(BatteryManager.BATTERY_STATUS_FULL))
    assertEquals("usb", DeviceBatteryStatusSnapshot.formatChargeSource(BatteryManager.BATTERY_PLUGGED_USB))
    assertEquals("none", DeviceBatteryStatusSnapshot.formatChargeSource(0))
  }
}

private fun pendingBatteryMessage(input: String): PendingRoleplayMessage {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Battery",
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
