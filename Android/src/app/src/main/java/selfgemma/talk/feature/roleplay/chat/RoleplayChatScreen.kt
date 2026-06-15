package selfgemma.talk.feature.roleplay.chat

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import selfgemma.talk.AppTopBar
import selfgemma.talk.BuildConfig
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.llmchat.LlmModelInstance
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.feature.roleplay.common.RoleAvatar

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
  LaunchedEffect(uiState.statusMessage) {
    uiState.statusMessage?.let { statusMessage ->
      Toast.makeText(context, statusMessage, Toast.LENGTH_SHORT).show()
    }
  }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val internalDiagnosticsEnabled = BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS
  val showLiveTokenSpeed =
    remember(modelManagerUiState.settingsUpdateTrigger) {
      modelManagerViewModel.isLiveTokenSpeedEnabled()
    }
  val showToolDebugOutput =
    remember(modelManagerUiState.settingsUpdateTrigger, internalDiagnosticsEnabled) {
      internalDiagnosticsEnabled && modelManagerViewModel.isRoleplayToolDebugOutputEnabled()
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
  val timelineItems =
    remember(uiState.messages, uiState.toolInvocations, showToolDebugOutput) {
      buildRoleplayTimelineItems(
        messages = uiState.messages,
        toolInvocations = uiState.toolInvocations,
        showToolDebugOutput = showToolDebugOutput,
      )
    }
  val lastTimelineItem = timelineItems.lastOrNull()
  val roleName = uiState.role?.name ?: stringResource(R.string.chat_assistant)
  val userPersonaName = uiState.userPersonaName.ifBlank { stringResource(R.string.chat_you) }
  val localModelLoadingMessage = stringResource(R.string.chat_local_model_loading)
  val tokenSpeedSubtitle =
    rememberRoleplayChatTokenSpeedSubtitle(
      messages = uiState.messages,
      showLiveTokenSpeed = showLiveTokenSpeed,
      inProgress = uiState.inProgress,
    )
  val imeBottom = WindowInsets.ime.getBottom(density)
  val screenOpenTimestamp = remember { SystemClock.elapsedRealtime() }
  var hasCompletedInitialPositioning by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var hasLoggedInitialPositioning by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var hasShownLocalModelLoadingToast by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  var previousTimelineItemCount by rememberSaveable(uiState.session?.id) { mutableStateOf(0) }
  var composerBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
  val latestListItemIndex =
    remember(timelineItems.size) {
      calculateLatestListItemIndex(
        itemCount = timelineItems.size,
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
  LaunchedEffect(uiState.session?.id, isActiveModelInitializing, isActiveModelInitialized) {
    if (hasShownLocalModelLoadingToast || uiState.session == null || !isActiveModelInitializing || isActiveModelInitialized) {
      return@LaunchedEffect
    }
    Toast.makeText(context, localModelLoadingMessage, Toast.LENGTH_SHORT).show()
    hasShownLocalModelLoadingToast = true
  }
  var showMenu by remember { mutableStateOf(false) }
  var showModelPicker by remember { mutableStateOf(false) }
  var showContinuityDebug by rememberSaveable(uiState.session?.id) { mutableStateOf(false) }
  val showContinuityDebugDialog = internalDiagnosticsEnabled && showContinuityDebug
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
      showContinuityDebugDialog -> {
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

  BackHandler(enabled = showMenu || showModelPicker || showContinuityDebugDialog) {
    Log.d(
      TAG,
      "intercept back to dismiss transient chat UI sessionId=${uiState.session?.id} showMenu=$showMenu showModelPicker=$showModelPicker",
    )
    handleNavigateUp()
  }

  RoleplayChatModelWarmupEffects(
    context = context,
    sessionId = uiState.session?.id,
    activeModel = activeModel,
    activeModelDownloadStatus = activeModelDownloadStatus,
    activeModelStatus = activeModelStatus,
    activeModelHasInstance = activeModelInstance != null,
    activeModelSupportsImage = activeModelInstance?.supportImage == true,
    activeModelSupportsAudio = activeModelInstance?.supportAudio == true,
    isActiveModelInitializing = isActiveModelInitializing,
    needsImage = historicalWarmupRequirements.needsImage,
    needsAudio = historicalWarmupRequirements.needsAudio,
    llmChatTask = llmChatTask,
    modelManagerViewModel = modelManagerViewModel,
  )

  RoleplayChatScrollEffects(
    sessionId = uiState.session?.id,
    imeBottom = imeBottom,
    latestListItemIndex = latestListItemIndex,
    timelineItemCount = timelineItems.size,
    lastTimelineStableId = lastTimelineItem?.stableId,
    lastMessageStatusKey = lastMessage?.status?.name,
    hasCompletedInitialPositioning = hasCompletedInitialPositioning,
    hasLoggedInitialPositioning = hasLoggedInitialPositioning,
    previousTimelineItemCount = previousTimelineItemCount,
    screenOpenTimestamp = screenOpenTimestamp,
    listState = listState,
    onMarkInitialPositioned = { hasCompletedInitialPositioning = true },
    onMarkLoggedInitialPositioning = { hasLoggedInitialPositioning = true },
    onUpdatePreviousTimelineItemCount = { previousTimelineItemCount = it },
  )

  LaunchedEffect(showToolDebugOutput, uiState.toolInvocations.size) {
    if (showToolDebugOutput) {
      Log.d(
        TAG,
        "roleplay tool debug output visible sessionId=${uiState.session?.id} toolInvocationCount=${uiState.toolInvocations.size}",
      )
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
            RoleplayChatOverflowMenu(
              expanded = showMenu,
              onExpandedChange = updateOverflowMenuVisibility,
              internalDiagnosticsEnabled = internalDiagnosticsEnabled,
              onShowModelPicker = { showModelPicker = true },
              onOpenModelLibrary = onOpenModelLibrary,
              onShowContinuityDebug = { showContinuityDebug = true },
              onExportDebugBundle = { viewModel.exportDebugBundle() },
            )
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
        items(timelineItems, key = { it.stableId }) { timelineItem ->
          when (timelineItem) {
            is RoleplayTimelineItem.MessageEntry ->
              ChatMessageBubble(
                message = timelineItem.message,
                roleName = roleName,
                roleAvatarUri = uiState.role?.primaryAvatarUri(),
                userName = userPersonaName,
                userAvatarUri = uiState.userPersonaAvatarUri,
                animateOnEnter = hasCompletedInitialPositioning && timelineItem.message.id == lastMessage?.id,
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
            is RoleplayTimelineItem.ToolInvocationEntry ->
              RoleplayToolInvocationSystemRow(invocation = timelineItem.invocation)
          }
        }
      }

      RoleplayChatComposerSection(
        llmChatTask = llmChatTask,
        draft = uiState.draft,
        inProgress = uiState.inProgress,
        isActiveModelInitialized = isActiveModelInitialized,
        isActiveModelInitializing = isActiveModelInitializing,
        lastMessageStatus = uiState.messages.lastOrNull()?.status,
        errorMessage = uiState.errorMessage,
        activeModel = activeModel,
        onUpdateDraft = viewModel::updateDraft,
        onComposerBoundsChanged = { composerBoundsInWindow = it },
        onSendMessages = { messages ->
          activeModel?.let { currentModel ->
            handleRoleplaySend(
              context = context,
              sessionId = uiState.session?.id,
              messages = messages,
              conversationMessages = uiState.messages,
              currentModel = currentModel,
              isActiveModelInitialized = isActiveModelInitialized,
              isActiveModelInitializing = isActiveModelInitializing,
              viewModel = viewModel,
              modelManagerViewModel = modelManagerViewModel,
            )
          }
        },
        onSendDraft = {
          activeModel?.let { currentModel ->
            viewModel.sendMessage(currentModel)
          }
        },
      )
    }
  }

  selectedMessageForAction?.let { selectedMessage ->
    RoleplayChatMessageActionsDialogHost(
      selectedMessage = selectedMessage,
      sessionId = uiState.session?.id,
      activeModel = activeModel,
      inProgress = uiState.inProgress,
      hasPendingSends = uiState.hasPendingSends,
      onDismiss = { selectedMessageActionId = null },
      onPin = viewModel::pinMessage,
      onRollback = viewModel::rollbackToMessage,
      onRegenerate = { messageId, model -> viewModel.regenerateAssistantMessage(messageId, model) },
      onEdit = viewModel::editMessageFromHere,
    )
  }

  if (showContinuityDebugDialog) {
    ContinuityDebugDialog(
      debugState = uiState.continuityDebug,
      onDismiss = { showContinuityDebug = false },
    )
  }

  if (showModelPicker && downloadedModels.isNotEmpty()) {
    ChatModelPickerDialog(
      downloadedModels = downloadedModels,
      activeModelName = activeModel?.name,
      onModelSelected = { modelName ->
        viewModel.switchModel(modelName)
        showModelPicker = false
      },
      onDismiss = { showModelPicker = false },
    )
  }
  }
}

