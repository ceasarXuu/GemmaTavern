package selfgemma.talk.feature.roleplay.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

class RoleplayChatScreenTest {
  @Test
  fun `text only send can reuse multimodal session`() {
    assertTrue(
      canReuseRoleplayModelSession(
        supportImage = true,
        supportAudio = true,
        needsImage = false,
        needsAudio = false,
      )
    )
  }

  @Test
  fun `image send cannot reuse text only session`() {
    assertFalse(
      canReuseRoleplayModelSession(
        supportImage = false,
        supportAudio = false,
        needsImage = true,
        needsAudio = false,
      )
    )
  }

  @Test
  fun `audio send can reuse fully capable session`() {
    assertTrue(
      canReuseRoleplayModelSession(
        supportImage = true,
        supportAudio = true,
        needsImage = false,
        needsAudio = true,
      )
    )
  }

  @Test
  fun `audio send cannot reuse image only session`() {
    assertFalse(
      canReuseRoleplayModelSession(
        supportImage = true,
        supportAudio = false,
        needsImage = false,
        needsAudio = true,
      )
    )
  }

  @Test
  fun `text send inherits historical image requirement`() {
    val requirements =
      resolveRoleplaySendRequirements(
        messages = listOf(),
        conversationMessages =
          listOf(
            conversationMessage(
              id = "historical-image",
              kind = MessageKind.IMAGE,
            )
          ),
      )

    assertTrue(requirements.needsImage)
    assertFalse(requirements.needsAudio)
  }

  @Test
  fun `text send ignores failed historical media messages`() {
    val requirements =
      resolveRoleplaySendRequirements(
        messages = listOf(),
        conversationMessages =
          listOf(
            conversationMessage(
              id = "failed-image",
              kind = MessageKind.IMAGE,
              status = MessageStatus.FAILED,
            )
          ),
      )

    assertFalse(requirements.needsImage)
    assertFalse(requirements.needsAudio)
  }

  @Test
  fun `downloaded roleplay model warms text session when no instance exists`() {
    assertEquals(
      RoleplayWarmupAction.TEXT_ONLY,
      resolveRoleplayWarmupAction(
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        isInitializing = false,
        hasInstance = false,
        supportImage = false,
        supportAudio = false,
        needsImage = false,
        needsAudio = false,
      ),
    )
  }

  @Test
  fun `historical image chat warms multimodal session when current instance lacks image support`() {
    assertEquals(
      RoleplayWarmupAction.MULTIMODAL,
      resolveRoleplayWarmupAction(
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        isInitializing = false,
        hasInstance = true,
        supportImage = false,
        supportAudio = false,
        needsImage = true,
        needsAudio = false,
      ),
    )
  }

  @Test
  fun `historical multimodal chat skips warmup when current instance already matches requirements`() {
    assertEquals(
      RoleplayWarmupAction.NONE,
      resolveRoleplayWarmupAction(
        downloadStatus = ModelDownloadStatusType.SUCCEEDED,
        isInitializing = false,
        hasInstance = true,
        supportImage = true,
        supportAudio = true,
        needsImage = true,
        needsAudio = true,
      ),
    )
  }

  @Test
  fun `multimodal send queues immediately and warms multimodal session when current session lacks support`() {
    assertEquals(
      RoleplaySendExecutionPlan(
        queueImmediately = true,
        warmupAction = RoleplayWarmupAction.MULTIMODAL,
      ),
      resolveRoleplaySendExecutionPlan(
        needsImage = true,
        needsAudio = false,
        hasReusableMultimodalSession = false,
        hasInitializedSession = true,
      ),
    )
  }

  @Test
  fun `multimodal send queues immediately without warmup when current session is already reusable`() {
    assertEquals(
      RoleplaySendExecutionPlan(
        queueImmediately = true,
        warmupAction = RoleplayWarmupAction.NONE,
      ),
      resolveRoleplaySendExecutionPlan(
        needsImage = false,
        needsAudio = true,
        hasReusableMultimodalSession = true,
        hasInitializedSession = true,
      ),
    )
  }

  @Test
  fun `plain text send waits for text warmup when no session exists`() {
    assertEquals(
      RoleplaySendExecutionPlan(
        queueImmediately = false,
        warmupAction = RoleplayWarmupAction.TEXT_ONLY,
      ),
      resolveRoleplaySendExecutionPlan(
        needsImage = false,
        needsAudio = false,
        hasReusableMultimodalSession = false,
        hasInitializedSession = false,
      ),
    )
  }

  private fun conversationMessage(
    id: String,
    kind: MessageKind,
    status: MessageStatus = MessageStatus.COMPLETED,
  ): Message {
    val now = System.currentTimeMillis()
    return Message(
      id = id,
      sessionId = "session-1",
      seq = 1,
      side = MessageSide.USER,
      kind = kind,
      status = status,
      content = "",
      createdAt = now,
      updatedAt = now,
    )
  }
}
