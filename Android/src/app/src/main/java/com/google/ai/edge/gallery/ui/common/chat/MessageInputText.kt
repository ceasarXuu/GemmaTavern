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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import selfgemma.talk.R
import selfgemma.talk.common.AudioClip
import selfgemma.talk.common.calculatePeakAmplitude
import selfgemma.talk.common.decodeSampledBitmapFromUri
import selfgemma.talk.common.rotateBitmap
import selfgemma.talk.data.MAX_AUDIO_CLIP_DURATION_SEC
import selfgemma.talk.data.MAX_AUDIO_CLIP_COUNT
import selfgemma.talk.data.MAX_IMAGE_COUNT
import selfgemma.talk.data.RuntimeType
import selfgemma.talk.data.SAMPLE_RATE
import selfgemma.talk.data.Task
import selfgemma.talk.ui.theme.customColors
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGMessageInputText"
private val ComposerButtonSize = 48.dp
private val ComposerIconSize = 20.dp
private val ComposerSendIconSize = 20.dp
private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
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
private fun RecordingStatusField(
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

private class HoldToTalkRecordingSession(
  val audioRecord: AudioRecord,
  val audioStream: ByteArrayOutputStream,
  val startedAtMs: Long,
) {
  lateinit var readJob: Job
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
    var limit = MAX_IMAGE_COUNT
    val maxAllowedForThisMessage = (limit - imageCount).coerceAtLeast(0)

    val combinedSize = pickedImages.size + bitmaps.size
    val withinLimit = combinedSize <= maxAllowedForThisMessage

    pickedImages =
      if (withinLimit) {
        pickedImages + bitmaps
      } else {
        (pickedImages + bitmaps).take(maxAllowedForThisMessage)
      }
  }

  val updatePickedAudioClips: (List<AudioClip>) -> Unit = { audioDataList ->
    val maxAllowedForThisMessage = (MAX_AUDIO_CLIP_COUNT - audioClipMessageCount).coerceAtLeast(0)

    val combinedSize = pickedAudioClips.size + audioDataList.size
    val withinLimit = combinedSize <= maxAllowedForThisMessage

    pickedAudioClips =
      if (withinLimit) {
        pickedAudioClips + audioDataList
      } else {
        (pickedAudioClips + audioDataList).take(maxAllowedForThisMessage)
      }
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
    val composerText = curMessage.trim()
    val outgoingAudioClips = pickedAudioClips + AudioClip(audioData = audioData, sampleRate = SAMPLE_RATE)
    if (composerText.isEmpty() && pickedImages.isEmpty() && outgoingAudioClips.isEmpty()) {
      return
    }
    onSendMessage(
      createMessagesToSend(
        pickedImages = pickedImages,
        audioClips = outgoingAudioClips,
        text = composerText,
      )
    )
    clearComposerAttachments()
  }

  lateinit var finishHoldToTalkRecording: suspend (Boolean, Boolean) -> Unit

  fun startHoldToTalkRecording(): Boolean {
    if (activeRecordingSession != null) {
      Log.d(TAG, "Ignore recording start because a session is already active")
      return false
    }
    val minBufferSize =
      AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING)
    if (minBufferSize <= 0) {
      Log.w(TAG, "Cannot start hold-to-talk recording invalidMinBufferSize=$minBufferSize")
      return false
    }

    val recorder =
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AUDIO_CHANNEL_CONFIG,
        AUDIO_ENCODING,
        minBufferSize,
      )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      Log.w(TAG, "Cannot start hold-to-talk recording recorder not initialized")
      recorder.release()
      return false
    }

    val session =
      HoldToTalkRecordingSession(
        audioRecord = recorder,
        audioStream = ByteArrayOutputStream(),
        startedAtMs = System.currentTimeMillis(),
      )
    session.readJob =
      scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(minBufferSize)
        runCatching { recorder.startRecording() }
          .onFailure { error ->
            Log.e(TAG, "Failed to start hold-to-talk recording", error)
            withContext(Dispatchers.Main) {
              if (activeRecordingSession === session) {
                activeRecordingSession = null
                resetRecordingIndicators()
              }
            }
            return@launch
          }

        while (isActive && activeRecordingSession === session) {
          val bytesRead = recorder.read(buffer, 0, buffer.size)
          if (bytesRead > 0) {
            val amplitude = calculatePeakAmplitude(buffer = buffer, bytesRead = bytesRead)
            session.audioStream.write(buffer, 0, bytesRead)
            withContext(Dispatchers.Main) {
              if (activeRecordingSession === session) {
                activeRecordingElapsedMs = System.currentTimeMillis() - session.startedAtMs
                onAmplitudeChanged(amplitude)
              }
            }
          }

          if (System.currentTimeMillis() - session.startedAtMs >= MAX_AUDIO_CLIP_DURATION_SEC * 1000L) {
            withContext(Dispatchers.Main) {
              scope.launch {
                finishHoldToTalkRecording(true, false)
              }
            }
            break
          }
        }
      }

    activeRecordingSession = session
    handleClickRecordAudioClip()
    Log.d(TAG, "Started chat audio recording")
    return true
  }

  finishHoldToTalkRecording = { sendAudio, cancelled ->
    val session = activeRecordingSession
    if (session == null) {
      Unit
    } else {
    activeRecordingSession = null
    val elapsedMs = System.currentTimeMillis() - session.startedAtMs

    runCatching {
      if (session.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        session.audioRecord.stop()
      }
    }.onFailure { error ->
      Log.w(TAG, "Failed to stop hold-to-talk recorder cleanly", error)
    }
    session.readJob.cancelAndJoin()
    session.audioRecord.release()
    val audioData = session.audioStream.toByteArray()
    session.audioStream.reset()
    resetRecordingIndicators()

    if (!shouldDispatchRecordedAudio(sendAudio, cancelled, elapsedMs, audioData.size)) {
      Log.d(
        TAG,
        "Discard hold-to-talk recording sendAudio=$sendAudio cancelled=$cancelled elapsedMs=$elapsedMs bytes=${audioData.size}",
      )
      Unit
    } else {
      Log.d(TAG, "Dispatch recorded audio elapsedMs=$elapsedMs bytes=${audioData.size}")
      dispatchRecordedAudio(audioData)
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
      activeRecordingSession?.let { session ->
        runCatching {
          if (session.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            session.audioRecord.stop()
          }
        }
        session.readJob.cancel()
        runCatching { session.audioRecord.release() }
      }
      activeRecordingSession = null
      resetRecordingIndicators()
    }
  }

  Column {
    // A preview panel for the selected images and audio clips.
    if (pickedImages.isNotEmpty() || pickedAudioClips.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Spacer(modifier = Modifier.width(16.dp))

        for (image in pickedImages) {
          Box(contentAlignment = Alignment.TopEnd) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              tonalElevation = 1.dp,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
              Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_image_thumbnail),
                modifier = Modifier.height(80.dp),
              )
            }
            MediaPanelCloseButton { pickedImages = pickedImages.filter { image != it } }
          }
        }

        for ((index, audioClip) in pickedAudioClips.withIndex()) {
          Box(contentAlignment = Alignment.TopEnd) {
            Surface(
              shape = MaterialTheme.shapes.medium,
              tonalElevation = 1.dp,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
              AudioPlaybackPanel(
                audioData = audioClip.audioData,
                sampleRate = audioClip.sampleRate,
                isRecording = false,
                modifier = Modifier.padding(end = 16.dp),
              )
            }
            MediaPanelCloseButton {
              pickedAudioClips = pickedAudioClips.filterIndexed { curIndex, curAudioData ->
                curIndex != index
              }
            }
          }
        }

        Spacer(modifier = Modifier.width(16.dp))
      }
    }

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
      val currentStartHoldToTalkRecording by rememberUpdatedState(::startHoldToTalkRecording)
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
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box {
          FilledTonalIconButton(
            onClick = { showAddContentMenu = true },
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
            onDismissRequest = { showAddContentMenu = false },
          ) {
            if (showImagePicker) {
              val enableAddImageMenuItems =
                enableAuxiliaryActions && (imageCount + pickedImages.size) < MAX_IMAGE_COUNT
              DropdownMenuItem(
                text = { Text(stringResource(R.string.take_a_picture)) },
                leadingIcon = {
                  Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                },
                enabled = enableAddImageMenuItems,
                onClick = {
                  when (PackageManager.PERMISSION_GRANTED) {
                    ContextCompat.checkSelfPermission(
                      context,
                      Manifest.permission.CAMERA,
                    ) -> {
                      showAddContentMenu = false
                      showCameraCaptureBottomSheet = true
                    }
                    else -> {
                      takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                  }
                },
              )

              DropdownMenuItem(
                text = { Text(stringResource(R.string.pick_from_album)) },
                leadingIcon = {
                  Icon(Icons.Rounded.Photo, contentDescription = null)
                },
                enabled = enableAddImageMenuItems,
                onClick = {
                  pickMedia.launch(
                    PickVisualMediaRequest(
                      ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                  )
                  showAddContentMenu = false
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
                clearComposerAttachments()
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
