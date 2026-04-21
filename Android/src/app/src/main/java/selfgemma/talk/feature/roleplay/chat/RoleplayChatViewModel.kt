package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.RoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.SessionSummary
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
import selfgemma.talk.domain.roleplay.usecase.RollbackRoleplayContinuityUseCase
import selfgemma.talk.domain.roleplay.usecase.RunRoleplayTurnUseCase
import selfgemma.talk.domain.roleplay.usecase.StagedRoleplayTurn
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatSide

data class RoleplayContinuityDebugState(
  val runtimeState: RuntimeStateSnapshot? = null,
  val openThreads: List<OpenThread> = emptyList(),
  val memoryAtoms: List<MemoryAtom> = emptyList(),
  val recentEvents: List<SessionEvent> = emptyList(),
  val latestMemoryQueryPayload: String? = null,
  val latestMemoryPackPayload: String? = null,
  val compactionEntryCount: Int = 0,
)

data class RoleplayChatUiState(
  val loading: Boolean = true,
  val session: Session? = null,
  val role: RoleCard? = null,
  val messages: List<Message> = emptyList(),
  val draft: String = "",
  val userPersonaSlotId: String = "",
  val userPersonaName: String = "",
  val userPersonaAvatarUri: String? = null,
  val userPersonaDescription: String = "",
  val summary: SessionSummary? = null,
  val pinnedMemories: List<MemoryItem> = emptyList(),
  val toolInvocations: List<ToolInvocation> = emptyList(),
  val continuityDebug: RoleplayContinuityDebugState = RoleplayContinuityDebugState(),
  val inProgress: Boolean = false,
  val hasPendingSends: Boolean = false,
  val errorMessage: String? = null,
)

private const val TAG = "RoleplayChatViewModel"
private const val DEFAULT_BRANCH_ID = "main"
private const val SEND_DISPATCH_DELAY_MS = 2_000L

private fun logDebug(message: String) {
  runCatching {
    Log.d(TAG, message)
  }
}

private fun logWarn(message: String) {
  runCatching {
    Log.w(TAG, message)
  }
}

private fun logError(message: String, error: Throwable) {
  runCatching {
    Log.e(TAG, message, error)
  }
}

private data class QueuedUserMessage(
  val message: Message,
  val persisted: Boolean = false,
)

private data class RoleplayChatMetaState(
  val summary: SessionSummary? = null,
  val pinnedMemories: List<MemoryItem> = emptyList(),
  val continuityDebug: RoleplayContinuityDebugState = RoleplayContinuityDebugState(),
  val pendingUserMessages: List<QueuedUserMessage> = emptyList(),
  val inProgress: Boolean = false,
  val errorMessage: String? = null,
)

private data class RoleplayChatTransientState(
  val draft: String,
  val meta: RoleplayChatMetaState,
  val toolInvocations: List<ToolInvocation>,
)

