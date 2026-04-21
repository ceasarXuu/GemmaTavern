package selfgemma.talk.feature.roleplay.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.ToolInvocation

internal sealed interface RoleplayTimelineItem {
  val stableId: String

  data class MessageEntry(
    val message: Message,
  ) : RoleplayTimelineItem {
    override val stableId: String = "message:${message.id}"
  }

  data class ToolInvocationEntry(
    val invocation: ToolInvocation,
    val anchorSeq: Int,
  ) : RoleplayTimelineItem {
    override val stableId: String = "tool:${invocation.id}"
  }
}

internal fun buildRoleplayTimelineItems(
  messages: List<Message>,
  toolInvocations: List<ToolInvocation>,
  showToolDebugOutput: Boolean,
): List<RoleplayTimelineItem> {
  val messageEntries = messages.map(RoleplayTimelineItem::MessageEntry)
  if (!showToolDebugOutput || toolInvocations.isEmpty()) {
    return messageEntries
  }

  val messageSeqById = messages.associate { message -> message.id to message.seq }
  val toolEntries =
    toolInvocations.mapNotNull { invocation ->
      val anchorSeq = messageSeqById[invocation.turnId] ?: return@mapNotNull null
      RoleplayTimelineItem.ToolInvocationEntry(invocation = invocation, anchorSeq = anchorSeq)
    }

  return (messageEntries + toolEntries).sortedWith(
    compareBy<RoleplayTimelineItem>(
      { it.timelineAnchorSeq() },
      { it.timelinePriority() },
      { it.timelineStepIndex() },
      { it.timelineCreatedAt() },
      { it.stableId },
    )
  )
}

@Composable
internal fun RoleplayToolInvocationSystemRow(
  invocation: ToolInvocation,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
      modifier = Modifier.widthIn(max = 320.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor = MaterialTheme.colorScheme.onSurface,
      border =
        BorderStroke(
          width = 1.dp,
          color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = stringResource(R.string.chat_tool_call_label),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Text(
          text = invocation.toolName.ifBlank { "-" },
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

private fun RoleplayTimelineItem.timelineAnchorSeq(): Int {
  return when (this) {
    is RoleplayTimelineItem.MessageEntry -> message.seq
    is RoleplayTimelineItem.ToolInvocationEntry -> anchorSeq
  }
}

private fun RoleplayTimelineItem.timelinePriority(): Int {
  return when (this) {
    is RoleplayTimelineItem.ToolInvocationEntry -> 0
    is RoleplayTimelineItem.MessageEntry -> 1
  }
}

private fun RoleplayTimelineItem.timelineStepIndex(): Int {
  return when (this) {
    is RoleplayTimelineItem.ToolInvocationEntry -> invocation.stepIndex
    is RoleplayTimelineItem.MessageEntry -> Int.MAX_VALUE
  }
}

private fun RoleplayTimelineItem.timelineCreatedAt(): Long {
  return when (this) {
    is RoleplayTimelineItem.ToolInvocationEntry -> invocation.startedAt
    is RoleplayTimelineItem.MessageEntry -> message.createdAt
  }
}
