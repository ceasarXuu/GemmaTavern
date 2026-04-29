/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import selfgemma.talk.AppLifecycleProvider
import selfgemma.talk.BuildConfig
import selfgemma.talk.customtasks.common.CustomTask
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.Category
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.DownloadRepository
import selfgemma.talk.data.EMPTY_MODEL
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatus
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.proto.AccessTokenData
import selfgemma.talk.proto.ImportedModel
import selfgemma.talk.proto.Theme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService

private const val TAG = "AGModelManagerViewModel"

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
  var initializedBackends: Set<String> = setOf(),
) {
  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.contains(backend)
  }
}

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}

enum class TokenStatus {
  NOT_STORED,
  EXPIRED,
  NOT_EXPIRED,
}

enum class TokenRequestResultType {
  FAILED,
  SUCCEEDED,
  USER_CANCELLED,
}

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)

data class TokenRequestResult(val status: TokenRequestResultType, val errorMessage: String? = null)

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** A map that tracks the initialization status of each model, indexed by model name. */
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val settingsUpdateTrigger: Long = 0L,
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZED
  }

  fun isModelInitializing(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZING
  }
}

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  internal val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  internal val lifecycleProvider: AppLifecycleProvider,
  internal val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  @ApplicationContext internal val context: Context,
) : ViewModel() {
  internal val externalFilesDir = context.getExternalFilesDir(null)
  internal val _uiState = MutableStateFlow(createEmptyUiState())
  val uiState = _uiState.asStateFlow()

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  override fun onCleared() {
    authService.dispose()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() {
    val curTasks = getActiveCustomTasks().map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { _uiState.value.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun updateSettingsUpdateTrigger() {
    _uiState.update { _uiState.value.copy(settingsUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { _uiState.value.copy(selectedModel = model) }
    }
    if (model.isLlm) {
      dataStoreRepository.setLastUsedLlmModelId(model.name)
    }
  }

  fun downloadModel(task: Task?, model: Model) = downloadModelExt(task, model)

  fun cancelDownloadModel(model: Model) = cancelDownloadModelExt(model)

  fun deleteModel(model: Model) = deleteModelExt(model)

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
  ) = initializeModelExt(context, task, model, force, onDone)

  fun initializeLlmModel(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    force: Boolean = false,
    onDone: () -> Unit = {},
  ) = initializeLlmModelExt(context, model, supportImage, supportAudio, force, onDone)

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) = cleanupModelExt(context, task, model, instanceToCleanUp, onDone)

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) =
    setDownloadStatusExt(curModel, status)

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) =
    setInitializationStatusExt(model, status)

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { _uiState.value.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { _uiState.value.copy(textInputHistory = mutableListOf()) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  fun setLiveTokenSpeedEnabled(enabled: Boolean) {
    Log.d(TAG, "Updating live token speed visibility enabled=$enabled")
    dataStoreRepository.setLiveTokenSpeedEnabled(enabled)
    updateSettingsUpdateTrigger()
  }

  fun isLiveTokenSpeedEnabled(): Boolean {
    return dataStoreRepository.isLiveTokenSpeedEnabled()
  }

  fun setStreamingOutputEnabled(enabled: Boolean) {
    Log.d(TAG, "Updating streaming output enabled=$enabled")
    dataStoreRepository.setStreamingOutputEnabled(enabled)
    updateSettingsUpdateTrigger()
  }

  fun isStreamingOutputEnabled(): Boolean {
    return dataStoreRepository.isStreamingOutputEnabled()
  }

  fun isRoleplayToolDebugOutputEnabled(): Boolean {
    return BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS &&
      dataStoreRepository.isRoleplayToolDebugOutputEnabled()
  }

  fun getModelUrlResponse(model: Model, accessToken: String? = null): Int =
    getModelUrlResponseExt(model, accessToken)

  fun addImportedLlmModel(info: ImportedModel) = addImportedLlmModelExt(info)

  fun updateImportedLlmModelConfig(model: Model, values: Map<String, Any>): Boolean =
    updateImportedLlmModelConfigExt(model, values)

  fun getTokenStatusAndData(): TokenStatusAndData = getTokenStatusAndDataExt()

  fun getAuthorizationRequest(): AuthorizationRequest? = getAuthorizationRequestExt()

  fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) =
    handleAuthResultExt(result, onTokenRequested)

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) =
    saveAccessTokenExt(accessToken, refreshToken, expiresAt)

  fun clearAccessToken() = clearAccessTokenExt()

  private fun processPendingDownloads() = processPendingDownloadsExt()

  fun loadModelAllowlist() = loadModelAllowlistExt()

  fun clearLoadModelAllowlistError() = clearLoadModelAllowlistErrorExt()

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun createEmptyUiState(): ModelManagerUiState = createEmptyUiStateExt()
  private fun createUiState(): ModelManagerUiState = createUiStateExt()
  private fun createModelFromImportedModelInfo(info: ImportedModel): Model =
    createModelFromImportedModelInfoExt(info)
  private fun groupTasksByCategory(): Map<String, List<Task>> = groupTasksByCategoryExt()
  private fun preloadLastUsedLlmModel() = preloadLastUsedLlmModelExt()
  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    getModelDownloadStatusExt(model)

  private fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) = updateModelInitializationStatusExt(model = model, status = status, error = error)
}

internal fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}
