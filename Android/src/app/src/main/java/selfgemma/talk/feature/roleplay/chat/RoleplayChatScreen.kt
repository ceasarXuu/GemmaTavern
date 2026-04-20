package selfgemma.talk.feature.roleplay.chat

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import selfgemma.talk.AppTopBar
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.AudioPlaybackPanel
import selfgemma.talk.ui.common.chat.MessageInputText
import selfgemma.talk.ui.common.chat.rememberStreamingTokenSpeed
import selfgemma.talk.ui.llmchat.LlmModelInstance
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.ui.common.TopBarOverflowMenuButton
import selfgemma.talk.ui.common.MarkdownText

private const val TAG = "RoleplayChatScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  onOpenModelLibrary: () -> Unit,
  onOpenRoleEditor: (String) -> Unit,
  onOpenPersonaEditor: (String?) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: RoleplayChatViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val showLiveTokenSpeed =
    remember(modelManagerUiState.settingsUpdateTrigger) {
      modelManagerViewModel.isLiveTokenSpeedEnabled()
    }
  val activeModel = uiState.session?.activeModelId?.let(modelManagerViewModel::getModelByName)
  val historicalWarmupRequirements =
    remember(uiState.messages) {
      resolveRoleplaySendRequirements(
        messages = emptyList(),
        conversationMessages = uiState.messages,
      )
    }
  val downloadedModels =
    remember(
      modelManagerUiState.modelDownloadStatus,
      modelManagerUiState.modelImportingUpdateTrigger,
    ) {
      modelManagerViewModel.getAllDownloadedModels()
    }
  val llmChatTask = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
  val listState = rememberLazyListState()
  val lastMessage = uiState.messages.lastOrNull()
  val roleName = uiState.role?.name ?: stringResource(R.string.chat_assistant)
  val userPersonaName = uiState.userPersonaName.ifBlank { stringResource(R.string.chat_you) }
  val latestAssistantMessage =
    remember(uiState.messages) {
      uiState.messages.lastOrNull { it.side == MessageSide.ASSISTANT }
    }
  val streamingAssistantText =
    remember(uiState.messages) {
      uiState.messages
        .lastOrNull { it.side == MessageSide.ASSISTANT && it.status == MessageStatus.STREAMING }
        ?.content
        .orEmpty()
    }
  val tokenSpeed =
    rememberStreamingTokenSpeed(
      streamingText = streamingAssistantText,
      isStreaming = showLiveTokenSpeed && uiState.inProgress,
      completedText =
        latestAssistantMessage
          ?.takeIf { it.status == MessageStatus.COMPLETED }
          ?.content
          .orEmpty(),
      completedLatencyMs =
        latestAssistantMessage
          ?.takeIf { it.status == MessageStatus.COMPLETED }
          ?.latencyMs,
      completedAtEpochMs =
        latestAssistantMessage
          ?.takeIf { it.status == MessageStatus.COMPLETED }
          ?.updatedAt,
    )
  val tokenSpeedSubtitle =
    tokenSpeed
      ?.takeIf { showLiveTokenSpeed }
      ?.let { stringResource(R.string.chat_token_speed_format, it) }
      .orEmpty()
  val imeBottom = WindowInsets.ime.getBottom(density)
  val screenOpenTimestamp = remember { SystemClock.elapsedRealtime() }
  var hasCompletedInitialPositioning by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var hasLoggedInitialPositioning by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var previousMessageCount by rememberSaveable(uiState.session?.id) { mutableStateOf(0) }
  var composerBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
  val latestListItemIndex =
    remember(uiState.messages.size) {
      calculateLatestListItemIndex(
        messageCount = uiState.messages.size,
      )
    }

  TrackPerformanceState(
    key = "RoleplayChatList",
    value = if (listState.isScrollInProgress) "scrolling" else null,
  )

  val activeModelInstance = activeModel?.instance as? LlmModelInstance
  val activeModelDownloadStatus =
    activeModel?.let { modelManagerUiState.modelDownloadStatus[it.name]?.status }
  val activeModelStatus = activeModel?.let { modelManagerUiState.modelInitializationStatus[it.name]?.status }
  val isActiveModelInitialized =
    activeModel != null && activeModelStatus == ModelInitializationStatusType.INITIALIZED
  val isActiveModelInitializing =
    activeModel != null &&
      (activeModel.initializing || activeModelStatus == ModelInitializationStatusType.INITIALIZING)
  var showMenu by remember { mutableStateOf(false) }
  var showModelPicker by remember { mutableStateOf(false) }
  var showContinuityDebug by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var selectedMessageActionId by rememberSaveable(uiState.session?.id) { mutableStateOf<String?>(null) }
  val selectedMessageForAction =
    remember(uiState.messages, selectedMessageActionId) {
      selectedMessageActionId?.let { actionId -> uiState.messages.firstOrNull { it.id == actionId } }
    }
  val updateOverflowMenuVisibility: (Boolean) -> Unit = { expanded ->
    showMenu = expanded
    val event = if (expanded) "opened" else "dismissed"
    Log.d(TAG, "chat overflow menu $event sessionId=${uiState.session?.id}")
  }
  val handleNavigateUp: () -> Unit = {
    when {
      showModelPicker -> {
        showModelPicker = false
        Log.d(TAG, "dismiss model picker before navigating up sessionId=${uiState.session?.id}")
      }
      showContinuityDebug -> {
        showContinuityDebug = false
        Log.d(TAG, "dismiss continuity debug before navigating up sessionId=${uiState.session?.id}")
      }
      showMenu -> {
        showMenu = false
        Log.d(TAG, "dismiss overflow menu before navigating up sessionId=${uiState.session?.id}")
      }
      else -> {
        Log.d(TAG, "navigate up from chat sessionId=${uiState.session?.id}")
        navigateUp()
      }
    }
  }
  val handleRoleAvatarClick: (() -> Unit)? =
    uiState.role?.id?.let { roleId ->
      {
        Log.d(TAG, "open role editor from chat avatar sessionId=${uiState.session?.id} roleId=$roleId")
        onOpenRoleEditor(roleId)
      }
    }
  val handlePersonaAvatarClick: () -> Unit = {
    val slotId = uiState.userPersonaSlotId.ifBlank { null }
    Log.d(TAG, "open persona editor from chat avatar sessionId=${uiState.session?.id} slotId=$slotId")
    onOpenPersonaEditor(slotId)
  }

  BackHandler(enabled = showMenu || showModelPicker || showContinuityDebug) {
    Log.d(
      TAG,
      "intercept back to dismiss transient chat UI sessionId=${uiState.session?.id} showMenu=$showMenu showModelPicker=$showModelPicker",
    )
    handleNavigateUp()
  }

  LaunchedEffect(activeModel?.name) {
    if (activeModel != null) {
      Log.d(TAG, "sync active chat model to recent selection model=${activeModel.name}")
      modelManagerViewModel.selectModel(activeModel)
    }
  }

  LaunchedEffect(
    activeModel?.name,
    activeModelDownloadStatus,
    activeModelStatus,
    activeModelInstance?.supportImage,
    activeModelInstance?.supportAudio,
    historicalWarmupRequirements.needsImage,
    historicalWarmupRequirements.needsAudio,
  ) {
    val currentModel = activeModel ?: return@LaunchedEffect
    val warmupAction =
      resolveRoleplayWarmupAction(
        downloadStatus = activeModelDownloadStatus,
        isInitializing = isActiveModelInitializing,
        hasInstance = activeModelInstance != null,
        supportImage = activeModelInstance?.supportImage == true,
        supportAudio = activeModelInstance?.supportAudio == true,
        needsImage = historicalWarmupRequirements.needsImage,
        needsAudio = historicalWarmupRequirements.needsAudio,
      )
    when (warmupAction) {
      RoleplayWarmupAction.NONE -> Unit
      RoleplayWarmupAction.TEXT_ONLY -> {
        val task = llmChatTask ?: return@LaunchedEffect
        Log.d(
          TAG,
          "warm roleplay active model on screen entry sessionId=${uiState.session?.id} model=${currentModel.name} mode=text-only",
        )
        modelManagerViewModel.initializeModel(
          context = context,
          task = task,
          model = currentModel,
          force = activeModelStatus == ModelInitializationStatusType.INITIALIZED,
        )
      }
      RoleplayWarmupAction.MULTIMODAL -> {
        Log.d(
          TAG,
          "warm roleplay active model on screen entry sessionId=${uiState.session?.id} model=${currentModel.name} mode=multimodal needsImage=${historicalWarmupRequirements.needsImage} needsAudio=${historicalWarmupRequirements.needsAudio}",
        )
        modelManagerViewModel.initializeLlmModel(
          context = context,
          model = currentModel,
          supportImage = historicalWarmupRequirements.needsImage,
          supportAudio = historicalWarmupRequirements.needsAudio,
          force =
            activeModelInstance != null ||
              activeModelStatus == ModelInitializationStatusType.INITIALIZED,
        )
      }
    }
  }

  LaunchedEffect(imeBottom, latestListItemIndex, hasCompletedInitialPositioning) {
    if (
      hasCompletedInitialPositioning &&
        imeBottom > 0 &&
        latestListItemIndex >= 0 &&
        shouldKeepLatestMessageVisible(listState, latestListItemIndex)
    ) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
    }
  }

  LaunchedEffect(latestListItemIndex, uiState.messages.size) {
    if (latestListItemIndex < 0) {
      previousMessageCount = 0
      return@LaunchedEffect
    }

    if (!hasCompletedInitialPositioning) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
      hasCompletedInitialPositioning = true
      previousMessageCount = uiState.messages.size
      if (!hasLoggedInitialPositioning) {
        hasLoggedInitialPositioning = true
        Log.d(
          TAG,
          "initial chat positioned sessionId=${uiState.session?.id} messageCount=${uiState.messages.size} elapsed=${SystemClock.elapsedRealtime() - screenOpenTimestamp}ms",
        )
      }
      return@LaunchedEffect
    }

    val messageCountIncreased = uiState.messages.size > previousMessageCount
    previousMessageCount = uiState.messages.size
    if (messageCountIncreased) {
      Log.d(
        TAG,
        "auto scroll to latest after message append sessionId=${uiState.session?.id} messageCount=${uiState.messages.size} latestItemIndex=$latestListItemIndex",
      )
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = true)
    }
  }

  LaunchedEffect(lastMessage?.id, lastMessage?.status, hasCompletedInitialPositioning) {
    if (
      hasCompletedInitialPositioning &&
        !listState.isScrollInProgress &&
        latestListItemIndex >= 0 &&
        shouldKeepLatestMessageVisible(listState, latestListItemIndex)
    ) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
    }
  }

  Box(
    modifier =
      modifier.fillMaxSize().let { baseModifier ->
        if (imeBottom > 0) {
          baseModifier.pointerInteropFilter { motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
              val tapPosition = Offset(motionEvent.rawX, motionEvent.rawY)
              val tappedInsideComposer = composerBoundsInWindow?.contains(tapPosition) == true
              if (!tappedInsideComposer) {
                Log.d(TAG, "keyboard dismissed by outside tap sessionId=${uiState.session?.id}")
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
              }
            }
            false
          }
        } else {
          baseModifier
        }
      }
  ) {
    Scaffold(
      topBar = {
        AppTopBar(
          title = uiState.role?.name ?: stringResource(R.string.chat_title),
          subtitle = tokenSpeedSubtitle,
          leftAction = AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = handleNavigateUp),
          rightActionContent = {
            TopBarOverflowMenuButton(
              expanded = showMenu,
              onExpandedChange = updateOverflowMenuVisibility,
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_switch_model)) },
                onClick = {
                  showMenu = false
                  showModelPicker = true
                },
                leadingIcon = {
                  Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_open_model_library_menu)) },
                onClick = {
                  showMenu = false
                  onOpenModelLibrary()
                },
                leadingIcon = {
                  Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_continuity_debug_action)) },
                onClick = {
                  showMenu = false
                  showContinuityDebug = true
                },
              )
            }
          },
        )
      },
  ) { innerPadding ->
    if (uiState.loading) {
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.chat_loading_session), style = MaterialTheme.typography.headlineSmall)
      }
      return@Scaffold
    }

    if (activeModel == null && uiState.messages.isEmpty()) {
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        Text(stringResource(R.string.chat_missing_model_title), style = MaterialTheme.typography.headlineSmall)
        Text(
          stringResource(R.string.chat_missing_model_content),
          modifier = Modifier.padding(top = 12.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        downloadedModels.firstOrNull()?.let { fallbackModel ->
          OutlinedButton(
            modifier = Modifier.padding(top = 20.dp),
            onClick = { viewModel.switchModel(fallbackModel.name) },
          ) {
            Text(stringResource(R.string.chat_use_model, fallbackModel.displayName.ifEmpty { fallbackModel.name }))
          }
        }
        FilledTonalButton(
          modifier = Modifier.padding(top = 12.dp),
          onClick = onOpenModelLibrary,
        ) {
          Text(stringResource(R.string.chat_open_model_library))
        }
      }
      return@Scaffold
    }

    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .imePadding()
    ) {
      if (activeModel == null) {
        MissingModelBanner(
          downloadedModels = downloadedModels,
          onSwitchModel = viewModel::switchModel,
          onOpenModelLibrary = onOpenModelLibrary,
        )
      }

      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        items(uiState.messages, key = { it.id }) { message ->
          ChatMessageBubble(
            message = message,
            roleName = roleName,
            roleAvatarUri = uiState.role?.primaryAvatarUri(),
            userName = userPersonaName,
            userAvatarUri = uiState.userPersonaAvatarUri,
            animateOnEnter = hasCompletedInitialPositioning && message.id == lastMessage?.id,
            onRoleAvatarClick = handleRoleAvatarClick,
            onUserAvatarClick = handlePersonaAvatarClick,
            onMessageLongPress = { pressedMessage ->
              if (!pressedMessage.supportsRoleplayActions()) {
                return@ChatMessageBubble
              }
              selectedMessageActionId = pressedMessage.id
              Log.d(
                TAG,
                "open message actions sessionId=${uiState.session?.id} messageId=${pressedMessage.id} side=${pressedMessage.side} canonical=${pressedMessage.isCanonical}",
              )
            },
          )
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        uiState.errorMessage?.let { errorMessage ->
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }

        if (llmChatTask != null) {
          Box(
            modifier =
              Modifier.onGloballyPositioned { coordinates ->
                composerBoundsInWindow = coordinates.boundsInWindow()
              }
          ) {
            MessageInputText(
              task = llmChatTask,
              curMessage = uiState.draft,
              isResettingSession = false,
              inProgress = uiState.inProgress,
              imageCount = 0,
              audioClipMessageCount = 0,
              modelInitializing = isActiveModelInitializing,
              modelPreparing = uiState.inProgress && uiState.messages.lastOrNull()?.status == MessageStatus.STREAMING,
              onValueChanged = viewModel::updateDraft,
              onSendMessage = { messages ->
                activeModel?.let { currentModel ->
                  val sendRequirements =
                    resolveRoleplaySendRequirements(
                      messages = messages,
                      conversationMessages = uiState.messages,
                    )
                  val submitMessages: () -> Unit = {
                    sendRequirements.primaryTextInput?.let(modelManagerViewModel::addTextInputHistory)
                    viewModel.sendChatMessages(
                      model = currentModel,
                      messages = messages,
                      clearDraft = true,
                    )
                  }
                  val initializedInstance = currentModel.instance as? LlmModelInstance
                  val sendExecutionPlan =
                    resolveRoleplaySendExecutionPlan(
                      needsImage = sendRequirements.needsImage,
                      needsAudio = sendRequirements.needsAudio,
                      hasReusableMultimodalSession =
                        canReuseRoleplayModelSession(
                          instance = initializedInstance,
                          needsImage = sendRequirements.needsImage,
                          needsAudio = sendRequirements.needsAudio,
                        ),
                      hasInitializedSession =
                        initializedInstance != null || isActiveModelInitialized || currentModel.initializing,
                    )
                  Log.d(
                    TAG,
                    "roleplay send requested sessionId=${uiState.session?.id} model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio} hasInitializedInstance=${initializedInstance != null} isModelInitializing=$isActiveModelInitializing queueImmediately=${sendExecutionPlan.queueImmediately} warmupAction=${sendExecutionPlan.warmupAction}",
                  )

                  if (sendExecutionPlan.queueImmediately) {
                    submitMessages()
                    when (sendExecutionPlan.warmupAction) {
                      RoleplayWarmupAction.NONE -> {
                        Log.d(
                          TAG,
                          "queue roleplay send immediately sessionId=${uiState.session?.id} model=${currentModel.name} using current session",
                        )
                      }
                      RoleplayWarmupAction.MULTIMODAL -> {
                        Log.d(
                          TAG,
                          "queue roleplay send immediately and reinitialize multimodal session sessionId=${uiState.session?.id} model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio}",
                        )
                        modelManagerViewModel.initializeLlmModel(
                          context = context,
                          model = currentModel,
                          supportImage = sendRequirements.needsImage,
                          supportAudio = sendRequirements.needsAudio,
                          force = true,
                        )
                      }
                      RoleplayWarmupAction.TEXT_ONLY -> {
                        Log.d(
                          TAG,
                          "queue roleplay send immediately sessionId=${uiState.session?.id} model=${currentModel.name} while text session is already warming",
                        )
                      }
                    }
                  } else {
                    when (sendExecutionPlan.warmupAction) {
                      RoleplayWarmupAction.NONE -> {
                      Log.d(
                        TAG,
                        "dispatch roleplay text send with existing or warming session sessionId=${uiState.session?.id} model=${currentModel.name}",
                      )
                      submitMessages()
                      }
                      RoleplayWarmupAction.TEXT_ONLY -> {
                        Log.d(
                          TAG,
                          "initialize text roleplay session before send sessionId=${uiState.session?.id} model=${currentModel.name}",
                        )
                        modelManagerViewModel.initializeModel(
                          context = context,
                          task = llmChatTask,
                          model = currentModel,
                          onDone = submitMessages,
                        )
                      }
                      RoleplayWarmupAction.MULTIMODAL -> {
                        Log.d(
                          TAG,
                          "initialize multimodal roleplay session before send sessionId=${uiState.session?.id} model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio}",
                        )
                        modelManagerViewModel.initializeLlmModel(
                          context = context,
                          model = currentModel,
                          supportImage = sendRequirements.needsImage,
                          supportAudio = sendRequirements.needsAudio,
                          force = true,
                          onDone = submitMessages,
                        )
                      }
                    }
                  }
                }
              },
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
            draft = uiState.draft,
            onDraftChange = viewModel::updateDraft,
            canSend = activeModel != null && uiState.draft.isNotBlank(),
            modifier =
              Modifier.onGloballyPositioned { coordinates ->
                composerBoundsInWindow = coordinates.boundsInWindow()
              },
            onSend = {
              activeModel?.let { currentModel ->
                viewModel.sendMessage(currentModel)
              }
            },
          )
        }
      }
    }
  }

  selectedMessageForAction?.let { selectedMessage ->
    RoleplayMessageActionsDialog(
      message = selectedMessage,
      onDismiss = {
        Log.d(TAG, "dismiss message actions sessionId=${uiState.session?.id} messageId=${selectedMessage.id}")
        selectedMessageActionId = null
      },
      onPinMessage = { actionMessage ->
        Log.d(TAG, "pin message action sessionId=${uiState.session?.id} messageId=${actionMessage.id}")
        selectedMessageActionId = null
        viewModel.pinMessage(actionMessage)
      },
      onRollbackToMessage = { actionMessage ->
        Log.d(TAG, "rollback message action sessionId=${uiState.session?.id} messageId=${actionMessage.id}")
        selectedMessageActionId = null
        viewModel.rollbackToMessage(actionMessage.id)
      },
      onRegenerateAssistantMessage = { actionMessage ->
        val currentModel = activeModel ?: return@RoleplayMessageActionsDialog
        Log.d(TAG, "regenerate message action sessionId=${uiState.session?.id} messageId=${actionMessage.id} model=${currentModel.name}")
        selectedMessageActionId = null
        viewModel.regenerateAssistantMessage(actionMessage.id, currentModel)
      },
      onEditMessage = { actionMessage ->
        Log.d(TAG, "edit message action sessionId=${uiState.session?.id} messageId=${actionMessage.id}")
        selectedMessageActionId = null
        viewModel.editMessageFromHere(actionMessage.id)
      },
      allowContinuityActions = !uiState.inProgress && !uiState.hasPendingSends,
      allowRegenerate = activeModel != null && !uiState.inProgress && !uiState.hasPendingSends,
    )
  }

  if (showContinuityDebug) {
    ContinuityDebugDialog(
      debugState = uiState.continuityDebug,
      onDismiss = { showContinuityDebug = false },
    )
  }

  if (showModelPicker && downloadedModels.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { showModelPicker = false },
      title = { Text(stringResource(R.string.chat_select_model_title)) },
      text = {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            downloadedModels.forEach { model ->
              val isSelected = model.name == activeModel?.name
              ListItem(
                headlineContent = {
                  Text(
                    text = model.displayName.ifEmpty { model.name },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                  )
                },
                supportingContent = {
                  if (isSelected) {
                    Text(stringResource(R.string.chat_current_model))
                  }
                },
                trailingContent = {
                  RadioButton(
                    selected = isSelected,
                    onClick = null,
                  )
                },
                colors =
                  ListItemDefaults.colors(
                    containerColor =
                      if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                      } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                      }
                  ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(MaterialTheme.shapes.medium)
                  .selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                  ) {
                    viewModel.switchModel(model.name)
                    showModelPicker = false
                  }
              )
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = { showModelPicker = false }) {
            Text(stringResource(R.string.cancel))
          }
        },
      )
    }
  }
}

