package selfgemma.talk.feature.roleplay.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import selfgemma.talk.data.DataStoreRepository

private const val TAG = "RoleplaySettingsViewModel"

data class RoleplaySettingsUiState(
  val messageSoundsEnabled: Boolean = true,
  val liveTokenSpeedEnabled: Boolean = true,
  val streamingOutputEnabled: Boolean = true,
  val roleplayToolDebugOutputEnabled: Boolean = false,
  val roleplayLocationToolsEnabled: Boolean = false,
  val roleplayCalendarToolsEnabled: Boolean = false,
  val roleEditorAssistantModelId: String? = null,
)

@HiltViewModel
class RoleplaySettingsViewModel
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  private val _uiState =
    MutableStateFlow(
      RoleplaySettingsUiState(
        messageSoundsEnabled = dataStoreRepository.areMessageSoundsEnabled(),
        liveTokenSpeedEnabled = dataStoreRepository.isLiveTokenSpeedEnabled(),
        streamingOutputEnabled = dataStoreRepository.isStreamingOutputEnabled(),
        roleplayToolDebugOutputEnabled = dataStoreRepository.isRoleplayToolDebugOutputEnabled(),
        roleplayLocationToolsEnabled = dataStoreRepository.isRoleplayLocationToolsEnabled(),
        roleplayCalendarToolsEnabled = dataStoreRepository.isRoleplayCalendarToolsEnabled(),
        roleEditorAssistantModelId = dataStoreRepository.getRoleEditorAssistantModelId(),
      )
    )
  val uiState: StateFlow<RoleplaySettingsUiState> = _uiState.asStateFlow()

  fun setMessageSoundsEnabled(enabled: Boolean) {
    dataStoreRepository.setMessageSoundsEnabled(enabled)
    _uiState.value = _uiState.value.copy(messageSoundsEnabled = enabled)
    logDebug("message sounds updated enabled=$enabled")
  }

  fun setLiveTokenSpeedEnabled(enabled: Boolean) {
    dataStoreRepository.setLiveTokenSpeedEnabled(enabled)
    _uiState.value = _uiState.value.copy(liveTokenSpeedEnabled = enabled)
    logDebug("live token speed updated enabled=$enabled")
  }

  fun setStreamingOutputEnabled(enabled: Boolean) {
    dataStoreRepository.setStreamingOutputEnabled(enabled)
    _uiState.value = _uiState.value.copy(streamingOutputEnabled = enabled)
    logDebug("streaming output updated enabled=$enabled")
  }

  fun setRoleplayToolDebugOutputEnabled(enabled: Boolean) {
    dataStoreRepository.setRoleplayToolDebugOutputEnabled(enabled)
    _uiState.value = _uiState.value.copy(roleplayToolDebugOutputEnabled = enabled)
    logDebug("roleplay tool debug output updated enabled=$enabled")
  }

  fun setRoleplayLocationToolsEnabled(enabled: Boolean) {
    dataStoreRepository.setRoleplayLocationToolsEnabled(enabled)
    _uiState.value = _uiState.value.copy(roleplayLocationToolsEnabled = enabled)
    logDebug("roleplay location tools updated enabled=$enabled")
  }

  fun setRoleplayCalendarToolsEnabled(enabled: Boolean) {
    dataStoreRepository.setRoleplayCalendarToolsEnabled(enabled)
    _uiState.value = _uiState.value.copy(roleplayCalendarToolsEnabled = enabled)
    logDebug("roleplay calendar tools updated enabled=$enabled")
  }

  fun setRoleEditorAssistantModelId(modelId: String?) {
    dataStoreRepository.setRoleEditorAssistantModelId(modelId)
    _uiState.value = _uiState.value.copy(roleEditorAssistantModelId = modelId)
    logDebug("role editor assistant model updated modelId=$modelId")
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }
}
