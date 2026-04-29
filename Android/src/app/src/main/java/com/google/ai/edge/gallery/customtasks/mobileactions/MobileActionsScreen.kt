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
package selfgemma.talk.customtasks.mobileactions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import selfgemma.talk.AnalyticsEvent
import selfgemma.talk.R
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.firebaseAnalytics
import selfgemma.talk.ui.common.MarkdownText
import selfgemma.talk.ui.common.chat.ChatMessageWarning
import selfgemma.talk.ui.common.chat.MessageBodyLoading
import selfgemma.talk.ui.common.chat.MessageBodyWarning
import selfgemma.talk.ui.common.getTaskBgGradientColors
import selfgemma.talk.ui.common.getTaskIconColor
import selfgemma.talk.ui.common.textandvoiceinput.HoldToDictateViewModel
import selfgemma.talk.ui.common.textandvoiceinput.TextAndVoiceInput
import selfgemma.talk.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import selfgemma.talk.ui.modelmanager.ModelInitializationStatus
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMAScreen"

/**
 * A Composable function that displays the MobileActions screen.
 *
 * This screen allows users to interact with an AI model using voice or text input to perform
 * various actions on their device.
 */
@Composable
fun MobileActionsScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  mobileActionsViewModel: MobileActionsViewModel = hiltViewModel(),
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  tools: List<ToolProvider>,
  onProcessingStarted: () -> Unit,
) {
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  // Permission request when recording audio clips.
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  // Ask for audio recording permission.
  LaunchedEffect(Unit) {
    // Check permission.
    when (PackageManager.PERMISSION_GRANTED) {
      // Already got permission. Call the lambda.
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }

      // Otherwise, ask for permission
      else -> {
        recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
    ) {
      MainUi(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        tools = tools,
        bottomPadding = bottomPadding,
        viewModel = mobileActionsViewModel,
        curActions = curActions,
        setAppBarControlsDisabled = setAppBarControlsDisabled,
        onProcessingStarted = onProcessingStarted,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  viewModel: MobileActionsViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  onProcessingStarted: () -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember { model.configValues }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  var curAmplitude by remember { mutableIntStateOf(0) }
  var clearInputTextTrigger by remember { mutableLongStateOf(0L) }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var doneGeneratingResponse by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val focusManager = LocalFocusManager.current
  val resources = LocalResources.current
  val taskColor = getTaskBgGradientColors(task = task)[1]

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  // Reset states on config changes.
  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      Log.d(TAG, "model config values changed.")
      modelManagerViewModel.setInitializationStatus(
        model = model,
        status = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED),
      )
      viewModel.reset()
    }
  }

  DisposableEffect(Unit) { onDispose { viewModel.cleanUp() } }

  // Show a loading indicator before the model is initialized.
  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
  }
  // Main UI.
  else {
    val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

    val send: (String) -> Unit = { text ->
      scope.launch(Dispatchers.Main) {
        selectedTabIndex = 0
        clearInputTextTrigger = System.currentTimeMillis()
        focusManager.clearFocus()
      }

      onProcessingStarted()

      // Figure out the correct action from user prompt.
      doneGeneratingResponse = false
      viewModel.processUserPrompt(
        model = model,
        userPrompt = text,
        tools = tools,
        onProcessDone = {
          doneGeneratingResponse = true
          Log.d(TAG, "Actions count: ${curActions.size}")

          // Execute functions.
          if (curActions.isNotEmpty()) {
            val errors = mutableListOf<String>()
            for (action in curActions) {
              val curError = viewModel.performAction(action = action, context = context)
              if (curError.isEmpty()) {
                viewModel.addFunctionCallDetails(
                  details = genFormattedFunctionCall(action = action, resources = resources)
                )
              } else {
                errors.add(curError)
              }
            }
            if (errors.isNotEmpty()) {
              scope.launch {
                snackbarHostState.showSnackbar(
                  errors.joinToString(separator = "; "),
                  withDismissAction = true,
                  duration = SnackbarDuration.Long,
                )
              }
            }
          }
          // No function recognized.
          else {
            viewModel.setNoFunctionRecognized(value = true)

            // Show a snack bar for unrecognized command.
            scope.launch {
              snackbarHostState.showSnackbar(
                noFunctionCallSnackbarMessage,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
              )
            }
          }
        },
        onError = { error ->
          doneGeneratingResponse = true

          // Show error dialog for users to reset the engine.
          errorDialogContent = error
          showErrorDialog = true
        },
      )

      firebaseAnalytics?.logEvent(
        AnalyticsEvent.GENERATE_ACTION.id,
        Bundle().apply {
          putString("capability_name", task.id)
          putString("model_id", model.name)
        },
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(
              bottom =
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 8.dp
            )
            .imePadding()
      ) {
        // Message shown when no prompt has been processed yet.
        if (uiState.showWelcomeMessage) {
          MobileActionsWelcomeBlock(
            task = task,
            modifier = Modifier.fillMaxWidth().weight(1f),
          )
        }
        // Current user prompt and model response.
        else {
          // The current user prompt.
          Box(
            modifier =
              Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              uiState.userPrompt,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
          }

          // Loader when processing.
          if (uiState.processing) {
            Box(
              modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
              contentAlignment = Alignment.TopStart,
            ) {
              MessageBodyLoading()
            }
          }
          // Response.
          else {
            MobileActionsResponseTabs(
              selectedTabIndex = selectedTabIndex,
              onTabSelected = { selectedTabIndex = it },
              taskColor = taskColor,
              noFunctionRecognized = uiState.noFunctionRecognized,
              modelResponse = uiState.modelResponse,
              functionCallDetails = uiState.functionCallDetails,
              doneGeneratingResponse = doneGeneratingResponse,
              modifier = Modifier.weight(1f),
            )
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // A list of prompt templates.
          MobileActionsPromptTemplateChips(
            processing = uiState.processing,
            onPromptClick = { send(it) },
          )

          // Text and voice Input.
          Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TextAndVoiceInput(
              task = task,
              processing = uiState.processing,
              holdToDictateViewModel = holdToDictateViewModel,
              onDone = { text -> send(text) },
              onAmplitudeChanged = { curAmplitude = it },
              clearTextTrigger = clearInputTextTrigger,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      // Show an overlay during speech recognition.
      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        exit =
          fadeOut(
            animationSpec =
              tween(durationMillis = 100, easing = FastOutSlowInEasing, delayMillis = 300)
          ),
      ) {
        VoiceRecognizerOverlay(
          task = task,
          viewModel = holdToDictateViewModel,
          curAmplitude = curAmplitude,
          bottomPadding = bottomPadding,
        )
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = bottomPadding + 100.dp).align(Alignment.BottomCenter),
      )
    }
  }

  if (showErrorDialog) {
    MobileActionsResetEngineDialog(
      errorDialogContent = errorDialogContent,
      taskColor = taskColor,
      onDismiss = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      onConfirm = {
        showErrorDialog = false
        errorDialogContent = ""

        viewModel.resetEngine(
          context = context,
          model = model,
          tools = tools,
          modelManagerViewModel = modelManagerViewModel,
          onError = {
            errorDialogContent = it
            showErrorDialog = true
          },
        )
      },
    )
  }
}
