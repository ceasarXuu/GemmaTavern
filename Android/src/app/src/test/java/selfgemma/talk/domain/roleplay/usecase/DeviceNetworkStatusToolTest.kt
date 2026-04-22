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

class DeviceNetworkStatusToolTest {
  @Test
  fun getDeviceNetworkStatus_recordsInvocationAndExternalFact() {
    val tool =
      DeviceNetworkStatusTool(ContextWrapper(null)).apply {
        snapshotProvider = {
          DeviceNetworkStatusSnapshot(
            isConnected = true,
            transport = "wifi",
            isValidated = true,
            isMetered = false,
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingNetworkMessage("你现在联网了吗？"),
        collector = collector,
      )

    val result =
      (toolSet as DeviceNetworkStatusTool.DeviceNetworkStatusToolSetAccess).getDeviceNetworkStatusForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals(true, result["isConnected"])
    assertEquals("wifi", result["transport"])
    assertEquals("getDeviceNetworkStatus", invocations.single().toolName)
    assertTrue(invocations.single().resultSummary!!.contains("wifi"))
    assertEquals(1, externalFacts.size)
    assertTrue(externalFacts.single().content.contains("online"))
    assertTrue(externalFacts.single().ephemeral)
  }

  @Test
  fun formatTransport_returnsNoneWhenCapabilitiesMissing() {
    assertEquals("none", DeviceNetworkStatusSnapshot.formatTransport(null))
  }
}

private fun pendingNetworkMessage(input: String): PendingRoleplayMessage {
  val now = System.currentTimeMillis()
  val session =
    Session(
      id = "session-1",
      roleId = "role-1",
      title = "Network",
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