@Composable
private fun ChatMessageBubble(
  message: Message,
  roleName: String,
  roleAvatarUri: String?,
  userName: String,
  userAvatarUri: String?,
  animateOnEnter: Boolean,
  onRoleAvatarClick: (() -> Unit)?,
  onUserAvatarClick: (() -> Unit)?,
  onMessageLongPress: ((Message) -> Unit)?,
) {
  val isUser = message.side == MessageSide.USER
  val isImageMessage = message.kind == MessageKind.IMAGE
  val bubbleShape =
    if (isUser) {
      RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp)
    } else {
      RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 8.dp, bottomEnd = 24.dp)
    }
  val bubbleColor =
    if (isUser) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerHigh
    }
  val bubbleTextColor =
    if (isUser) {
      MaterialTheme.colorScheme.onPrimaryContainer
    } else {
      MaterialTheme.colorScheme.onSurface
    }
  val content: @Composable () -> Unit = {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
      verticalAlignment = Alignment.Top,
    ) {
      if (!isUser) {
        RoleAvatar(
          name = roleName,
          avatarUri = roleAvatarUri,
          onClick = onRoleAvatarClick,
          modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
      }

      Column(
        modifier = Modifier.widthIn(max = if (isImageMessage) 280.dp else 340.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = if (isUser) userName else roleName,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 4.dp),
        )

        Surface(
          modifier =
            if (message.supportsRoleplayActions() && onMessageLongPress != null) {
              Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onMessageLongPress(message) },
              )
            } else {
              Modifier
            },
          shape = bubbleShape,
          tonalElevation = 0.dp,
          color = bubbleColor,
        ) {
          if (message.status == MessageStatus.STREAMING && message.content.isBlank() && message.kind == MessageKind.TEXT) {
            TypingIndicator()
          } else if (isImageMessage) {
            RoleplayImageMessageBody(
              message = message,
              imageShape = bubbleShape,
            )
          } else {
            Column(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              RenderRoleplayMessageBody(
                message = message,
                isUser = isUser,
                textColor = bubbleTextColor,
              )
            }
          }
        }

        if (isUser) {
          Text(
            text = stringResource(R.string.chat_message_read),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }

      if (isUser) {
        Spacer(modifier = Modifier.width(8.dp))
        RoleAvatar(
          name = userName,
          avatarUri = userAvatarUri,
          onClick = onUserAvatarClick,
          modifier = Modifier.size(32.dp),
        )
      }
    }
  }

  if (animateOnEnter) {
    AnimatedVisibility(
      visible = true,
      enter = fadeIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        )
      ) + slideInHorizontally(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        initialOffsetX = { if (isUser) it / 3 else -it / 3 },
      ) + scaleIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy,
        ),
        initialScale = 0.9f,
      ),
      exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
      content()
    }
  } else {
    content()
  }
}

