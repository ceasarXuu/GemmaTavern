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
        roleEditorAssistantModelId = dataStoreRepository.getRoleEditorAssistantModelId(),
      )
    )
  val uiState: StateFlow<RoleplaySettingsUiState> = _uiState.asStateFlow()

  fun setMessageSoundsEnabled(enabled: Boolean) {
    dataStoreRepository.setMessageSoundsEnabled(enabled)
    _uiState.value = _uiState.value.copy(messageSoundsEnabled = enabled)
    Log.d(TAG, "message sounds updated enabled=$enabled")
  }

  fun setLiveTokenSpeedEnabled(enabled: Boolean) {
    dataStoreRepository.setLiveTokenSpeedEnabled(enabled)
    _uiState.value = _uiState.value.copy(liveTokenSpeedEnabled = enabled)
    Log.d(TAG, "live token speed updated enabled=$enabled")
  }

  fun setStreamingOutputEnabled(enabled: Boolean) {
    dataStoreRepository.setStreamingOutputEnabled(enabled)
    _uiState.value = _uiState.value.copy(streamingOutputEnabled = enabled)
    Log.d(TAG, "streaming output updated enabled=$enabled")
  }

  fun setRoleEditorAssistantModelId(modelId: String?) {
    dataStoreRepository.setRoleEditorAssistantModelId(modelId)
    _uiState.value = _uiState.value.copy(roleEditorAssistantModelId = modelId)
    Log.d(TAG, "role editor assistant model updated modelId=$modelId")
  }
}
