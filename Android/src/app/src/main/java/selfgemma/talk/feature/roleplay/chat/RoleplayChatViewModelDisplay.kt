package selfgemma.talk.feature.roleplay.chat

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val CHAT_STATUS_MESSAGE_AUTO_DISMISS_MS_INTERNAL = 2_000L

internal fun RoleplayChatViewModel.playSendSound() {
  if (!dataStoreRepository.areMessageSoundsEnabled()) {
    return
  }
  RoleplaySoundEffectPlayer.playSend(appContext)
}

internal fun RoleplayChatViewModel.playReceiveSound() {
  if (!dataStoreRepository.areMessageSoundsEnabled()) {
    return
  }
  RoleplaySoundEffectPlayer.playReceive(appContext)
}

internal fun RoleplayChatViewModel.appString(@StringRes resId: Int, vararg args: Any): String {
  return stringResolver(resId, args.toList())
}

internal fun RoleplayChatViewModel.displaySessionIdShort(sessionId: String): String {
  return if (sessionId.length <= 12) sessionId else sessionId.take(8)
}

internal fun RoleplayChatViewModel.showStatusMessage(message: String) {
  statusMessageDismissJob?.cancel()
  logDebug("show status message sessionId=$sessionId message=$message")
  metaState.update { current ->
    current.copy(statusMessage = message, errorMessage = null)
  }
  statusMessageDismissJob =
    viewModelScope.launch {
      delay(CHAT_STATUS_MESSAGE_AUTO_DISMISS_MS)
      metaState.update { current ->
        if (current.statusMessage == message) {
          logDebug("auto-dismiss status message sessionId=$sessionId message=$message")
          current.copy(statusMessage = null)
        } else {
          current
        }
      }
    }
}

internal fun RoleplayChatViewModel.showErrorMessage(message: String) {
  statusMessageDismissJob?.cancel()
  logWarn("show error message sessionId=$sessionId message=$message")
  metaState.update { current ->
    current.copy(statusMessage = null, errorMessage = message)
  }
}
