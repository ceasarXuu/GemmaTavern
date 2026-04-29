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

import android.util.Log
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selfgemma.talk.R
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.AudioAnimation
import selfgemma.talk.ui.common.ErrorDialog
import selfgemma.talk.ui.common.FloatingBanner
import selfgemma.talk.ui.common.RotationalLoader
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.theme.customColors
import kotlinx.coroutines.delay

private const val TAG = "AGChatPanel"
private const val AUTO_SCROLL_BOTTOM_TOLERANCE_PX = 90

/** Composable function for the main chat panel, displaying messages and handling user input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  selectedModel: Model,
  viewModel: ChatViewModel,
  innerPadding: PaddingValues,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onBenchmarkClicked: (Model, ChatMessage, warmUpIterations: Int, benchmarkIterations: Int) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  onStreamImageMessage: (Model, ChatMessageImage) -> Unit = { _, _ -> },
  onStreamEnd: (Int) -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onSkillClicked: () -> Unit = {},
  onImageSelected: (bitmaps: List<Bitmap>, selectedBitmapIndex: Int) -> Unit = { _, _ -> },
  showStopButtonInInputWhenInProgress: Boolean = false,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  emptyStateComposable: @Composable (Model) -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val messages = uiState.messagesByModel[selectedModel.name] ?: listOf()
  val streamingMessage = uiState.streamingMessagesByModel[selectedModel.name]
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val imageCountToLastConfigChange =
    remember(messages) {
      var imageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageImage) {
          imageCount += message.bitmaps.size
        }
      }
      imageCount
    }
  val audioClipMesssageCountToLastconfigChange =
    remember(messages) {
      var audioClipMessageCount = 0
      for (message in messages.reversed()) {
        if (message is ChatMessageConfigValuesChange) {
          break
        }
        if (message is ChatMessageAudioClip) {
          audioClipMessageCount++
        }
      }
      audioClipMessageCount
    }

  var curMessage by remember { mutableStateOf("") } // Correct state
  val focusManager = LocalFocusManager.current

  // Remember the LazyListState to control scrolling
  val listState = rememberLazyListState()
  val density = LocalDensity.current
  var showBenchmarkConfigsDialog by remember { mutableStateOf(false) }
  val benchmarkMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }

  var showErrorDialog by remember { mutableStateOf(false) }

  var showAudioRecorder by remember { mutableStateOf(false) }
  var curAmplitude by remember { mutableIntStateOf(0) }
  var pickedImagesCount by remember { mutableIntStateOf(0) }
  var pickedAudioClipsCount by remember { mutableIntStateOf(0) }

  var showImageLimitBanner by remember { mutableStateOf(false) }
  var previousMessageCount by remember(selectedModel.name) { mutableIntStateOf(0) }
  var isAudioPlaybackActive by remember(selectedModel.name) { mutableStateOf(false) }

  LaunchedEffect(showImageLimitBanner) {
    if (showImageLimitBanner) {
      delay(3000) // 3 seconds
      showImageLimitBanner = false
    }
  }

  // Keep track of the last message and last message content.
  val lastMessage: MutableState<ChatMessage?> = remember { mutableStateOf(null) }
  val lastMessageContent: MutableState<String> = remember { mutableStateOf("") }
  if (messages.isNotEmpty()) {
    val tmpLastMessage = messages.last()
    lastMessage.value = tmpLastMessage
    if (tmpLastMessage is ChatMessageText) {
      lastMessageContent.value = tmpLastMessage.content
    }
  }

  // Scroll to bottom when IME is toggled.
  LaunchedEffect(WindowInsets.ime.getBottom(density)) {
    scrollToBottom(listState = listState, animate = true)
  }

  // Auto-scroll to bottom when a new message is added or message type changes.
  LaunchedEffect(messages.size, lastMessage.value?.type) {
    if (messages.isEmpty()) {
      previousMessageCount = 0
    } else {
      val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
      val shouldScroll =
        shouldAutoScrollOnTailUpdate(
          previousMessageCount = previousMessageCount,
          currentMessageCount = messages.size,
          lastVisibleItemIndex = lastVisibleItem?.index,
          lastVisibleItemBottomOffsetPx = lastVisibleItem?.let { it.offset + it.size },
          viewportEndOffsetPx = listState.layoutInfo.viewportEndOffset,
          isAudioPlaybackActive = isAudioPlaybackActive,
        )
      if (messages.size > previousMessageCount && isAudioPlaybackActive) {
        Log.d(
          TAG,
          "Suppressing tail auto-scroll while audio playback is active. " +
            "previousCount=$previousMessageCount currentCount=${messages.size}"
        )
      }
      if (shouldScroll) {
        scrollToBottom(listState = listState, animate = true)
      }
      previousMessageCount = messages.size
    }
  }

  // Scroll to keep up with streaming, ONLY if we are already at the bottom.
  LaunchedEffect(lastMessage.value, lastMessageContent.value, lastMessage.value?.latencyMs) {
    if (messages.isNotEmpty() && !isAudioPlaybackActive) {
      val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
      if (lastVisibleItem != null) {
        // Determines if an automatic scroll is necessary. It is true if the scroll position is
        // close to the bottom (within 90 pixels of the end offset. 90 is slightly taller than
        // the "show stats" chip).
        val canScroll =
          lastVisibleItem.index == messages.size - 1 &&
            lastVisibleItem.offset + lastVisibleItem.size - listState.layoutInfo.viewportEndOffset <
              AUTO_SCROLL_BOTTOM_TOLERANCE_PX
        if (canScroll) {
          scrollToBottom(listState = listState, animate = true)
        }
      }
    }
  }

  val nestedScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // If downward scroll, clear the focus from any currently focused composable.
        // This is useful for dismissing software keyboards or hiding text input fields
        // when the user starts scrolling down a list.
        if (available.y > 0) {
          focusManager.clearFocus()
        }
        // Let LazyColumn handle the scroll
        return Offset.Zero
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]

  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    // Audio record animation.
    AnimatedVisibility(
      showAudioRecorder,
      enter =
        slideInVertically(
          animationSpec =
            spring(
              stiffness = Spring.StiffnessLow,
              visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        ) {
          it
        } + fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)),
      exit = fadeOut(),
      modifier = Modifier.graphicsLayer { alpha = 0.8f },
    ) {
      AudioAnimation(bgColor = MaterialTheme.colorScheme.surface, amplitude = curAmplitude)
    }

    Column(
      modifier = modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()
    ) {
      Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.weight(1f)) {
        val cdChatPanel = stringResource(R.string.cd_chat_panel)
        LazyColumn(
          modifier =
            Modifier.fillMaxSize().nestedScroll(nestedScrollConnection).semantics {
              contentDescription = cdChatPanel
            },
          state = listState,
          verticalArrangement = Arrangement.Top,
        ) {
          itemsIndexed(messages) { index, message ->
            val imageHistoryCurIndex = remember { mutableIntStateOf(0) }
            ChatMessageRow(
              index = index,
              message = message,
              task = task,
              selectedModel = selectedModel,
              isLast = index == messages.lastIndex,
              inProgress = uiState.inProgress,
              imageHistoryCurIndex = imageHistoryCurIndex,
              onSendMessage = onSendMessage,
              onRunAgainClicked = onRunAgainClicked,
              onImageSelected = onImageSelected,
              onAudioPlaybackStateChanged = { isPlaying -> isAudioPlaybackActive = isPlaying },
              onRequestBenchmark = { msg ->
                showBenchmarkConfigsDialog = true
                benchmarkMessage.value = msg
              },
            )
          }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(vertical = 4.dp))

        // Show empty state.
        if (messages.isEmpty() && pickedImagesCount == 0 && pickedAudioClipsCount == 0) {
          emptyStateComposable(selectedModel)
        }
        // Loading screen when model is initialized for that first time.
        val isFirstInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING &&
            modelInitializationStatus.isFirstInitialization(selectedModel)
        FirstInitializingOverlay(visible = isFirstInitializing)
      }

      MessageInputText(
        task = task,
        curMessage = curMessage,
        inProgress = uiState.inProgress,
        isResettingSession = uiState.isResettingSession,
        modelPreparing = uiState.preparing,
        imageCount = imageCountToLastConfigChange,
        audioClipMessageCount = audioClipMesssageCountToLastconfigChange,
        modelInitializing =
          modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZING,
        onValueChanged = { curMessage = it },
        onSendMessage = {
          onSendMessage(selectedModel, it)
          curMessage = ""
          // Hide software keyboard.
          focusManager.clearFocus()
        },
        onOpenPromptTemplatesClicked = {
          onSendMessage(
            selectedModel,
            listOf(
              ChatMessagePromptTemplates(
                templates = selectedModel.llmPromptTemplates,
                showMakeYourOwn = false,
              )
            ),
          )
        },
        onStopButtonClicked = onStopButtonClicked,
        onSetAudioRecorderVisible = { start ->
          showAudioRecorder = start
          if (!showAudioRecorder) {
            curAmplitude = 0
          }
        },
        onAmplitudeChanged = { curAmplitude = it },
        onSkillsClicked = onSkillClicked,
        onPickedImagesChanged = { pickedImagesCount = it.size },
        onPickedAudioClipsChanged = { pickedAudioClipsCount = it.size },
        showPromptTemplatesInMenu = false,
        showSkillsPicker = task.id === BuiltInTaskId.LLM_AGENT_CHAT,
        showImagePicker = selectedModel.llmSupportImage && showImagePicker,
        showAudioPicker = selectedModel.llmSupportAudio && showAudioPicker,
        showStopButtonWhenInProgress = showStopButtonInInputWhenInProgress,
        onImageLimitExceeded = { showImageLimitBanner = true },
      )
    }
  }

  // Error dialog.
  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = { showErrorDialog = false },
    )
  }

  // Benchmark config dialog.
  if (showBenchmarkConfigsDialog) {
    BenchmarkConfigDialog(
      onDismissed = { showBenchmarkConfigsDialog = false },
      messageToBenchmark = benchmarkMessage.value,
      onBenchmarkClicked = { message, warmUpIterations, benchmarkIterations ->
        onBenchmarkClicked(selectedModel, message, warmUpIterations, benchmarkIterations)
      },
    )
  }
}

private suspend fun scrollToBottom(listState: LazyListState, animate: Boolean = false) {
  val itemCount = listState.layoutInfo.totalItemsCount
  if (itemCount > 0) {
    if (animate) {
      listState.animateScrollToItem(itemCount - 1, scrollOffset = 1000000)
    } else {
      listState.scrollToItem(itemCount - 1, scrollOffset = 1000000)
    }
  }
}

internal fun shouldAutoScrollOnTailUpdate(
  previousMessageCount: Int,
  currentMessageCount: Int,
  lastVisibleItemIndex: Int?,
  lastVisibleItemBottomOffsetPx: Int?,
  viewportEndOffsetPx: Int,
  isAudioPlaybackActive: Boolean,
  bottomTolerancePx: Int = AUTO_SCROLL_BOTTOM_TOLERANCE_PX,
): Boolean {
  if (currentMessageCount <= 0 || isAudioPlaybackActive) {
    return false
  }
  if (previousMessageCount <= 0) {
    return true
  }
  val lastVisibleIndex = lastVisibleItemIndex ?: return false
  val lastVisibleBottomOffsetPx = lastVisibleItemBottomOffsetPx ?: return false
  val anchorIndex =
    if (currentMessageCount > previousMessageCount) {
      previousMessageCount - 1
    } else {
      currentMessageCount - 1
    }
  if (lastVisibleIndex < anchorIndex) {
    return false
  }
  return lastVisibleBottomOffsetPx - viewportEndOffsetPx < bottomTolerancePx
}
