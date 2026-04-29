package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.BuildConfig
import selfgemma.talk.R
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository
import selfgemma.talk.domain.roleplay.usecase.ExtractMemoriesUseCase
import selfgemma.talk.domain.roleplay.usecase.PrepareRoleplayEditUseCase
import selfgemma.talk.domain.roleplay.usecase.PrepareRoleplayRegenerationUseCase
import selfgemma.talk.domain.roleplay.usecase.RoleplayDebugBundleExportLauncher
import selfgemma.talk.domain.roleplay.usecase.RollbackRoleplayContinuityUseCase
import selfgemma.talk.domain.roleplay.usecase.RunRoleplayTurnUseCase
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatSide

private const val TAG = "RoleplayChatViewModel"
private const val DEFAULT_BRANCH_ID = "main"
private const val SEND_DISPATCH_DELAY_MS = 2_000L
internal const val CHAT_STATUS_MESSAGE_AUTO_DISMISS_MS = 2_000L

@HiltViewModel
class RoleplayChatViewModel
@Inject
constructor(
  savedStateHandle: SavedStateHandle,
  @ApplicationContext internal val appContext: Context,
  internal val dataStoreRepository: DataStoreRepository,
  internal val conversationRepository: ConversationRepository,
  internal val roleRepository: RoleRepository,
  internal val memoryRepository: MemoryRepository,
  internal val runtimeStateRepository: RuntimeStateRepository,
  internal val openThreadRepository: OpenThreadRepository,
  internal val memoryAtomRepository: MemoryAtomRepository,
  internal val compactionCacheRepository: CompactionCacheRepository,
  internal val toolInvocationRepository: ToolInvocationRepository,
  internal val runRoleplayTurnUseCase: RunRoleplayTurnUseCase,
  internal val roleplayDebugBundleExportLauncher: RoleplayDebugBundleExportLauncher,
  internal val extractMemoriesUseCase: ExtractMemoriesUseCase,
  internal val rollbackRoleplayContinuityUseCase: RollbackRoleplayContinuityUseCase,
  internal val prepareRoleplayEditUseCase: PrepareRoleplayEditUseCase,
  internal val prepareRoleplayRegenerationUseCase: PrepareRoleplayRegenerationUseCase,
) : ViewModel() {
  internal val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
  internal val draft = MutableStateFlow("")
  internal val metaState = MutableStateFlow(RoleplayChatMetaState())
  internal val stopRequested = MutableStateFlow(false)
  internal var dispatchJob: Job? = null
  internal var statusMessageDismissJob: Job? = null
  internal var lastDraftEditAtElapsed = 0L
  internal var latestQueuedModel: Model? = null
  internal var activeAssistantMessageId: String? = null
  internal var activeDispatchSuperseded = false
  private fun elapsedRealtime(): Long = elapsedRealtimeProvider()
  internal var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
  internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
  internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
  internal var stringResolver: (Int, List<Any>) -> String =
    { resId, args -> appContext.getString(resId, *args.toTypedArray()) }

  private val sessionFlow =
    conversationRepository.observeSessions().map { sessions ->
      sessions.firstOrNull { it.id == sessionId }
    }.distinctUntilChanged()
  private val roleFlow =
    combine(sessionFlow, roleRepository.observeRoles()) { session, roles ->
      roles.firstOrNull { it.id == session?.roleId }
    }.distinctUntilChanged()
  private val toolInvocationsFlow =
    toolInvocationRepository.observeBySession(sessionId).distinctUntilChanged()
  private val transientStateFlow =
    combine(draft, metaState, toolInvocationsFlow) { draftValue, meta, toolInvocations ->
      RoleplayChatTransientState(
        draft = draftValue,
        meta = meta,
        toolInvocations = toolInvocations,
      )
    }

  val uiState: StateFlow<RoleplayChatUiState> =
    combine(
      sessionFlow,
      conversationRepository.observeMessages(sessionId).distinctUntilChanged(),
      roleFlow,
      transientStateFlow,
    ) { session, messages, role, transientState ->
      val userProfile =
        session?.resolveUserProfile(dataStoreRepository.getStUserProfile())
          ?: dataStoreRepository.getStUserProfile().ensureDefaults()
      RoleplayChatUiState(
        loading = session == null,
        session = session,
        role = role,
        messages = mergeMessages(messages = messages, queuedMessages = transientState.meta.pendingUserMessages),
        draft = transientState.draft,
        userPersonaSlotId = userProfile.resolvedUserAvatarId(),
        userPersonaName = userProfile.userName,
        userPersonaAvatarUri = userProfile.activeAvatarUri,
        userPersonaDescription = userProfile.personaDescription,
        summary = transientState.meta.summary,
        pinnedMemories = transientState.meta.pinnedMemories,
        toolInvocations = transientState.toolInvocations,
        continuityDebug = transientState.meta.continuityDebug,
        inProgress = transientState.meta.inProgress,
        hasPendingSends = transientState.meta.pendingUserMessages.isNotEmpty(),
        statusMessage = transientState.meta.statusMessage,
        errorMessage = transientState.meta.errorMessage,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RoleplayChatUiState(),
      )

  init {
    RoleplaySoundEffectPlayer.prepare(appContext)
    refreshSupplementalState()
  }

  fun updateDraft(value: String) {
    if (draft.value == value) {
      return
    }

    draft.value = value
    lastDraftEditAtElapsed = elapsedRealtime()
    if (metaState.value.pendingUserMessages.isNotEmpty() && !metaState.value.inProgress) {
      scheduleDispatch(reason = "draft changed while send pending")
    }
  }

  fun sendMessage(model: Model) {
    val input = draft.value.trim()
    if (input.isBlank()) {
      return
    }
    sendChatMessages(
      model = model,
      messages = listOf(ChatMessageText(content = input, side = ChatSide.USER)),
      clearDraft = true,
    )
  }

  fun sendChatMessages(model: Model, messages: List<ChatMessage>, clearDraft: Boolean = false) {
    val hasText = messages.filterIsInstance<ChatMessageText>().any { it.content.trim().isNotBlank() }
    val hasImages = messages.any { it is ChatMessageImage && it.bitmaps.isNotEmpty() }
    val hasAudio = messages.any { it is ChatMessageAudioClip }
    if (!hasText && !hasImages && !hasAudio) {
      return
    }

    latestQueuedModel = model
    if (clearDraft) {
      draft.value = ""
    }
    lastDraftEditAtElapsed = elapsedRealtime()

    viewModelScope.launch {
      val queuedMessages =
        withContext(ioDispatcher) {
          runCatching {
            stagePendingUserMessages(messages = messages)
          }.onFailure { error ->
            logError("failed to stage multimodal roleplay messages sessionId=$sessionId", error)
          }.getOrDefault(emptyList())
        }
      if (queuedMessages.isEmpty()) {
        metaState.update { current ->
          current.copy(errorMessage = "Failed to prepare the selected media.")
        }
        logWarn("send ignored because no queued roleplay messages were produced sessionId=$sessionId")
        return@launch
      }

      metaState.update { current ->
        current.copy(
          pendingUserMessages = current.pendingUserMessages + queuedMessages,
          errorMessage = null,
        )
      }
      logDebug(
        "send accepted sessionId=$sessionId model=${model.name} queuedCount=${queuedMessages.size} hasText=$hasText hasImages=$hasImages hasAudio=$hasAudio pendingCount=${metaState.value.pendingUserMessages.size}",
      )

      viewModelScope.launch(defaultDispatcher) {
        playSendSound()
      }

      if (metaState.value.inProgress) {
        requestMergeAndStop(model = model)
        return@launch
      }

      scheduleDispatch(reason = "send accepted")
    }
  }

  fun switchModel(modelId: String) {
    viewModelScope.launch {
      val session = conversationRepository.getSession(sessionId) ?: return@launch
      if (session.activeModelId == modelId) {
        return@launch
      }

      val now = System.currentTimeMillis()
      conversationRepository.updateSession(
        session.copy(activeModelId = modelId, updatedAt = now, lastMessageAt = session.lastMessageAt)
      )
      conversationRepository.appendEvent(
        SessionEvent(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          eventType = SessionEventType.MODEL_SWITCH,
          payloadJson = """{"activeModelId":"${modelId.escapeJson()}"}""",
          createdAt = now,
        )
      )
      metaState.update { current -> current.copy(errorMessage = null) }
      refreshSupplementalState()
    }
  }

  fun exportDebugBundle() {
    if (!BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS) {
      logDebug("ignore debug bundle export in release build sessionId=$sessionId")
      return
    }
    viewModelScope.launch {
      runCatching {
        roleplayDebugBundleExportLauncher.exportFromSession(
          sessionId = sessionId,
          origin = RoleplayDebugExportOrigin.CHAT_SCREEN,
        ) ?: return@launch
      }
        .onSuccess { statusMessage ->
          logDebug("debug bundle exported sessionId=$sessionId")
          showStatusMessage(statusMessage)
          refreshSupplementalState()
        }
        .onFailure { error ->
          logError("failed to export debug bundle sessionId=$sessionId", error)
          showErrorMessage(error.message ?: "Failed to export the debug bundle")
        }
    }
  }

  fun pinMessage(message: Message) {
    viewModelScope.launch {
      val session = conversationRepository.getSession(sessionId) ?: return@launch
      val role = roleRepository.getRole(session.roleId) ?: return@launch
      extractMemoriesUseCase.pinMessage(session = session, role = role, message = message)
      refreshSupplementalState()
    }
  }

  fun addManualMemory(content: String, category: MemoryCategory) {
    viewModelScope.launch {
      val session = conversationRepository.getSession(sessionId) ?: return@launch
      val role = roleRepository.getRole(session.roleId) ?: return@launch
      extractMemoriesUseCase.addManualMemory(
        session = session,
        role = role,
        content = content,
        category = category,
      )
      refreshSupplementalState()
    }
  }

  fun rollbackToMessage(messageId: String) {
    if (hasContinuityMutationConflict()) {
      metaState.update { current ->
        current.copy(errorMessage = "Wait for queued or in-progress turns to finish before rewinding.")
      }
      return
    }
    viewModelScope.launch {
      val result = rollbackRoleplayContinuityUseCase(sessionId = sessionId, targetMessageId = messageId)
      if (result == null) {
        metaState.update { current -> current.copy(errorMessage = "Failed to rewind the current continuity.") }
        logWarn("rollback rejected sessionId=$sessionId targetMessageId=$messageId")
        return@launch
      }

      metaState.update { current -> current.copy(errorMessage = null) }
      logDebug(
        "rollback applied sessionId=$sessionId targetMessageId=$messageId rolledBackCount=${result.rolledBackMessageCount} replayedTurnCount=${result.rebuildResult?.replayedTurnCount ?: 0}",
      )
      refreshSupplementalState()
    }
  }

  fun editMessageFromHere(messageId: String) {
    if (hasContinuityMutationConflict()) {
      metaState.update { current ->
        current.copy(errorMessage = "Wait for queued or in-progress turns to finish before editing.")
      }
      return
    }

    viewModelScope.launch(ioDispatcher) {
      val prepared = prepareRoleplayEditUseCase(sessionId = sessionId, targetMessageId = messageId)
      if (prepared == null) {
        metaState.update { current ->
          current.copy(errorMessage = "Failed to reopen the selected turn for editing.")
        }
        logWarn("edit rejected sessionId=$sessionId targetMessageId=$messageId")
        return@launch
      }

      draft.value = prepared.restoredDraft
      lastDraftEditAtElapsed = elapsedRealtime()
      metaState.update { current -> current.copy(errorMessage = null) }
      logDebug(
        "edit prepared sessionId=$sessionId targetMessageId=$messageId rolledBackCount=${prepared.rolledBackMessageCount} replayedTurnCount=${prepared.rebuildResult?.replayedTurnCount ?: 0}",
      )
      refreshSupplementalState()
    }
  }

  fun regenerateAssistantMessage(messageId: String, model: Model) {
    if (hasContinuityMutationConflict()) {
      metaState.update { current ->
        current.copy(errorMessage = "Wait for queued or in-progress turns to finish before regenerating.")
      }
      return
    }

    val regenerationStartedAt = elapsedRealtime()
    stopRequested.value = false
    activeDispatchSuperseded = false
    metaState.update { current ->
      current.copy(
        inProgress = true,
        errorMessage = null,
      )
    }

    viewModelScope.launch(ioDispatcher) {
      val prepared =
        prepareRoleplayRegenerationUseCase(
          sessionId = sessionId,
          assistantMessageId = messageId,
          model = model,
        )
      if (prepared == null) {
        stopRequested.value = false
        activeAssistantMessageId = null
        activeDispatchSuperseded = false
        metaState.update { current ->
          current.copy(
            inProgress = false,
            errorMessage = "Failed to regenerate from the selected turn.",
          )
        }
        logWarn("regeneration rejected sessionId=$sessionId assistantMessageId=$messageId")
        return@launch
      }

      activeAssistantMessageId = prepared.pendingMessage.assistantSeed.id
      logDebug(
        "regeneration queued sessionId=$sessionId sourceAssistantMessageId=$messageId assistantMessageId=${prepared.pendingMessage.assistantSeed.id} sourceUserCount=${prepared.sourceUserMessageIds.size}",
      )

      val result =
        runRoleplayTurnUseCase.runPrepared(
          pendingMessage = prepared.pendingMessage,
          model = model,
          enableStreamingOutput = dataStoreRepository.isStreamingOutputEnabled(),
          isStopRequested = { stopRequested.value },
        )
      val superseded = activeDispatchSuperseded

      logDebug(
        "regeneration finished after ${elapsedRealtime() - regenerationStartedAt}ms sessionId=$sessionId assistantMessageId=${prepared.pendingMessage.assistantSeed.id} interrupted=${result.interrupted} superseded=$superseded error=${result.errorMessage != null}",
      )

      if (superseded && result.assistantMessage != null) {
        conversationRepository.updateMessage(
          result.assistantMessage.copy(
            content = "",
            status = MessageStatus.INTERRUPTED,
            errorMessage = null,
            updatedAt = System.currentTimeMillis(),
          )
        )
      }

      if (!superseded && result.assistantMessage != null && result.assistantMessage.status == MessageStatus.COMPLETED) {
        launch(defaultDispatcher) {
          playReceiveSound()
        }
      }

      stopRequested.value = false
      activeAssistantMessageId = null
      activeDispatchSuperseded = false
      metaState.update { current ->
        current.copy(
          inProgress = false,
          errorMessage = if (result.interrupted || superseded) null else result.errorMessage,
        )
      }
      refreshSupplementalState()

      if (metaState.value.pendingUserMessages.isNotEmpty()) {
        scheduleDispatch(reason = "pending queue remains after regeneration")
      }
    }
  }
}

