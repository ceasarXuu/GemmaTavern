package selfgemma.talk.feature.roleplay.chat

import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.llmchat.LlmModelInstance

internal data class RoleplaySendRequirements(
  val primaryTextInput: String? = null,
  val needsImage: Boolean = false,
  val needsAudio: Boolean = false,
)

internal enum class RoleplayWarmupAction {
  NONE,
  TEXT_ONLY,
  MULTIMODAL,
}

internal data class RoleplaySendExecutionPlan(
  val queueImmediately: Boolean,
  val warmupAction: RoleplayWarmupAction,
)

internal fun resolveRoleplaySendRequirements(
  messages: List<ChatMessage>,
  conversationMessages: List<Message> = emptyList(),
): RoleplaySendRequirements {
  val historicalNeedsImage =
    conversationMessages.any { message ->
      message.kind == MessageKind.IMAGE && message.status != MessageStatus.FAILED
    }
  val historicalNeedsAudio =
    conversationMessages.any { message ->
      message.kind == MessageKind.AUDIO && message.status != MessageStatus.FAILED
    }

  return RoleplaySendRequirements(
    primaryTextInput =
      messages
        .filterIsInstance<ChatMessageText>()
        .map { it.content.trim() }
        .firstOrNull(String::isNotBlank),
    needsImage = historicalNeedsImage || messages.any { it is ChatMessageImage && it.bitmaps.isNotEmpty() },
    needsAudio = historicalNeedsAudio || messages.any { it is ChatMessageAudioClip },
  )
}

internal fun resolveRoleplayWarmupAction(
  downloadStatus: ModelDownloadStatusType?,
  isInitializing: Boolean,
  hasInstance: Boolean,
  supportImage: Boolean,
  supportAudio: Boolean,
  needsImage: Boolean,
  needsAudio: Boolean,
): RoleplayWarmupAction {
  if (downloadStatus != ModelDownloadStatusType.SUCCEEDED || isInitializing) {
    return RoleplayWarmupAction.NONE
  }

  if (
    hasInstance &&
      canReuseRoleplayModelSession(
        supportImage = supportImage,
        supportAudio = supportAudio,
        needsImage = needsImage,
        needsAudio = needsAudio,
      )
  ) {
    return RoleplayWarmupAction.NONE
  }

  return if (needsImage || needsAudio) {
    RoleplayWarmupAction.MULTIMODAL
  } else {
    RoleplayWarmupAction.TEXT_ONLY
  }
}

internal fun resolveRoleplaySendExecutionPlan(
  needsImage: Boolean,
  needsAudio: Boolean,
  hasReusableMultimodalSession: Boolean,
  hasInitializedSession: Boolean,
): RoleplaySendExecutionPlan {
  val needsMultimodalSession = needsImage || needsAudio
  if (needsMultimodalSession) {
    return RoleplaySendExecutionPlan(
      queueImmediately = true,
      warmupAction =
        if (hasReusableMultimodalSession) {
          RoleplayWarmupAction.NONE
        } else {
          RoleplayWarmupAction.MULTIMODAL
        },
    )
  }

  return if (hasInitializedSession) {
    RoleplaySendExecutionPlan(
      queueImmediately = true,
      warmupAction = RoleplayWarmupAction.NONE,
    )
  } else {
    RoleplaySendExecutionPlan(
      queueImmediately = false,
      warmupAction = RoleplayWarmupAction.TEXT_ONLY,
    )
  }
}

internal fun canReuseRoleplayModelSession(
  instance: LlmModelInstance?,
  needsImage: Boolean,
  needsAudio: Boolean,
): Boolean {
  if (instance == null) {
    return false
  }
  return canReuseRoleplayModelSession(
    supportImage = instance.supportImage,
    supportAudio = instance.supportAudio,
    needsImage = needsImage,
    needsAudio = needsAudio,
  )
}

internal fun canReuseRoleplayModelSession(
  supportImage: Boolean,
  supportAudio: Boolean,
  needsImage: Boolean,
  needsAudio: Boolean,
): Boolean {
  return (!needsImage || supportImage) && (!needsAudio || supportAudio)
}

internal fun Message.supportsRoleplayActions(): Boolean {
  return supportsPinAction() || supportsRollbackAction()
}

internal fun Message.supportsPinAction(): Boolean {
  if (!accepted || !isCanonical || side == MessageSide.SYSTEM) {
    return false
  }
  return content.isNotBlank() || kind == MessageKind.IMAGE || kind == MessageKind.AUDIO
}

internal fun Message.supportsRollbackAction(): Boolean {
  return accepted && isCanonical && side != MessageSide.SYSTEM
}

internal fun Message.supportsRegenerateAction(): Boolean {
  return side == MessageSide.ASSISTANT && accepted && isCanonical && status == MessageStatus.COMPLETED
}

internal fun Message.supportsEditAction(): Boolean {
  return side == MessageSide.USER && accepted && isCanonical && kind == MessageKind.TEXT && content.isNotBlank()
}
