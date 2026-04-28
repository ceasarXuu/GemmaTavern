package selfgemma.talk.feature.roleplay.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionEvent

@Composable
internal fun ContinuityDebugDialog(
  debugState: RoleplayContinuityDebugState,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.chat_continuity_debug_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        DebugSection(
          title = stringResource(R.string.chat_continuity_runtime_state_label),
          content = debugState.runtimeState?.toDebugText() ?: stringResource(R.string.chat_continuity_debug_empty),
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_open_threads_label),
          content =
            if (debugState.openThreads.isEmpty()) {
              stringResource(R.string.chat_continuity_debug_empty)
            } else {
              debugState.openThreads.joinToString(separator = "\n\n") { it.toDebugText() }
            },
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_memory_atoms_label),
          content =
            if (debugState.memoryAtoms.isEmpty()) {
              stringResource(R.string.chat_continuity_debug_empty)
            } else {
              debugState.memoryAtoms.joinToString(separator = "\n\n") { it.toDebugText() }
            },
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_recent_events_label),
          content =
            if (debugState.recentEvents.isEmpty()) {
              stringResource(R.string.chat_continuity_debug_empty)
            } else {
              debugState.recentEvents.joinToString(separator = "\n\n") { it.toDebugText() }
            },
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_last_query_label),
          content = debugState.latestMemoryQueryPayload?.prettyDebugJson() ?: stringResource(R.string.chat_continuity_debug_empty),
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_last_pack_label),
          content = debugState.latestMemoryPackPayload?.prettyDebugJson() ?: stringResource(R.string.chat_continuity_debug_empty),
        )
        DebugSection(
          title = stringResource(R.string.chat_continuity_compaction_label),
          content = stringResource(R.string.chat_continuity_compaction_count_format, debugState.compactionEntryCount),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.close))
      }
    },
  )
}

@Composable
private fun DebugSection(
  title: String,
  content: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = content,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun RoleplayMessageActionsDialog(
  message: Message,
  onDismiss: () -> Unit,
  onPinMessage: (Message) -> Unit,
  onRollbackToMessage: (Message) -> Unit,
  onRegenerateAssistantMessage: (Message) -> Unit,
  onEditMessage: (Message) -> Unit,
  allowContinuityActions: Boolean,
  allowRegenerate: Boolean,
) {
  val canPin = message.supportsPinAction()
  val canRollback = allowContinuityActions && message.supportsRollbackAction()
  val canRegenerate = allowRegenerate && message.supportsRegenerateAction()
  val canEdit = allowContinuityActions && message.supportsEditAction()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = stringResource(R.string.chat_message_actions_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(R.string.chat_message_actions_content),
          style = MaterialTheme.typography.bodyMedium,
        )
        if (canPin) {
          TextButton(
            onClick = { onPinMessage(message) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(text = stringResource(R.string.chat_pin_message_action))
          }
        }
        if (canRollback) {
          TextButton(
            onClick = { onRollbackToMessage(message) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(text = stringResource(R.string.chat_rewind_here_action))
          }
        }
        if (canEdit) {
          TextButton(
            onClick = { onEditMessage(message) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(text = stringResource(R.string.chat_edit_from_here_action))
          }
        }
        if (canRegenerate) {
          TextButton(
            onClick = { onRegenerateAssistantMessage(message) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(text = stringResource(R.string.chat_regenerate_reply_action))
          }
        }
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(text = stringResource(R.string.cancel))
        }
      }
    },
    confirmButton = {},
    dismissButton = {},
  )
}

internal fun RuntimeStateSnapshot.toDebugText(): String {
  return buildString {
    appendLine("updatedAt=$updatedAt")
    appendLine("sourceMessageId=${sourceMessageId ?: "-"}")
    appendLine("scene=$sceneJson")
    appendLine("relationship=$relationshipJson")
    append("entities=$activeEntitiesJson")
  }
}

internal fun OpenThread.toDebugText(): String {
  return buildString {
    appendLine("[$status] $type owner=$owner priority=$priority")
    appendLine("content=$content")
    appendLine("source=${sourceMessageIds.joinToString().ifBlank { "-" }}")
    append("resolvedBy=${resolvedByMessageId ?: "-"}")
  }
}

internal fun MemoryAtom.toDebugText(): String {
  return buildString {
    appendLine("$plane/$namespace $subject | $predicate | $objectValue")
    appendLine("stability=$stability epistemic=$epistemicStatus branch=$branchScope")
    appendLine("confidence=$confidence salience=$salience updatedAt=$updatedAt")
    appendLine("source=${sourceMessageIds.joinToString().ifBlank { "-" }}")
    append("evidence=${evidenceQuote.ifBlank { "-" }}")
  }
}

internal fun SessionEvent.toDebugText(): String {
  return buildString {
    appendLine("$eventType @ $createdAt")
    append(payloadJson)
  }
}

internal fun String.prettyDebugJson(): String {
  return replace("\",\"", "\",\n\"")
    .replace("\",\"", "\",\n\"")
    .replace(",\"", ",\n\"")
    .replace("{\"", "{\n\"")
    .replace("}", "\n}")
    .replace("[{", "[\n{")
    .replace("}]", "}\n]")
}
