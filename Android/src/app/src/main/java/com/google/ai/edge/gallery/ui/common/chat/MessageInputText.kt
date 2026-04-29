/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.common.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import selfgemma.talk.R
import selfgemma.talk.common.AudioClip
import selfgemma.talk.data.MAX_AUDIO_CLIP_COUNT
import selfgemma.talk.data.MAX_IMAGE_COUNT
import selfgemma.talk.data.SAMPLE_RATE
import selfgemma.talk.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMessageInputText"
internal val ComposerButtonSize = 48.dp
internal val ComposerIconSize = 20.dp
internal val ComposerSendIconSize = 20.dp
private const val MIN_HOLD_TO_SEND_MS = 250L

internal data class MessageInputInteractionState(
  val allowTextEntry: Boolean,
  val allowAuxiliaryActions: Boolean,
  val showStopButton: Boolean,
)

internal enum class ComposerSupportState {
  None,
  SkillsChip,
  RecordingInline,
}

internal enum class AudioButtonPressAction {
  None,
  RequestPermission,
  StartHoldRecording,
}

internal fun shouldDispatchRecordedAudio(
  sendAudio: Boolean,
  cancelled: Boolean,
  elapsedMs: Long,
  audioSize: Int,
): Boolean = sendAudio && !cancelled && elapsedMs >= MIN_HOLD_TO_SEND_MS && audioSize > 0

internal fun resolveComposerSupportState(
  showSkillsPicker: Boolean,
  isPressRecording: Boolean,
): ComposerSupportState =
  when {
    isPressRecording -> ComposerSupportState.RecordingInline
    showSkillsPicker -> ComposerSupportState.SkillsChip
    else -> ComposerSupportState.None
  }

internal fun resolveAudioButtonPressAction(
  audioButtonEnabled: Boolean,
  canRecordAudio: Boolean,
  hasRecordAudioPermission: Boolean,
): AudioButtonPressAction =
  when {
    !audioButtonEnabled || !canRecordAudio -> AudioButtonPressAction.None
    !hasRecordAudioPermission -> AudioButtonPressAction.RequestPermission
    else -> AudioButtonPressAction.StartHoldRecording
  }

internal fun isAudioButtonEnabled(
  isResettingSession: Boolean,
  modelInitializing: Boolean,
  forceDisableComposer: Boolean,
  inProgress: Boolean,
  isPressRecording: Boolean,
  canRecordAudio: Boolean,
  allowAuxiliaryActionsWhenInProgress: Boolean = false,
): Boolean {
  val baseLocked = isResettingSession || modelInitializing || forceDisableComposer
  if (baseLocked || (inProgress && !allowAuxiliaryActionsWhenInProgress)) {
    return false
  }
  return isPressRecording || canRecordAudio
}

