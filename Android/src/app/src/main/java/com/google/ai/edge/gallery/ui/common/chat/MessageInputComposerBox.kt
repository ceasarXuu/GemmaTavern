package selfgemma.talk.ui.common.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import selfgemma.talk.common.AudioClip
import selfgemma.talk.data.MAX_AUDIO_CLIP_COUNT
import selfgemma.talk.data.MAX_IMAGE_COUNT
import selfgemma.talk.R
import selfgemma.talk.ui.theme.customColors

private const val TAG = "AGMessageInputComposerBox"

@Composable
internal fun MessageInputComposerBox(
  context: Context,
  curMessage: String,
  pickedImages: List<Bitmap>,
  pickedAudioClips: List<AudioClip>,
  imageCount: Int,
  audioClipMessageCount: Int,
  inProgress: Boolean,
  isResettingSession: Boolean,
  modelInitializing: Boolean,
  modelPreparing: Boolean,
  isPressRecording: Boolean,
  showStopButtonWhenInProgress: Boolean,
  allowTextInputWhenInProgress: Boolean,
  allowAuxiliaryActionsWhenInProgress: Boolean,
  forceDisableComposer: Boolean,
  showAudioPicker: Boolean,
  showImagePicker: Boolean,
  showSkillsPicker: Boolean,
  showAddContentMenu: Boolean,
  onShowAddContentMenuChanged: (Boolean) -> Unit,
  onShowCameraCaptureBottomSheet: () -> Unit,
  recordingElapsedSeconds: String,
  onValueChanged: (String) -> Unit,
  onSendMessage: (List<ChatMessage>) -> Unit,
  onStopButtonClicked: () -> Unit,
  onSkillsClicked: () -> Unit,
  onClearComposerAttachments: () -> Unit,
  startHoldToTalkRecording: () -> Boolean,
  finishHoldToTalkRecording: suspend (Boolean, Boolean) -> Unit,
  takePicturePermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
  recordAudioClipsPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
  pickMedia: ManagedActivityResultLauncher<PickVisualMediaRequest, List<android.net.Uri>>,
) {
  Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 72.dp)) {
    val interactionState =
      resolveMessageInputInteractionState(
        inProgress = inProgress,
        isResettingSession = isResettingSession,
        modelInitializing = modelInitializing,
        isPressRecording = isPressRecording,
        showStopButtonWhenInProgress = showStopButtonWhenInProgress,
        allowTextInputWhenInProgress = allowTextInputWhenInProgress,
        allowAuxiliaryActionsWhenInProgress = allowAuxiliaryActionsWhenInProgress,
        forceDisableComposer = forceDisableComposer,
      )
    val enableTextEntry = interactionState.allowTextEntry
    val enableAuxiliaryActions = interactionState.allowAuxiliaryActions
    val canSend =
      enableTextEntry &&
        (curMessage.isNotEmpty() || pickedImages.isNotEmpty() || pickedAudioClips.isNotEmpty())
    val canRecordAudio =
      showAudioPicker &&
        enableAuxiliaryActions &&
        !isPressRecording &&
        (audioClipMessageCount + pickedAudioClips.size) < MAX_AUDIO_CLIP_COUNT
    val audioButtonEnabled =
      isAudioButtonEnabled(
        isResettingSession = isResettingSession,
        modelInitializing = modelInitializing,
        forceDisableComposer = forceDisableComposer,
        inProgress = inProgress,
        isPressRecording = isPressRecording,
        canRecordAudio = canRecordAudio,
        allowAuxiliaryActionsWhenInProgress = allowAuxiliaryActionsWhenInProgress,
      )
    val composerSupportState =
      resolveComposerSupportState(
        showSkillsPicker = showSkillsPicker,
        isPressRecording = isPressRecording,
      )
    val currentAudioButtonEnabled by rememberUpdatedState(audioButtonEnabled)
    val currentCanRecordAudio by rememberUpdatedState(canRecordAudio)
    val currentStartHoldToTalkRecording by rememberUpdatedState(startHoldToTalkRecording)
    val currentFinishHoldToTalkRecording by rememberUpdatedState(finishHoldToTalkRecording)
    val audioButtonInteractionSource = remember { MutableInteractionSource() }
    var activeAudioButtonPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    var audioButtonPressStartedRecording by remember { mutableStateOf(false) }
    val canOpenAddMenu = enableAuxiliaryActions

    LaunchedEffect(audioButtonInteractionSource) {
      audioButtonInteractionSource.interactions.collect { interaction ->
        when (interaction) {
          is PressInteraction.Press -> {
            activeAudioButtonPress = interaction
            val pressAction =
              resolveAudioButtonPressAction(
                audioButtonEnabled = currentAudioButtonEnabled,
                canRecordAudio = currentCanRecordAudio,
                hasRecordAudioPermission =
                  ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                  ) == PackageManager.PERMISSION_GRANTED,
              )
            when (pressAction) {
              AudioButtonPressAction.None -> {
                audioButtonPressStartedRecording = false
              }
              AudioButtonPressAction.RequestPermission -> {
                audioButtonPressStartedRecording = false
                Log.d(TAG, "Record audio permission missing; requesting from chat composer")
                recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              }
              AudioButtonPressAction.StartHoldRecording -> {
                audioButtonPressStartedRecording = currentStartHoldToTalkRecording()
              }
            }
          }
          is PressInteraction.Release -> {
            if (activeAudioButtonPress == interaction.press) {
              activeAudioButtonPress = null
              if (audioButtonPressStartedRecording) {
                audioButtonPressStartedRecording = false
                Log.d(TAG, "Chat audio button released sendAudio=true")
                currentFinishHoldToTalkRecording(true, false)
              }
            }
          }
          is PressInteraction.Cancel -> {
            if (activeAudioButtonPress == interaction.press) {
              activeAudioButtonPress = null
              if (audioButtonPressStartedRecording) {
                audioButtonPressStartedRecording = false
                Log.d(TAG, "Chat audio button gesture cancelled")
                currentFinishHoldToTalkRecording(false, true)
              }
            }
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box {
        FilledTonalIconButton(
          onClick = { onShowAddContentMenuChanged(true) },
          enabled = canOpenAddMenu,
          modifier = Modifier.size(ComposerButtonSize),
        ) {
          Icon(
            Icons.Outlined.Add,
            contentDescription = stringResource(R.string.cd_add_content_icon),
            modifier = Modifier.size(ComposerIconSize),
          )
        }

        DropdownMenu(
          expanded = showAddContentMenu,
          onDismissRequest = { onShowAddContentMenuChanged(false) },
        ) {
          if (showImagePicker) {
            val enableAddImageMenuItems =
              enableAuxiliaryActions && (imageCount + pickedImages.size) < MAX_IMAGE_COUNT
            DropdownMenuItem(
              text = { Text(stringResource(R.string.take_a_picture)) },
              leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
              enabled = enableAddImageMenuItems,
              onClick = {
                when (PackageManager.PERMISSION_GRANTED) {
                  ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
                    onShowAddContentMenuChanged(false)
                    onShowCameraCaptureBottomSheet()
                  }
                  else -> {
                    takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
                  }
                }
              },
            )

            DropdownMenuItem(
              text = { Text(stringResource(R.string.pick_from_album)) },
              leadingIcon = { Icon(Icons.Rounded.Photo, contentDescription = null) },
              enabled = enableAddImageMenuItems,
              onClick = {
                pickMedia.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
                onShowAddContentMenuChanged(false)
              },
            )
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (composerSupportState) {
          ComposerSupportState.SkillsChip ->
            AssistChip(
              onClick = onSkillsClicked,
              enabled = enableTextEntry,
              label = {
                Text(
                  text = stringResource(R.string.skills),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              modifier = Modifier.widthIn(max = 120.dp),
            )
          else -> Unit
        }

        when (composerSupportState) {
          ComposerSupportState.RecordingInline ->
            RecordingStatusField(recordingElapsedSeconds = recordingElapsedSeconds)
          else ->
            TextField(
              value = curMessage,
              onValueChange = onValueChanged,
              modifier = Modifier.fillMaxWidth(),
              enabled = enableTextEntry,
              minLines = 1,
              maxLines = 3,
              shape = MaterialTheme.shapes.extraLarge,
              colors =
                TextFieldDefaults.colors(
                  focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                  disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
      }

      if (showAudioPicker) {
        FilledTonalIconButton(
          onClick = {},
          enabled = audioButtonEnabled,
          interactionSource = audioButtonInteractionSource,
          modifier = Modifier.size(ComposerButtonSize),
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor =
                if (isPressRecording) {
                  MaterialTheme.customColors.recordButtonBgColor
                } else {
                  IconButtonDefaults.filledTonalIconButtonColors().containerColor
                },
              contentColor =
                if (isPressRecording) {
                  Color.White
                } else {
                  IconButtonDefaults.filledTonalIconButtonColors().contentColor
                },
            ),
        ) {
          Icon(
            if (isPressRecording) Icons.AutoMirrored.Rounded.Send else Icons.Outlined.Mic,
            contentDescription =
              stringResource(
                if (isPressRecording) R.string.release_to_send else R.string.record_audio_clip
              ),
            modifier = Modifier.size(ComposerIconSize),
          )
        }
      }

      if (interactionState.showStopButton) {
        if (!modelInitializing && !modelPreparing) {
          FilledTonalIconButton(
            onClick = onStopButtonClicked,
            modifier = Modifier.size(ComposerButtonSize),
          ) {
            Icon(
              Icons.Rounded.Stop,
              contentDescription = stringResource(R.string.cd_stop_icon),
              modifier = Modifier.size(ComposerIconSize),
            )
          }
        }
      } else {
        FilledIconButton(
          onClick = {
            if (canSend) {
              val message = curMessage.trim()
              onSendMessage(
                createMessagesToSend(
                  pickedImages = pickedImages,
                  audioClips = pickedAudioClips,
                  text = message,
                )
              )
              onClearComposerAttachments()
            }
          },
          enabled = canSend,
          modifier = Modifier.size(ComposerButtonSize),
        ) {
          Icon(
            Icons.AutoMirrored.Rounded.Send,
            contentDescription = stringResource(R.string.cd_send_prompt_icon),
            modifier = Modifier.size(ComposerSendIconSize),
          )
        }
      }
    }
  }
}
