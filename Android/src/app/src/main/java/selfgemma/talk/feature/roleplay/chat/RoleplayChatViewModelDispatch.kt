package selfgemma.talk.feature.roleplay.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.runtime.runtimeHelper

private const val SEND_DISPATCH_DELAY_MS = 2_000L

internal fun RoleplayChatViewModel.remainingDispatchDelayMs(): Long {
  val elapsed = elapsedRealtimeProvider() - lastDraftEditAtElapsed
  return (SEND_DISPATCH_DELAY_MS - elapsed).coerceAtLeast(0L)
}

internal fun RoleplayChatViewModel.scheduleDispatch(reason: String) {
  val model = latestQueuedModel ?: return
  dispatchJob?.cancel()
  dispatchJob =
    viewModelScope.launch {
      while (true) {
        val delayMs = remainingDispatchDelayMs()
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

internal fun RoleplayChatViewModel.requestMergeAndStop(model: Model) {
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

internal fun RoleplayChatViewModel.dispatchPendingMessages(model: Model) {
  val queuedMessages = metaState.value.pendingUserMessages
  if (queuedMessages.isEmpty() || metaState.value.inProgress) {
    return
  }

  val stagedTurn = stageDispatchTurn(userMessages = queuedMessages.map { it.message }, model = model)
  val persistedIds = queuedMessages.filter { it.persisted }.mapTo(mutableSetOf()) { it.message.id }
  val queuedIds = queuedMessages.mapTo(mutableSetOf()) { it.message.id }
  val dispatchStartedAt = elapsedRealtimeProvider()

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
        "dispatch queue failed after ${elapsedRealtimeProvider() - dispatchStartedAt}ms sessionId=$sessionId",
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
      "dispatch queued after ${elapsedRealtimeProvider() - dispatchStartedAt}ms sessionId=$sessionId assistantMessageId=${stagedTurn.assistantMessage.id}",
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
      "dispatch finished after ${elapsedRealtimeProvider() - dispatchStartedAt}ms sessionId=$sessionId interrupted=${result.interrupted} superseded=$superseded error=${result.errorMessage != null}",
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
