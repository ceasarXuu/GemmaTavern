package selfgemma.talk.feature.roleplay.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.feature.roleplay.chat.ChatComposer
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.MessageInputText

@Composable
internal fun RoleplayChatComposerSection(
  llmChatTask: Task?,
  draft: String,
  inProgress: Boolean,
  isActiveModelInitialized: Boolean,
  isActiveModelInitializing: Boolean,
  lastMessageStatus: MessageStatus?,
  errorMessage: String?,
  activeModel: Model?,
  onUpdateDraft: (String) -> Unit,
  onComposerBoundsChanged: (Rect?) -> Unit,
  onSendMessages: (List<ChatMessage>) -> Unit,
  onSendDraft: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    errorMessage?.let { message ->
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
    }

    if (llmChatTask != null) {
      Box(
        modifier =
          Modifier.onGloballyPositioned { coordinates ->
            onComposerBoundsChanged(coordinates.boundsInWindow())
          }
      ) {
        MessageInputText(
          task = llmChatTask,
          curMessage = draft,
          isResettingSession = false,
          inProgress = inProgress,
          imageCount = 0,
          audioClipMessageCount = 0,
          modelInitializing = isActiveModelInitializing,
          modelPreparing = inProgress && lastMessageStatus == MessageStatus.STREAMING,
          onValueChanged = onUpdateDraft,
          onSendMessage = onSendMessages,
          onAmplitudeChanged = {},
          showPromptTemplatesInMenu = false,
          showSkillsPicker = false,
          showImagePicker = activeModel?.llmSupportImage == true,
          showAudioPicker = activeModel?.llmSupportAudio == true,
          allowTextInputWhenInProgress = true,
          allowAuxiliaryActionsWhenInProgress = true,
          forceDisableComposer = activeModel == null,
        )
      }
    } else {
      ChatComposer(
        draft = draft,
        onDraftChange = onUpdateDraft,
        canSend = activeModel != null && draft.isNotBlank(),
        modifier =
          Modifier.onGloballyPositioned { coordinates ->
            onComposerBoundsChanged(coordinates.boundsInWindow())
          },
        onSend = onSendDraft,
      )
    }
  }
}
