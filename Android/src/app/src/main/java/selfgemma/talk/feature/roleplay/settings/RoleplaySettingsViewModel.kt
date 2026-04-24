package selfgemma.talk.feature.roleplay.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import selfgemma.talk.BuildConfig
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolIds

data class RoleplayToolToggleUiState(
  val toolId: String,
  val enabled: Boolean,
)

private const val TAG = "RoleplaySettingsViewModel"

data class RoleplaySettingsUiState(
  val messageSoundsEnabled: Boolean = true,
  val liveTokenSpeedEnabled: Boolean = true,
  val streamingOutputEnabled: Boolean = true,
  val roleplayToolDebugOutputEnabled: Boolean = false,
  val toolStates: List<RoleplayToolToggleUiState> = emptyList(),
  val roleEditorAssistantModelId: String? = null,
) {
  val allToolsEnabled: Boolean
    get() = toolStates.isNotEmpty() && toolStates.all(RoleplayToolToggleUiState::enabled)
}

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
        roleplayToolDebugOutputEnabled =
          BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS &&
            dataStoreRepository.isRoleplayToolDebugOutputEnabled(),
        toolStates = buildToolStates(),
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
    val allowed = BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS && enabled
    dataStoreRepository.setRoleplayToolDebugOutputEnabled(allowed)
    _uiState.value = _uiState.value.copy(roleplayToolDebugOutputEnabled = allowed)
    logDebug("roleplay tool debug output updated enabled=$allowed")
  }

  fun setRoleplayToolEnabled(toolId: String, enabled: Boolean) {
    dataStoreRepository.setRoleplayToolEnabled(toolId, enabled)
    _uiState.value = _uiState.value.copy(toolStates = buildToolStates())
    logDebug("roleplay tool updated toolId=$toolId enabled=$enabled")
  }

  fun setAllRoleplayToolsEnabled(enabled: Boolean) {
    dataStoreRepository.setAllRoleplayToolsEnabled(RoleplayToolIds.all, enabled)
    _uiState.value = _uiState.value.copy(toolStates = buildToolStates())
    logDebug("all roleplay tools updated enabled=$enabled")
  }

  fun setRoleEditorAssistantModelId(modelId: String?) {
    dataStoreRepository.setRoleEditorAssistantModelId(modelId)
    _uiState.value = _uiState.value.copy(roleEditorAssistantModelId = modelId)
    logDebug("role editor assistant model updated modelId=$modelId")
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private fun buildToolStates(): List<RoleplayToolToggleUiState> {
    return roleplayToolManagementEntries.map { entry ->
      RoleplayToolToggleUiState(
        toolId = entry.toolId,
        enabled = dataStoreRepository.isRoleplayToolEnabled(entry.toolId),
      )
    }
  }
}
