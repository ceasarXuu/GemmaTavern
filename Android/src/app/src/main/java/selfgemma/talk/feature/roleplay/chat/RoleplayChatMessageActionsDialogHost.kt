package selfgemma.talk.feature.roleplay.chat

import android.util.Log
import androidx.compose.runtime.Composable
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message

private const val TAG = "RoleplayChatActions"

@Composable
internal fun RoleplayChatMessageActionsDialogHost(
  selectedMessage: Message?,
  sessionId: String?,
  activeModel: Model?,
  inProgress: Boolean,
  hasPendingSends: Boolean,
  onDismiss: () -> Unit,
  onPin: (Message) -> Unit,
  onRollback: (String) -> Unit,
  onRegenerate: (String, Model) -> Unit,
  onEdit: (String) -> Unit,
) {
  if (selectedMessage == null) return
  RoleplayMessageActionsDialog(
    message = selectedMessage,
    onDismiss = {
      Log.d(TAG, "dismiss message actions sessionId=$sessionId messageId=${selectedMessage.id}")
      onDismiss()
    },
    onPinMessage = { actionMessage ->
      Log.d(TAG, "pin message action sessionId=$sessionId messageId=${actionMessage.id}")
      onDismiss()
      onPin(actionMessage)
    },
    onRollbackToMessage = { actionMessage ->
      Log.d(TAG, "rollback message action sessionId=$sessionId messageId=${actionMessage.id}")
      onDismiss()
      onRollback(actionMessage.id)
    },
    onRegenerateAssistantMessage = { actionMessage ->
      val currentModel = activeModel ?: return@RoleplayMessageActionsDialog
      Log.d(TAG, "regenerate message action sessionId=$sessionId messageId=${actionMessage.id} model=${currentModel.name}")
      onDismiss()
      onRegenerate(actionMessage.id, currentModel)
    },
    onEditMessage = { actionMessage ->
      Log.d(TAG, "edit message action sessionId=$sessionId messageId=${actionMessage.id}")
      onDismiss()
      onEdit(actionMessage.id)
    },
    allowContinuityActions = !inProgress && !hasPendingSends,
    allowRegenerate = activeModel != null && !inProgress && !hasPendingSends,
  )
}