@Composable
internal fun RecordingStatusField(
  recordingElapsedSeconds: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    shape = MaterialTheme.shapes.extraLarge,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "${stringResource(R.string.release_to_send)}  $recordingElapsedSeconds s",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

internal fun resolveMessageInputInteractionState(
  inProgress: Boolean,
  isResettingSession: Boolean,
  modelInitializing: Boolean,
  isPressRecording: Boolean,
  showStopButtonWhenInProgress: Boolean,
  allowTextInputWhenInProgress: Boolean,
  allowAuxiliaryActionsWhenInProgress: Boolean = false,
  forceDisableComposer: Boolean,
): MessageInputInteractionState {
  val baseLocked = isResettingSession || modelInitializing || forceDisableComposer
  val allowTextEntry = !baseLocked && !isPressRecording && (!inProgress || allowTextInputWhenInProgress)
  val allowAuxiliaryActions =
    !baseLocked &&
      !isPressRecording &&
      (!inProgress || allowAuxiliaryActionsWhenInProgress)
  val showStopButton =
    inProgress &&
      showStopButtonWhenInProgress &&
      !allowTextInputWhenInProgress &&
      !allowAuxiliaryActionsWhenInProgress
  return MessageInputInteractionState(
    allowTextEntry = allowTextEntry,
    allowAuxiliaryActions = allowAuxiliaryActions,
    showStopButton = showStopButton,
  )
}



/**
 * Composable function to display a text input field for composing chat messages.
 *
 * This function renders a row containing a text field for message input and a send button. It
 * handles message composition, input validation, and sending messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInputText(
  task: Task,
  curMessage: String,
  isResettingSession: Boolean,
  inProgress: Boolean,
  imageCount: Int,
  audioClipMessageCount: Int,
  modelInitializing: Boolean,
  onValueChanged: (String) -> Unit,
  onSendMessage: (List<ChatMessage>) -> Unit,
  modelPreparing: Boolean = false,
  onOpenPromptTemplatesClicked: () -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onSetAudioRecorderVisible: (visible: Boolean) -> Unit = {},
  onAmplitudeChanged: (Int) -> Unit,
  onSkillsClicked: () -> Unit = {},
  onPickedImagesChanged: (List<Bitmap>) -> Unit = {},
  onPickedAudioClipsChanged: (List<AudioClip>) -> Unit = {},
  showPromptTemplatesInMenu: Boolean = false,
  showSkillsPicker: Boolean = false,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  showStopButtonWhenInProgress: Boolean = false,
  allowTextInputWhenInProgress: Boolean = false,
  allowAuxiliaryActionsWhenInProgress: Boolean = false,
  forceDisableComposer: Boolean = false,
  onImageLimitExceeded: () -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  var showAddContentMenu by remember { mutableStateOf(false) }
  var showCameraCaptureBottomSheet by remember { mutableStateOf(false) }
  val cameraCaptureSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var pickedImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  var pickedAudioClips by remember { mutableStateOf<List<AudioClip>>(listOf()) }
  var hasFrontCamera by remember { mutableStateOf(false) }
  var activeRecordingSession by remember { mutableStateOf<HoldToTalkRecordingSession?>(null) }
  var activeRecordingElapsedMs by remember { mutableLongStateOf(0L) }
  val sensorObserver = remember { SensorObserver(context) }
  val isPressRecording = activeRecordingSession != null
  val recordingElapsedSeconds by remember {
    derivedStateOf { "%.1f".format(activeRecordingElapsedMs.toFloat() / 1000f) }
  }

  val updatePickedImages: (List<Bitmap>) -> Unit = { bitmaps ->
    pickedImages = appendWithCap(pickedImages, bitmaps, MAX_IMAGE_COUNT - imageCount)
  }

  val updatePickedAudioClips: (List<AudioClip>) -> Unit = { audioDataList ->
    pickedAudioClips =
      appendWithCap(pickedAudioClips, audioDataList, MAX_AUDIO_CLIP_COUNT - audioClipMessageCount)
  }

  LaunchedEffect(Unit) { checkFrontCamera(context = context, callback = { hasFrontCamera = it }) }

  LaunchedEffect(pickedImages) { onPickedImagesChanged(pickedImages) }

  LaunchedEffect(pickedAudioClips) { onPickedAudioClipsChanged(pickedAudioClips) }

  // Permission request when taking picture.
  val takePicturePermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        showAddContentMenu = false
        showCameraCaptureBottomSheet = true
      }
    }

  val handleClickRecordAudioClip = {
    showAddContentMenu = false
    onSetAudioRecorderVisible(true)
  }

  fun resetRecordingIndicators() {
    activeRecordingElapsedMs = 0L
    onAmplitudeChanged(0)
    onSetAudioRecorderVisible(false)
  }

  fun clearComposerAttachments() {
    pickedImages = listOf()
    pickedAudioClips = listOf()
  }

  fun dispatchRecordedAudio(audioData: ByteArray) {
    val messages =
      buildRecordedAudioMessages(
        curMessage = curMessage,
        pickedImages = pickedImages,
        pickedAudioClips = pickedAudioClips,
        audioData = audioData,
        sampleRate = SAMPLE_RATE,
      ) ?: return
    onSendMessage(messages)
    clearComposerAttachments()
  }

  lateinit var finishHoldToTalkRecording: suspend (Boolean, Boolean) -> Unit

  fun startHoldToTalkRecording(): Boolean {
    if (activeRecordingSession != null) {
      Log.d(TAG, "Ignore recording start because a session is already active")
      return false
    }
    val session =
      beginHoldToTalkAudioRecording(
        scope = scope,
        isStillActive = { it === activeRecordingSession },
        onAmplitudeUpdate = { _, amplitude, elapsedMs ->
          activeRecordingElapsedMs = elapsedMs
          onAmplitudeChanged(amplitude)
        },
        onMaxDurationReached = { scope.launch { finishHoldToTalkRecording(true, false) } },
        onStartFailure = { failed ->
          if (activeRecordingSession === failed) {
            activeRecordingSession = null
            resetRecordingIndicators()
          }
        },
      ) ?: return false
    activeRecordingSession = session
    handleClickRecordAudioClip()
    return true
  }

  finishHoldToTalkRecording = { sendAudio, cancelled ->
    val session = activeRecordingSession
    if (session != null) {
      activeRecordingSession = null
      val result = finalizeHoldToTalkAudioRecording(session)
      resetRecordingIndicators()
      if (
        shouldDispatchRecordedAudio(sendAudio, cancelled, result.elapsedMs, result.audioData.size)
      ) {
        Log.d(TAG, "Dispatch recorded audio elapsedMs=${result.elapsedMs} bytes=${result.audioData.size}")
        dispatchRecordedAudio(result.audioData)
      } else {
        Log.d(
          TAG,
          "Discard hold-to-talk recording sendAudio=$sendAudio cancelled=$cancelled elapsedMs=${result.elapsedMs} bytes=${result.audioData.size}",
        )
      }
    }
  }

  // Permission request when recording audio clips.
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      Log.d(TAG, "Record audio permission result granted=$permissionGranted")
    }

  // Registers a photo picker activity launcher in single-select mode.
  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
      // Callback is invoked after the user selects media items or closes the
      // photo picker.
      if (uris.isNotEmpty()) {
        scope.launch(Dispatchers.IO) {
          handleImagesSelected(
            context = context,
            uris = uris,
            onImagesSelected = { bitmaps -> updatePickedImages(bitmaps) },
          )
        }
      }
    }

  DisposableEffect(lifecycleOwner) {
    lifecycleOwner.lifecycle.addObserver(sensorObserver)
    onDispose { lifecycleOwner.lifecycle.removeObserver(sensorObserver) }
  }

  DisposableEffect(Unit) {
    onDispose {
      activeRecordingSession?.let { disposeHoldToTalkAudioRecording(it) }
      activeRecordingSession = null
      resetRecordingIndicators()
    }
  }

  Column {
    // A preview panel for the selected images and audio clips.
    MessageInputPreviewPanel(
      pickedImages = pickedImages,
      pickedAudioClips = pickedAudioClips,
      onRemoveImage = { image -> pickedImages = pickedImages.filter { image != it } },
      onRemoveAudioClip = { index ->
        pickedAudioClips =
          pickedAudioClips.filterIndexed { curIndex, _ -> curIndex != index }
      },
    )

    MessageInputComposerBox(
      context = context,
      curMessage = curMessage,
      pickedImages = pickedImages,
      pickedAudioClips = pickedAudioClips,
      imageCount = imageCount,
      audioClipMessageCount = audioClipMessageCount,
      inProgress = inProgress,
      isResettingSession = isResettingSession,
      modelInitializing = modelInitializing,
      modelPreparing = modelPreparing,
      isPressRecording = isPressRecording,
      showStopButtonWhenInProgress = showStopButtonWhenInProgress,
      allowTextInputWhenInProgress = allowTextInputWhenInProgress,
      allowAuxiliaryActionsWhenInProgress = allowAuxiliaryActionsWhenInProgress,
      forceDisableComposer = forceDisableComposer,
      showAudioPicker = showAudioPicker,
      showImagePicker = showImagePicker,
      showSkillsPicker = showSkillsPicker,
      showAddContentMenu = showAddContentMenu,
      onShowAddContentMenuChanged = { showAddContentMenu = it },
      onShowCameraCaptureBottomSheet = { showCameraCaptureBottomSheet = true },
      recordingElapsedSeconds = recordingElapsedSeconds,
      onValueChanged = onValueChanged,
      onSendMessage = onSendMessage,
      onStopButtonClicked = onStopButtonClicked,
      onSkillsClicked = onSkillsClicked,
      onClearComposerAttachments = { clearComposerAttachments() },
      startHoldToTalkRecording = ::startHoldToTalkRecording,
      finishHoldToTalkRecording = finishHoldToTalkRecording,
      takePicturePermissionLauncher = takePicturePermissionLauncher,
      recordAudioClipsPermissionLauncher = recordAudioClipsPermissionLauncher,
      pickMedia = pickMedia,
    )
  }

  if (showCameraCaptureBottomSheet) {
    MessageInputCameraCaptureSheet(
      sheetState = cameraCaptureSheetState,
      onDismiss = { showCameraCaptureBottomSheet = false },
      hasFrontCamera = hasFrontCamera,
      scope = scope,
      sensorRotationProvider = { sensorObserver.currentRotation },
      onImagesCaptured = { updatePickedImages(it) },
    )
  }
}