@Composable
private fun ContinuityDebugDialog(
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
private fun RoleplayMessageActionsDialog(
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

@Composable
private fun RenderRoleplayMessageBody(
  message: Message,
  isUser: Boolean,
  textColor: Color,
) {
  when (message.kind) {
    MessageKind.IMAGE ->
      RoleplayImageMessageBody(
        message = message,
        imageShape = MaterialTheme.shapes.large,
      )
    MessageKind.AUDIO -> RoleplayAudioMessageBody(message = message)
    else ->
      RenderChatMessageText(
        text = message.displayText(),
        textColor = textColor,
      )
  }
}

@Composable
private fun RoleplayImageMessageBody(
  message: Message,
  imageShape: Shape,
) {
  val imagePaths =
    remember(message.metadataJson) {
      message
        .roleplayMessageMediaPayload()
        ?.attachments
        ?.filter { it.type == RoleplayMessageAttachmentType.IMAGE }
        ?.map { it.filePath }
        .orEmpty()
    }
  val bitmaps by
    produceState<List<android.graphics.Bitmap>>(initialValue = emptyList(), key1 = imagePaths) {
      value =
        withContext(Dispatchers.IO) {
          imagePaths.mapNotNull(::decodeRoleplayBitmap)
        }
    }
  if (bitmaps.isEmpty()) {
    RenderChatMessageText(
      text = message.displayText(),
      textColor = MaterialTheme.colorScheme.onSurface,
    )
    return
  }

  Row(
    modifier =
      Modifier
        .horizontalScroll(rememberScrollState())
        .padding(all = if (bitmaps.size > 1) 8.dp else 0.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    bitmaps.forEach { bitmap ->
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_image_thumbnail),
        contentScale = ContentScale.Crop,
        modifier =
          roleplayImageModifier(bitmap = bitmap)
            .clip(if (bitmaps.size == 1) imageShape else MaterialTheme.shapes.large),
      )
    }
  }
}

private fun roleplayImageModifier(bitmap: android.graphics.Bitmap): Modifier {
  val imageWidth = bitmap.width.coerceAtLeast(1)
  val imageHeight = bitmap.height.coerceAtLeast(1)
  val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
  return if (aspectRatio >= 1f) {
    Modifier.width(236.dp).aspectRatio(aspectRatio)
  } else {
    Modifier.height(280.dp).aspectRatio(aspectRatio)
  }
}

private fun decodeRoleplayBitmap(filePath: String): android.graphics.Bitmap? {
  return runCatching {
      val bounds =
        BitmapFactory.Options().apply {
          inJustDecodeBounds = true
        }
      BitmapFactory.decodeFile(filePath, bounds)
      val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
      val sampleSize =
        when {
          maxDimension > 4096 -> 8
          maxDimension > 2048 -> 4
          maxDimension > 1024 -> 2
          else -> 1
        }
      BitmapFactory.decodeFile(
        filePath,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        },
      )
    }
    .getOrNull()
}

