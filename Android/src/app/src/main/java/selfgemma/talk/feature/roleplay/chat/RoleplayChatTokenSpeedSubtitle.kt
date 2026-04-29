package selfgemma.talk.feature.roleplay.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.ui.common.chat.rememberStreamingTokenSpeed

@Composable
internal fun rememberRoleplayChatTokenSpeedSubtitle(
  messages: List<Message>,
  showLiveTokenSpeed: Boolean,
  inProgress: Boolean,
): String {
  val latestAssistantMessage =
    remember(messages) {
      messages.lastOrNull { it.side == MessageSide.ASSISTANT }
    }
  val streamingAssistantText =
    remember(messages) {
      messages
        .lastOrNull { it.side == MessageSide.ASSISTANT && it.status == MessageStatus.STREAMING }
        ?.content
        .orEmpty()
    }
  val completed = latestAssistantMessage?.takeIf { it.status == MessageStatus.COMPLETED }
  val tokenSpeed =
    rememberStreamingTokenSpeed(
      streamingText = streamingAssistantText,
      isStreaming = showLiveTokenSpeed && inProgress,
      completedText = completed?.content.orEmpty(),
      completedLatencyMs = completed?.latencyMs,
      completedAtEpochMs = completed?.updatedAt,
    )
  return tokenSpeed
    ?.takeIf { showLiveTokenSpeed }
    ?.let { stringResource(R.string.chat_token_speed_format, it) }
    .orEmpty()
}
