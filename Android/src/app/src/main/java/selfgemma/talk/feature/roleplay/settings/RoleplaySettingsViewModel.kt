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
import selfgemma.talk.data.cloudllm.CloudModelConfigRepository
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderId
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
  val cloudModelConfig: CloudModelConfig = CloudModelConfig(),
  val cloudApiKeySaved: Boolean = false,
) {
  val allToolsEnabled: Boolean
    get() = toolStates.isNotEmpty() && toolStates.all(RoleplayToolToggleUiState::enabled)
}

@HiltViewModel
class RoleplaySettingsViewModel
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val cloudModelConfigRepository: CloudModelConfigRepository,
) : ViewModel() {
  private val _uiState =
    MutableStateFlow(
      dataStoreRepository.getCloudModelConfig().let { cloudConfig ->
        RoleplaySettingsUiState(
          messageSoundsEnabled = dataStoreRepository.areMessageSoundsEnabled(),
          liveTokenSpeedEnabled = dataStoreRepository.isLiveTokenSpeedEnabled(),
          streamingOutputEnabled = dataStoreRepository.isStreamingOutputEnabled(),
          roleplayToolDebugOutputEnabled =
            BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS &&
              dataStoreRepository.isRoleplayToolDebugOutputEnabled(),
          toolStates = buildToolStates(),
          roleEditorAssistantModelId = dataStoreRepository.getRoleEditorAssistantModelId(),
          cloudModelConfig = cloudConfig,
          cloudApiKeySaved = cloudModelConfigRepository.getApiKey(cloudConfig.providerId) != null,
        )
      }
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

  fun saveCloudModelSettings(config: CloudModelConfig, apiKeyInput: String) {
    val normalizedConfig = config.copy(modelName = config.modelName.trim())
    cloudModelConfigRepository.saveConfig(normalizedConfig)
    if (apiKeyInput.isNotBlank()) {
      cloudModelConfigRepository.saveApiKey(normalizedConfig.providerId, apiKeyInput)
    }
    _uiState.value =
      _uiState.value.copy(
        cloudModelConfig = normalizedConfig,
        cloudApiKeySaved =
          cloudModelConfigRepository.getApiKey(normalizedConfig.providerId) != null,
      )
    logDebug(
      "cloud model settings updated provider=${normalizedConfig.providerId.storageId} enabled=${normalizedConfig.enabled} model=${normalizedConfig.modelName} mediaUpload=${normalizedConfig.allowRawMediaUpload}",
    )
  }

  fun clearCloudApiKey(providerId: CloudProviderId) {
    cloudModelConfigRepository.deleteApiKey(providerId)
    _uiState.value =
      _uiState.value.copy(
        cloudApiKeySaved =
          cloudModelConfigRepository.getApiKey(_uiState.value.cloudModelConfig.providerId) != null,
      )
    logDebug("cloud api key cleared provider=${providerId.storageId}")
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