@Composable
private fun RoleplayAudioMessageBody(message: Message) {
  val audioAttachments =
    remember(message.metadataJson) {
      message
        .roleplayMessageMediaPayload()
        ?.attachments
        ?.filter { it.type == RoleplayMessageAttachmentType.AUDIO }
        .orEmpty()
    }
  val audioPayloads by
    produceState<List<Pair<ByteArray, Int>>>(initialValue = emptyList(), key1 = audioAttachments) {
      value =
        withContext(Dispatchers.IO) {
          audioAttachments.mapNotNull { attachment ->
            val sampleRate = attachment.sampleRate ?: return@mapNotNull null
            val audioData = runCatching { java.io.File(attachment.filePath).readBytes() }.getOrNull()
            audioData?.let { it to sampleRate }
          }
        }
    }
  if (audioPayloads.isEmpty()) {
    RenderChatMessageText(
      text = message.displayText(),
      textColor = MaterialTheme.colorScheme.onSurface,
    )
    return
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    audioPayloads.forEach { (audioData, sampleRate) ->
      AudioPlaybackPanel(
        audioData = audioData,
        sampleRate = sampleRate,
        isRecording = false,
      )
    }
  }
}

@Composable
private fun RenderChatMessageText(
  text: String,
  textColor: Color,
) {
  when {
    text.looksLikeHtml() -> HtmlText(text = text, textColor = textColor)
    text.looksLikeMarkdown() -> MarkdownText(text = text, textColor = textColor, linkColor = textColor)
    else ->
      Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 22.sp,
        color = textColor,
      )
  }
}