@HiltViewModel
class RoleplayChatViewModel
@Inject
constructor(
  savedStateHandle: SavedStateHandle,
  @ApplicationContext private val appContext: Context,
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val memoryRepository: MemoryRepository,
  private val runtimeStateRepository: RuntimeStateRepository,
  private val openThreadRepository: OpenThreadRepository,
  private val memoryAtomRepository: MemoryAtomRepository,
  private val compactionCacheRepository: CompactionCacheRepository,
  private val toolInvocationRepository: ToolInvocationRepository,
  private val runRoleplayTurnUseCase: RunRoleplayTurnUseCase,
  private val extractMemoriesUseCase: ExtractMemoriesUseCase,
  private val rollbackRoleplayContinuityUseCase: RollbackRoleplayContinuityUseCase,
  private val prepareRoleplayEditUseCase: PrepareRoleplayEditUseCase,
  private val prepareRoleplayRegenerationUseCase: PrepareRoleplayRegenerationUseCase,
) : ViewModel() {
  private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
  private val draft = MutableStateFlow("")
  private val metaState = MutableStateFlow(RoleplayChatMetaState())
  private val stopRequested = MutableStateFlow(false)
  private var dispatchJob: Job? = null
  private var lastDraftEditAtElapsed = 0L
  private var latestQueuedModel: Model? = null
  private var activeAssistantMessageId: String? = null
  private var activeDispatchSuperseded = false
  internal var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
  internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
  internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

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

  private fun scheduleDispatch(reason: String) {
    val model = latestQueuedModel ?: return
    dispatchJob?.cancel()
    dispatchJob =
      viewModelScope.launch {
        while (true) {
          val delayMs = remainingDispatchDelay()
          if (delayMs <= 0L) {
            break
          }
          logDebug(
            "dispatch paused sessionId=$sessionId reason=$reason delayMs=$delayMs pendingCount=${metaState.value.pendingUserMessages.size}",
          )
          delay(delayMs)
        }

        if (metaState.value.inProgress || metaState.value.pendingUserMessages.isEmpty()) {
          return@launch
        }

        dispatchPendingMessages(model = model)
      }
  }

  private fun requestMergeAndStop(model: Model) {
    if (!metaState.value.inProgress) {
      return
    }

    stopRequested.value = true
    activeDispatchSuperseded = true
    metaState.update { current -> current.copy(errorMessage = null) }
    logDebug(
      "send merge requested sessionId=$sessionId model=${model.name} pendingCount=${metaState.value.pendingUserMessages.size} activeAssistantMessageId=$activeAssistantMessageId",
    )
    if (dataStoreRepository.isStreamingOutputEnabled()) {
      viewModelScope.launch(ioDispatcher) {
        retractActiveAssistantBubble()
      }
    }
    model.runtimeHelper.stopResponse(model)
  }

  private fun dispatchPendingMessages(model: Model) {
    val queuedMessages = metaState.value.pendingUserMessages
    if (queuedMessages.isEmpty() || metaState.value.inProgress) {
      return
    }

    val stagedTurn = stageDispatchTurn(userMessages = queuedMessages.map { it.message }, model = model)
    val persistedIds = queuedMessages.filter { it.persisted }.mapTo(mutableSetOf()) { it.message.id }
    val queuedIds = queuedMessages.mapTo(mutableSetOf()) { it.message.id }
    val dispatchStartedAt = elapsedRealtime()

    stopRequested.value = false
    activeAssistantMessageId = stagedTurn.assistantMessage.id
    activeDispatchSuperseded = false
    metaState.update { current ->
      current.copy(
        inProgress = true,
        errorMessage = null,
      )
    }
    logDebug(
      "dispatch starting sessionId=$sessionId model=${model.name} pendingCount=${queuedMessages.size} persistedCount=${persistedIds.size} combinedLength=${stagedTurn.combinedUserInput.length} assistantMessageId=${stagedTurn.assistantMessage.id}",
    )

    viewModelScope.launch(ioDispatcher) {
      val pendingMessage =
        runRoleplayTurnUseCase.enqueueTurn(
          sessionId = sessionId,
          stagedTurn = stagedTurn,
          persistedUserMessageIds = persistedIds,
        )

      if (pendingMessage == null) {
        logDebug(
          "dispatch queue failed after ${elapsedRealtime() - dispatchStartedAt}ms sessionId=$sessionId",
        )
        draft.value = stagedTurn.combinedUserInput
        stopRequested.value = false
        activeAssistantMessageId = null
        metaState.update { current ->
          current.copy(
            pendingUserMessages = current.pendingUserMessages.filterNot { it.message.id in queuedIds },
            inProgress = false,
            errorMessage = "Session no longer exists.",
          )
        }
        return@launch
      }

      metaState.update { current ->
        current.copy(
          pendingUserMessages =
            current.pendingUserMessages.map { queued ->
              if (queued.message.id in queuedIds) {
                queued.copy(persisted = true)
              } else {
                queued
              }
            }
        )
      }
      logDebug(
        "dispatch queued after ${elapsedRealtime() - dispatchStartedAt}ms sessionId=$sessionId assistantMessageId=${stagedTurn.assistantMessage.id}",
      )
      val result =
        runRoleplayTurnUseCase.runPrepared(
          pendingMessage = pendingMessage,
          model = model,
          enableStreamingOutput = dataStoreRepository.isStreamingOutputEnabled(),
          isStopRequested = { stopRequested.value },
        )
      val superseded = activeDispatchSuperseded

      logDebug(
        "dispatch finished after ${elapsedRealtime() - dispatchStartedAt}ms sessionId=$sessionId interrupted=${result.interrupted} superseded=$superseded error=${result.errorMessage != null}",
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
      if (!superseded && result.errorMessage != null && !result.interrupted) {
        draft.value = stagedTurn.combinedUserInput
      }

      stopRequested.value = false
      activeAssistantMessageId = null
      activeDispatchSuperseded = false
      metaState.update { current ->
        current.copy(
          pendingUserMessages =
            if (result.interrupted || superseded) {
              current.pendingUserMessages
            } else {
              current.pendingUserMessages.filterNot { it.message.id in queuedIds }
            },
          inProgress = false,
          errorMessage = if (result.interrupted || superseded) null else result.errorMessage,
        )
      }
      refreshSupplementalState()

      if (metaState.value.pendingUserMessages.isNotEmpty()) {
        scheduleDispatch(reason = "pending queue remains after completion")
      }
    }
  }

  private fun refreshSupplementalState() {
    viewModelScope.launch {
      val session = conversationRepository.getSession(sessionId)
      if (session == null) {
        metaState.update { current ->
          current.copy(
            summary = null,
            pinnedMemories = emptyList(),
            continuityDebug = RoleplayContinuityDebugState(),
          )
        }
        return@launch
      }

      val summary = conversationRepository.getSummary(sessionId)
      val pinnedMemories = loadPinnedMemories(session)
      val continuityDebug = loadContinuityDebugState(sessionId = sessionId)
      metaState.update { current ->
        current.copy(
          summary = summary,
          pinnedMemories = pinnedMemories,
          continuityDebug = continuityDebug,
        )
      }
    }
  }

  private suspend fun loadPinnedMemories(session: Session): List<MemoryItem> {
    return (memoryRepository.listSessionMemories(session.id) + memoryRepository.listRoleMemories(session.roleId))
      .filter { it.pinned }
      .distinctBy { it.normalizedHash }
      .sortedByDescending { it.updatedAt }
      .take(8)
  }

  private fun stagePendingUserMessages(messages: List<ChatMessage>): List<QueuedUserMessage> {
    val now = System.currentTimeMillis()
    var nextSeq = (uiState.value.messages.maxOfOrNull { it.seq } ?: 0) + 1
    val queuedMessages = mutableListOf<QueuedUserMessage>()

    messages.forEach { chatMessage ->
      when (chatMessage) {
        is ChatMessageText -> {
          val input = chatMessage.content.trim()
          if (input.isBlank()) {
            return@forEach
          }
          val userMessage =
            Message(
              id = UUID.randomUUID().toString(),
              sessionId = sessionId,
              seq = nextSeq++,
              branchId = DEFAULT_BRANCH_ID,
              side = MessageSide.USER,
              kind = MessageKind.TEXT,
              status = MessageStatus.COMPLETED,
              accepted = true,
              isCanonical = true,
              content = input,
              createdAt = now,
              updatedAt = now,
            )
          queuedMessages += QueuedUserMessage(message = userMessage)
          logDebug("queued text draft sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id}")
        }
        is ChatMessageImage -> {
          if (chatMessage.bitmaps.isEmpty()) {
            return@forEach
          }
          val messageId = UUID.randomUUID().toString()
          val payload = persistImagePayload(messageId = messageId, bitmaps = chatMessage.bitmaps)
          val userMessage =
            Message(
              id = messageId,
              sessionId = sessionId,
              seq = nextSeq++,
              branchId = DEFAULT_BRANCH_ID,
              side = MessageSide.USER,
              kind = MessageKind.IMAGE,
              status = MessageStatus.COMPLETED,
              accepted = true,
              isCanonical = true,
              content = "Shared ${payload.attachments.size} image(s).",
              metadataJson = encodeRoleplayMessageMediaPayload(payload),
              createdAt = now,
              updatedAt = now,
            )
          queuedMessages += QueuedUserMessage(message = userMessage)
          logDebug(
            "queued image payload sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id} imageCount=${payload.attachments.size}",
          )
        }
        is ChatMessageAudioClip -> {
          val messageId = UUID.randomUUID().toString()
          val payload =
            persistAudioPayload(
              messageId = messageId,
              audioData = chatMessage.audioData,
              sampleRate = chatMessage.sampleRate,
            )
          val userMessage =
            Message(
              id = messageId,
              sessionId = sessionId,
              seq = nextSeq++,
              branchId = DEFAULT_BRANCH_ID,
              side = MessageSide.USER,
              kind = MessageKind.AUDIO,
              status = MessageStatus.COMPLETED,
              accepted = true,
              isCanonical = true,
              content = "Shared an audio clip.",
              metadataJson = encodeRoleplayMessageMediaPayload(payload),
              createdAt = now,
              updatedAt = now,
            )
          queuedMessages += QueuedUserMessage(message = userMessage)
          logDebug(
            "queued audio payload sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id} sampleRate=${chatMessage.sampleRate}",
          )
        }
        else -> Unit
      }
    }

    return queuedMessages
  }

  private fun stageDispatchTurn(userMessages: List<Message>, model: Model): StagedRoleplayTurn {
    val now = System.currentTimeMillis()
    val parentMessageId = userMessages.lastOrNull()?.id
    val assistantMessage =
      Message(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        seq = (userMessages.maxOfOrNull { it.seq } ?: 0) + 1,
        branchId = userMessages.lastOrNull()?.branchId ?: DEFAULT_BRANCH_ID,
        side = MessageSide.ASSISTANT,
        status = MessageStatus.STREAMING,
        accepted = false,
        isCanonical = false,
        content = "",
        accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = ""),
        parentMessageId = parentMessageId,
        regenerateGroupId = parentMessageId,
        createdAt = now,
        updatedAt = now,
      )
    logDebug(
      "dispatch turn staged sessionId=$sessionId userMessageCount=${userMessages.size} assistantMessageId=${assistantMessage.id}",
    )
    return StagedRoleplayTurn(
      userMessages = userMessages,
      assistantMessage = assistantMessage,
      combinedUserInput =
        userMessages
          .filter { it.kind == MessageKind.TEXT }
          .joinToString(separator = "\n\n") { it.content.trim() },
    )
  }

  private fun persistImagePayload(
    messageId: String,
    bitmaps: List<Bitmap>,
  ): RoleplayMessageMediaPayload {
    val attachments =
      bitmaps.mapIndexed { index, bitmap ->
        val targetFile = resolveAttachmentFile(messageId = messageId, fileName = "image-${index + 1}.png")
        writeBitmapToFile(bitmap = bitmap, file = targetFile)
        RoleplayMessageAttachment(
          type = RoleplayMessageAttachmentType.IMAGE,
          filePath = targetFile.absolutePath,
          mimeType = "image/png",
          width = bitmap.width,
          height = bitmap.height,
          fileSizeBytes = targetFile.length(),
        )
      }
    return RoleplayMessageMediaPayload(attachments = attachments)
  }

  private fun persistAudioPayload(
    messageId: String,
    audioData: ByteArray,
    sampleRate: Int,
  ): RoleplayMessageMediaPayload {
    val targetFile = resolveAttachmentFile(messageId = messageId, fileName = "audio-1.pcm")
    targetFile.writeBytes(audioData)
    val durationMs =
      if (sampleRate > 0) {
        ((audioData.size / 2.0) / sampleRate * 1000).toLong()
      } else {
        null
      }
    return RoleplayMessageMediaPayload(
      attachments =
        listOf(
          RoleplayMessageAttachment(
            type = RoleplayMessageAttachmentType.AUDIO,
            filePath = targetFile.absolutePath,
            mimeType = "audio/raw",
            sampleRate = sampleRate,
            durationMs = durationMs,
            fileSizeBytes = targetFile.length(),
          )
        )
    )
  }

  private fun resolveAttachmentFile(messageId: String, fileName: String): File {
    val directory = File(appContext.filesDir, "roleplay-media/$sessionId/$messageId")
    if (!directory.exists()) {
      directory.mkdirs()
    }
    return File(directory, fileName)
  }

  private fun writeBitmapToFile(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { output ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
      output.flush()
    }
  }

  private fun remainingDispatchDelay(): Long {
    val elapsed = elapsedRealtime() - lastDraftEditAtElapsed
    return (SEND_DISPATCH_DELAY_MS - elapsed).coerceAtLeast(0L)
  }

  private fun elapsedRealtime(): Long = elapsedRealtimeProvider()

  private suspend fun retractActiveAssistantBubble() {
    val assistantMessageId = activeAssistantMessageId ?: return
    val message =
      conversationRepository.observeMessages(sessionId).first().lastOrNull { it.id == assistantMessageId }
        ?: return
    if (message.side != MessageSide.ASSISTANT) {
      return
    }
    conversationRepository.updateMessage(
      message.copy(
        content = "",
        status = MessageStatus.INTERRUPTED,
        errorMessage = null,
        updatedAt = System.currentTimeMillis(),
      )
    )
    logDebug("retracted streaming assistant bubble sessionId=$sessionId messageId=$assistantMessageId")
  }

  private fun mergeMessages(messages: List<Message>, queuedMessages: List<QueuedUserMessage>): List<Message> {
    val visiblePersistedMessages = messages.filter(::shouldDisplayMessage)
    val persistedIds = visiblePersistedMessages.mapTo(mutableSetOf()) { it.id }
    return (visiblePersistedMessages + queuedMessages.map { it.message }.filterNot { it.id in persistedIds })
      .sortedWith(compareBy<Message>({ it.seq }, { it.createdAt }, { it.id }))
  }

  private fun shouldDisplayMessage(message: Message): Boolean {
    if (message.isCanonical) {
      return true
    }

    return when (message.status) {
      MessageStatus.PENDING,
      MessageStatus.STREAMING,
      MessageStatus.FAILED,
      -> true
      MessageStatus.INTERRUPTED -> message.content.isNotBlank()
      MessageStatus.COMPLETED -> false
    }
  }

  private fun hasContinuityMutationConflict(): Boolean {
    return metaState.value.inProgress || metaState.value.pendingUserMessages.isNotEmpty()
  }

  private suspend fun loadContinuityDebugState(sessionId: String): RoleplayContinuityDebugState {
    val runtimeState = runtimeStateRepository.getLatestSnapshot(sessionId)
    val openThreads =
      openThreadRepository
        .listBySession(sessionId)
        .sortedWith(compareByDescending<OpenThread> { it.priority }.thenByDescending { it.updatedAt })
    val memoryAtoms =
      memoryAtomRepository
        .listBySession(sessionId)
        .filterNot { it.tombstone }
        .sortedWith(
          compareByDescending<MemoryAtom> { it.updatedAt }
            .thenByDescending { it.salience }
            .thenByDescending { it.confidence }
        )
    val recentEvents = conversationRepository.listEvents(sessionId).take(12)
    val latestMemoryQueryPayload =
      recentEvents.firstOrNull { it.eventType == SessionEventType.MEMORY_QUERY_EXECUTED }?.payloadJson
    val latestMemoryPackPayload =
      recentEvents.firstOrNull { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }?.payloadJson
    val compactionEntryCount = compactionCacheRepository.listBySession(sessionId).size
    return RoleplayContinuityDebugState(
      runtimeState = runtimeState,
      openThreads = openThreads.take(8),
      memoryAtoms = memoryAtoms.take(12),
      recentEvents = recentEvents,
      latestMemoryQueryPayload = latestMemoryQueryPayload,
      latestMemoryPackPayload = latestMemoryPackPayload,
      compactionEntryCount = compactionEntryCount,
    )
  }

  private fun String.escapeJson(): String {
    return replace("\\", "\\\\").replace("\"", "\\\"")
  }

  private fun playSendSound() {
    if (!dataStoreRepository.areMessageSoundsEnabled()) {
      return
    }
    RoleplaySoundEffectPlayer.playSend(appContext)
  }

  private fun playReceiveSound() {
    if (!dataStoreRepository.areMessageSoundsEnabled()) {
      return
    }
    RoleplaySoundEffectPlayer.playReceive(appContext)
  }
}
