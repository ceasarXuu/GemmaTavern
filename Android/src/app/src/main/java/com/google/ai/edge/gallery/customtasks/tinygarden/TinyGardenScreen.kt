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
package selfgemma.talk.customtasks.tinygarden

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.webkit.WebViewAssetLoader
import selfgemma.talk.AnalyticsEvent
import selfgemma.talk.R
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.data.ValueType
import selfgemma.talk.data.convertValueToTargetType
import selfgemma.talk.firebaseAnalytics
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatMessageWarning
import selfgemma.talk.ui.common.chat.ChatSide
import selfgemma.talk.ui.common.getTaskBgGradientColors
import selfgemma.talk.ui.common.textandvoiceinput.HoldToDictateViewModel
import selfgemma.talk.ui.common.textandvoiceinput.TextAndVoiceInput
import selfgemma.talk.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.theme.customColors
import com.google.ai.edge.litertlm.ToolProvider
import com.google.common.io.BaseEncoding
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val TAG = "AGTinyGarden"
private const val ASSETS_BASE_URL = "https://appassets.androidplatform.net"

/** The main screen for the Tiny Garden game. */
@Composable
fun TinyGardenScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>,
  viewModel: TinyGardenViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
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

  LaunchedEffect(Unit) {
    // Check permission
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
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MainUi(
          task = task,
          modelManagerViewModel = modelManagerViewModel,
          tools = tools,
          bottomPadding = bottomPadding,
          commandFlow = commandFlow,
          viewModel = viewModel,
          setAppBarControlsDisabled = setAppBarControlsDisabled,
          setTopBarVisible = setTopBarVisible,
        )

        // Resetting engine spinner.
        ResettingEngineOverlay(visible = uiState.resettingEngine)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  viewModel: TinyGardenViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  commandFlow: Flow<TinyGardenCommand>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember(model) { model.configValues }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()
  var clearTextTrigger by remember { mutableLongStateOf(0L) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  var showConversationHistoryPanel by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val snackbarHostState = remember { SnackbarHostState() }
  var prevSeed by remember { mutableStateOf("") }
  var prevPlots by remember { mutableStateOf("") }
  var prevAction by remember { mutableStateOf("") }
  val resources = LocalResources.current
  val context = LocalContext.current

  val taskColor = getTaskBgGradientColors(task = task)[1]
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  // Close conversation history panel when pressing back button.
  BackHandler(enabled = showConversationHistoryPanel) { showConversationHistoryPanel = false }

  LaunchedEffect(showConversationHistoryPanel) { setTopBarVisible(!showConversationHistoryPanel) }

  LaunchedEffect(Unit) {
    // Run commands/functions generated by TinyGardenTools.
    commandFlow.collect { command ->
      val summary = viewModel.handleTinyGardenCommand(
        command = command,
        resources = resources,
        webView = webViewRef,
      )
      prevSeed = summary.seed
      prevPlots = summary.plots
      prevAction = summary.action
    }
  }

  val noFunctionCallWarningMessage = stringResource(R.string.warning_no_function_call)
  val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

  // A function to process the input from the user.
  fun processInstructionText(text: String) {
    clearTextTrigger = System.currentTimeMillis()

    if (text.trim().isNotEmpty()) {
      // A special input to unlock all :)
      if (text.trim().tinyGardenSha256() == "XtNztQDSDvVpMRPOK+q9tZs43x/VD1teVs3CvWp7zkc=") {
        webViewRef
          ?.runCatching { evaluateJavascript("tinyGarden.unlockAll()", null) }
          ?.onFailure { e -> Log.e(TAG, "$e") }
      } else {
        // Run inference to get response command in json.
        viewModel.getCommand(
          model = model,
          instructionText = text,
          onDone = { response ->
            // Add a warning message if no function was recognized.
            if (uiState.messages.last().side != ChatSide.AGENT) {
              viewModel.addMessage(
                message = ChatMessageWarning(content = noFunctionCallWarningMessage)
              )
              // Show a snack bar for unrecognized command.
              scope.launch {
                snackbarHostState.showSnackbar(
                  noFunctionCallSnackbarMessage,
                  withDismissAction = true,
                )
              }
            }

            // Add the final response from the model.
            // viewModel.addMessage(
            //   message = ChatMessageText(content = response, side = ChatSide.AGENT)
            // )

            // Reset conversation every {numTurns} turns.
            val numTurnsToReset =
              convertValueToTargetType(
                value = model.configValues.getValue(ConfigKeys.RESET_CONVERSATION_TURN_COUNT.label),
                valueType = ValueType.INT,
              )
                as Int
            Log.d(TAG, "Target turn to reset: $numTurnsToReset")
            if (uiState.numTurns == numTurnsToReset) {
              Log.d(TAG, "!! This is the turn to reset conversation")
              viewModel.resetConversation(
                model = model,
                tools = tools,
                prevSeed = prevSeed,
                prevPlots = prevPlots,
                prevAction = prevAction,
                onError = { error ->
                  errorDialogContent = error
                  showErrorDialog = true
                },
              )
            }
          },
          onError = { error ->
            // Show error dialog for users to reset the engine.
            errorDialogContent = error
            showErrorDialog = true
          },
        )
      }

      firebaseAnalytics?.logEvent(
        AnalyticsEvent.GENERATE_ACTION.id,
        Bundle().apply {
          putString("capability_name", task.id)
          putString("model_id", model.name)
        },
      )
    }
  }

  // Reset states on config changes.
  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      val (same, nonNumTurnsConfigChanged) = detectTinyGardenConfigChange(model)

      if (!same) {
        Log.d(TAG, "model config values changed.")
        if (nonNumTurnsConfigChanged) {
          Log.d(TAG, "need to reset engine")
          viewModel.resetEngine(
            context = context,
            model = model,
            tools = tools,
            onError = {
              errorDialogContent = it
              showErrorDialog = true
            },
          )
        } else {
          Log.d(TAG, "need to reset conversation")
          viewModel.resetConversation(
            model = model,
            tools = tools,
            prevSeed = "",
            prevPlots = "",
            prevAction = "",
            onError = { error ->
              errorDialogContent = error
              showErrorDialog = true
            },
          )
        }
      }
    }
  }

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
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.padding(
            bottom =
              if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) bottomPadding else 12.dp
          )
      ) {
        // A webview to load the game which is written in javascript.
        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
          AndroidView(
            modifier = Modifier.fillMaxHeight(),
            factory = { ctx ->
              createTinyGardenWebView(
                context = ctx,
                viewModel = viewModel,
                scope = scope,
                onWebViewReady = { wv -> webViewRef = wv },
              )
            },
          )

          SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = 12.dp))
        }

        // Text and voice input.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          TextAndVoiceInput(
            task = task,
            processing = uiState.processing,
            holdToDictateViewModel = holdToDictateViewModel,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
            onDone = { text -> processInstructionText(text = text) },
            onAmplitudeChanged = { curAmplitude = it },
            clearTextTrigger = clearTextTrigger,
            defaultTextInputMode = true,
          )
          Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (uiState.processing) {
              CircularProgressIndicator(
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 3.dp,
                modifier = Modifier.padding(end = 8.dp).size(24.dp),
              )
            } else {
              IconButton(
                onClick = { showConversationHistoryPanel = true },
                modifier = Modifier.padding(end = 8.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.History,
                  contentDescription = stringResource(R.string.cd_more_options),
                )
              }
            }
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

      // Conversation history panel.
      AnimatedVisibility(
        showConversationHistoryPanel,
        enter = slideInVertically { fullHeight -> fullHeight },
        exit = slideOutVertically { fullHeight -> fullHeight },
      ) {
        ConversationHistoryPanel(
          task = task,
          bottomPadding = bottomPadding,
          viewModel = viewModel,
          onDismiss = { showConversationHistoryPanel = false },
        )
      }
    }
  }

  if (showErrorDialog) {
    TinyGardenErrorDialog(
      errorContent = errorDialogContent,
      taskColor = taskColor,
      onDismiss = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      onReset = {
        showErrorDialog = false
        errorDialogContent = ""
        viewModel.resetEngine(
          context = context,
          model = model,
          tools = tools,
          onError = {
            errorDialogContent = it
            showErrorDialog = true
          },
        )
      },
    )
  }
}