@Composable
private fun HtmlText(
  text: String,
  textColor: Color,
) {
  val context = LocalContext.current
  val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
  AndroidView(
    factory = {
      TextView(context).apply {
        setTextColor(textColor.toArgb())
        setTextSize(textSize)
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        setLineSpacing(0f, 1.2f)
      }
    },
    update = { textView ->
      textView.setTextColor(textColor.toArgb())
      textView.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    },
  )
}

@Composable
private fun MissingModelBanner(
  downloadedModels: List<Model>,
  onSwitchModel: (String) -> Unit,
  onOpenModelLibrary: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(R.string.chat_missing_model_title),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(R.string.chat_missing_model_content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        downloadedModels.firstOrNull()?.let { fallbackModel ->
          OutlinedButton(onClick = { onSwitchModel(fallbackModel.name) }) {
            Text(
              stringResource(
                R.string.chat_use_model,
                fallbackModel.displayName.ifEmpty { fallbackModel.name },
              )
            )
          }
        }
        FilledTonalButton(onClick = onOpenModelLibrary) {
          Text(stringResource(R.string.chat_open_model_library))
        }
      }
    }
  }
}

@Composable
private fun ChatComposer(
  draft: String,
  onDraftChange: (String) -> Unit,
  canSend: Boolean,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TextField(
      modifier = Modifier.weight(1f),
      value = draft,
      onValueChange = onDraftChange,
      minLines = 1,
      maxLines = 4,
      shape = MaterialTheme.shapes.extraLarge,
      placeholder = { Text(stringResource(R.string.chat_message_placeholder)) },
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

    FilledIconButton(
      onClick = onSend,
      enabled = canSend,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Send,
        contentDescription = stringResource(R.string.chat_send_message),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun Message.displayText(): String {
  if (content.isNotBlank()) {
    return content
  }

  return when (status) {
    MessageStatus.STREAMING -> "..."
    MessageStatus.INTERRUPTED -> stringResource(R.string.chat_response_stopped)
    MessageStatus.FAILED -> errorMessage ?: stringResource(R.string.chat_response_failed)
    else -> stringResource(R.string.chat_empty_message)
  }
}

@Composable
private fun TypingIndicator() {
  val dots = listOf(0, 1, 2)
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(vertical = 8.dp)
  ) {
    dots.forEach { index ->
      var animating by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) {
        delay(index * 200L)
        animating = true
      }

      Surface(
        modifier = Modifier.size(8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
      ) {}
    }
  }
}

private fun shouldKeepLatestMessageVisible(listState: LazyListState, latestItemIndex: Int): Boolean {
  val layoutInfo = listState.layoutInfo
  val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
  val bottomGap =
    lastVisibleItem.offset + lastVisibleItem.size - layoutInfo.viewportEndOffset
  return lastVisibleItem.index >= latestItemIndex - 1 && bottomGap < 120
}

private suspend fun scrollToItem(listState: LazyListState, itemIndex: Int, animate: Boolean) {
  if (itemIndex < 0) {
    return
  }

  if (animate) {
    listState.animateScrollToItem(index = itemIndex)
  } else {
    listState.scrollToItem(index = itemIndex)
  }
}

private fun calculateLatestListItemIndex(
  messageCount: Int,
): Int {
  if (messageCount == 0) {
    return -1
  }

  return messageCount - 1
}

private fun String.looksLikeHtml(): Boolean {
  return Regex("""<([a-zA-Z][a-zA-Z0-9]*)(\s[^>]*)?>|</[a-zA-Z][a-zA-Z0-9]*>""").containsMatchIn(this)
}

private fun String.looksLikeMarkdown(): Boolean {
  return Regex("""(?m)^\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|```|~~~)|(\[[^]]+]\([^)]+\)|`[^`]+`)""")
    .containsMatchIn(this)
}

private fun Message.supportsRoleplayActions(): Boolean {
  return supportsPinAction() || supportsRollbackAction()
}

private fun Message.supportsPinAction(): Boolean {
  if (!accepted || !isCanonical || side == MessageSide.SYSTEM) {
    return false
  }
  return content.isNotBlank() || kind == MessageKind.IMAGE || kind == MessageKind.AUDIO
}

private fun Message.supportsRollbackAction(): Boolean {
  return accepted && isCanonical && side != MessageSide.SYSTEM
}

private fun Message.supportsRegenerateAction(): Boolean {
  return side == MessageSide.ASSISTANT && accepted && isCanonical && status == MessageStatus.COMPLETED
}

private fun Message.supportsEditAction(): Boolean {
  return side == MessageSide.USER && accepted && isCanonical && kind == MessageKind.TEXT && content.isNotBlank()
}

private fun RuntimeStateSnapshot.toDebugText(): String {
  return buildString {
    appendLine("updatedAt=$updatedAt")
    appendLine("sourceMessageId=${sourceMessageId ?: "-"}")
    appendLine("scene=$sceneJson")
    appendLine("relationship=$relationshipJson")
    append("entities=$activeEntitiesJson")
  }
}

private fun OpenThread.toDebugText(): String {
  return buildString {
    appendLine("[$status] $type owner=$owner priority=$priority")
    appendLine("content=$content")
    appendLine("source=${sourceMessageIds.joinToString().ifBlank { "-" }}")
    append("resolvedBy=${resolvedByMessageId ?: "-"}")
  }
}

private fun MemoryAtom.toDebugText(): String {
  return buildString {
    appendLine("$plane/$namespace $subject | $predicate | $objectValue")
    appendLine("stability=$stability epistemic=$epistemicStatus branch=$branchScope")
    appendLine("confidence=$confidence salience=$salience updatedAt=$updatedAt")
    appendLine("source=${sourceMessageIds.joinToString().ifBlank { "-" }}")
    append("evidence=${evidenceQuote.ifBlank { "-" }}")
  }
}

private fun SessionEvent.toDebugText(): String {
  return buildString {
    appendLine("$eventType @ $createdAt")
    append(payloadJson)
  }
}

private fun String.prettyDebugJson(): String {
  return replace("\",\"", "\",\n\"")
    .replace("\",\"", "\",\n\"")
    .replace(",\"", ",\n\"")
    .replace("{\"", "{\n\"")
    .replace("}", "\n}")
    .replace("[{", "[\n{")
    .replace("}]", "}\n]")
}

internal data class RoleplaySendRequirements(
  val primaryTextInput: String? = null,
  val needsImage: Boolean = false,
  val needsAudio: Boolean = false,
)

internal enum class RoleplayWarmupAction {
  NONE,
  TEXT_ONLY,
  MULTIMODAL,
}

internal fun resolveRoleplaySendRequirements(
  messages: List<ChatMessage>,
  conversationMessages: List<Message> = emptyList(),
): RoleplaySendRequirements {
  val historicalNeedsImage =
    conversationMessages.any { message ->
      message.kind == MessageKind.IMAGE && message.status != MessageStatus.FAILED
    }
  val historicalNeedsAudio =
    conversationMessages.any { message ->
      message.kind == MessageKind.AUDIO && message.status != MessageStatus.FAILED
    }

  return RoleplaySendRequirements(
    primaryTextInput =
      messages
        .filterIsInstance<ChatMessageText>()
        .map { it.content.trim() }
        .firstOrNull(String::isNotBlank),
    needsImage = historicalNeedsImage || messages.any { it is ChatMessageImage && it.bitmaps.isNotEmpty() },
    needsAudio = historicalNeedsAudio || messages.any { it is ChatMessageAudioClip },
  )
}

internal fun resolveRoleplayWarmupAction(
  downloadStatus: ModelDownloadStatusType?,
  isInitializing: Boolean,
  hasInstance: Boolean,
  supportImage: Boolean,
  supportAudio: Boolean,
  needsImage: Boolean,
  needsAudio: Boolean,
): RoleplayWarmupAction {
  if (downloadStatus != ModelDownloadStatusType.SUCCEEDED || isInitializing) {
    return RoleplayWarmupAction.NONE
  }

  if (
    hasInstance &&
      canReuseRoleplayModelSession(
        supportImage = supportImage,
        supportAudio = supportAudio,
        needsImage = needsImage,
        needsAudio = needsAudio,
      )
  ) {
    return RoleplayWarmupAction.NONE
  }

  return if (needsImage || needsAudio) {
    RoleplayWarmupAction.MULTIMODAL
  } else {
    RoleplayWarmupAction.TEXT_ONLY
  }
}

internal data class RoleplaySendExecutionPlan(
  val queueImmediately: Boolean,
  val warmupAction: RoleplayWarmupAction,
)

internal fun resolveRoleplaySendExecutionPlan(
  needsImage: Boolean,
  needsAudio: Boolean,
  hasReusableMultimodalSession: Boolean,
  hasInitializedSession: Boolean,
): RoleplaySendExecutionPlan {
  val needsMultimodalSession = needsImage || needsAudio
  if (needsMultimodalSession) {
    return RoleplaySendExecutionPlan(
      queueImmediately = true,
      warmupAction =
        if (hasReusableMultimodalSession) {
          RoleplayWarmupAction.NONE
        } else {
          RoleplayWarmupAction.MULTIMODAL
        },
    )
  }

  return if (hasInitializedSession) {
    RoleplaySendExecutionPlan(
      queueImmediately = true,
      warmupAction = RoleplayWarmupAction.NONE,
    )
  } else {
    RoleplaySendExecutionPlan(
      queueImmediately = false,
      warmupAction = RoleplayWarmupAction.TEXT_ONLY,
    )
  }
}

private fun canReuseRoleplayModelSession(
  instance: LlmModelInstance?,
  needsImage: Boolean,
  needsAudio: Boolean,
): Boolean {
  if (instance == null) {
    return false
  }
  return canReuseRoleplayModelSession(
    supportImage = instance.supportImage,
    supportAudio = instance.supportAudio,
    needsImage = needsImage,
    needsAudio = needsAudio,
  )
}

internal fun canReuseRoleplayModelSession(
  supportImage: Boolean,
  supportAudio: Boolean,
  needsImage: Boolean,
  needsAudio: Boolean,
): Boolean {
  return (!needsImage || supportImage) && (!needsAudio || supportAudio)
}
