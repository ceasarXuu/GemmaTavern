/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package selfgemma.talk.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatus
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.runtime.runtimeHelper

private const val TAG = "AGModelManagerVMLifecycle"

internal fun ModelManagerViewModel.downloadModelExt(task: Task?, model: Model) {
  setDownloadStatus(
    curModel = model,
    status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
  )
  deleteModel(model = model)
  downloadRepository.downloadModel(
    task = task,
    model = model,
    onStatusUpdated = this::setDownloadStatus,
  )
}

internal fun ModelManagerViewModel.cancelDownloadModelExt(model: Model) {
  downloadRepository.cancelDownloadModel(model)
  deleteModel(model = model)
}

internal fun ModelManagerViewModel.deleteModelExt(model: Model) {
  if (model.imported) {
    deleteFilesFromImportDir(model.downloadFileName)
  } else {
    deleteDirFromExternalFilesDir(model.normalizedName)
  }

  val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
  curModelDownloadStatus[model.name] =
    ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

  if (model.imported) {
    for (curTask in uiState.value.tasks) {
      val index = curTask.models.indexOf(model)
      if (index >= 0) {
        curTask.models.removeAt(index)
      }
      curTask.updateTrigger.value = System.currentTimeMillis()
    }
    curModelDownloadStatus.remove(model.name)

    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
    if (importedModelIndex >= 0) {
      importedModels.removeAt(importedModelIndex)
    }
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }
  val newUiState =
    uiState.value.copy(
      modelDownloadStatus = curModelDownloadStatus,
      tasks = uiState.value.tasks.toList(),
      modelImportingUpdateTrigger = System.currentTimeMillis(),
    )
  _uiState.update { newUiState }
}

internal fun ModelManagerViewModel.initializeModelExt(
  context: Context,
  task: Task,
  model: Model,
  force: Boolean = false,
  onDone: () -> Unit = {},
) {
  val vm = this
  viewModelScope.launch(Dispatchers.Default) {
    if (
      !force &&
        vm.uiState.value.modelInitializationStatus[model.name]?.status ==
          ModelInitializationStatusType.INITIALIZED
    ) {
      Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
      return@launch
    }

    if (model.initializing) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
      return@launch
    }

    runInitializationAfterOptionalCleanup(
      hasExistingInstance = model.instance != null,
      startCleanup = { onCleanupDone ->
        Log.d(
          TAG,
          "Cleaning up existing model '${model.name}' before initialization so the next instance is not raced by stale cleanup.",
        )
        vm.cleanupModel(
          context = context,
          task = task,
          model = model,
          onDone = {
            vm.viewModelScope.launch(Dispatchers.Default) { onCleanupDone() }
          },
        )
      },
      startInitialization = {
        Log.d(TAG, "Initializing model '${model.name}'...")
        model.initializing = true
        vm.updateModelInitializationStatusExt(
          model = model,
          status = ModelInitializationStatusType.INITIALIZING,
        )

        val onDoneFn: (error: String) -> Unit = { error ->
          model.initializing = false
          if (model.instance != null) {
            Log.d(TAG, "Model '${model.name}' initialized successfully")
            vm.updateModelInitializationStatusExt(
              model = model,
              status = ModelInitializationStatusType.INITIALIZED,
            )
            if (model.cleanUpAfterInit) {
              Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
              vm.cleanupModel(context = context, task = task, model = model)
            }
            onDone()
          } else if (error.isNotEmpty()) {
            Log.d(TAG, "Model '${model.name}' failed to initialize")
            vm.updateModelInitializationStatusExt(
              model = model,
              status = ModelInitializationStatusType.ERROR,
              error = error,
            )
          }
        }

        vm.getCustomTaskByTaskId(id = task.id)
          ?.initializeModelFn(
            context = context,
            coroutineScope = vm.viewModelScope,
            model = model,
            onDone = onDoneFn,
          )
      },
    )
  }
}

internal fun ModelManagerViewModel.initializeLlmModelExt(
  context: Context,
  model: Model,
  supportImage: Boolean,
  supportAudio: Boolean,
  force: Boolean = false,
  onDone: () -> Unit = {},
) {
  val vm = this
  viewModelScope.launch(Dispatchers.Default) {
    if (
      !force &&
        vm.uiState.value.modelInitializationStatus[model.name]?.status ==
          ModelInitializationStatusType.INITIALIZED
    ) {
      Log.d(TAG, "Model '${model.name}' has been initialized for multimodal chat. Skipping.")
      return@launch
    }

    if (model.initializing) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Model '${model.name}' is being initialized for multimodal chat. Skipping.")
      return@launch
    }

    val llmChatTask = vm.getTaskById(BuiltInTaskId.LLM_CHAT) ?: return@launch
    runInitializationAfterOptionalCleanup(
      hasExistingInstance = model.instance != null,
      startCleanup = { onCleanupDone ->
        Log.d(
          TAG,
          "Cleaning up existing model '${model.name}' before multimodal initialization so the replacement session is not cleared by the previous cleanup callback.",
        )
        vm.cleanupModel(
          context = context,
          task = llmChatTask,
          model = model,
          onDone = {
            vm.viewModelScope.launch(Dispatchers.Default) { onCleanupDone() }
          },
        )
      },
      startInitialization = {
        Log.d(
          TAG,
          "Initializing model '${model.name}' for multimodal roleplay supportImage=$supportImage supportAudio=$supportAudio",
        )
        model.initializing = true
        vm.updateModelInitializationStatusExt(
          model = model,
          status = ModelInitializationStatusType.INITIALIZING,
        )

        val onDoneFn: (String) -> Unit = { error ->
          model.initializing = false
          if (model.instance != null) {
            Log.d(TAG, "Model '${model.name}' initialized successfully for multimodal roleplay")
            vm.updateModelInitializationStatusExt(
              model = model,
              status = ModelInitializationStatusType.INITIALIZED,
            )
            if (model.cleanUpAfterInit) {
              vm.getTaskById(BuiltInTaskId.LLM_CHAT)?.let { task ->
                Log.d(TAG, "Model '${model.name}' needs cleaning up after multimodal init.")
                vm.cleanupModel(context = context, task = task, model = model)
              }
            }
            onDone()
          } else if (error.isNotEmpty()) {
            Log.d(TAG, "Model '${model.name}' failed multimodal initialization")
            vm.updateModelInitializationStatusExt(
              model = model,
              status = ModelInitializationStatusType.ERROR,
              error = error,
            )
          }
        }

        model.runtimeHelper.initialize(
          context = context,
          model = model,
          supportImage = supportImage,
          supportAudio = supportAudio,
          onDone = onDoneFn,
          coroutineScope = vm.viewModelScope,
        )
      },
    )
  }
}

internal fun ModelManagerViewModel.cleanupModelExt(
  context: Context,
  task: Task,
  model: Model,
  instanceToCleanUp: Any? = model.instance,
  onDone: () -> Unit = {},
) {
  if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
    Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
    onDone()
    return
  }

  if (model.instance != null) {
    model.cleanUpAfterInit = false
    Log.d(TAG, "Cleaning up model '${model.name}'...")
    val onDoneFn: () -> Unit = {
      model.instance = null
      model.initializing = false
      updateModelInitializationStatusExt(
        model = model,
        status = ModelInitializationStatusType.NOT_INITIALIZED,
      )
      Log.d(TAG, "Clean up model '${model.name}' done")
      onDone()
    }
    getCustomTaskByTaskId(id = task.id)
      ?.cleanUpModelFn(
        context = context,
        coroutineScope = viewModelScope,
        model = model,
        onDone = onDoneFn,
      )
  } else {
    if (model.initializing) {
      Log.d(
        TAG,
        "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
      )
      model.cleanUpAfterInit = true
    }
  }
}

internal fun ModelManagerViewModel.setDownloadStatusExt(
  curModel: Model,
  status: ModelDownloadStatus,
) {
  val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
  curModelDownloadStatus[curModel.name] = status
  val newUiState = uiState.value.copy(modelDownloadStatus = curModelDownloadStatus)

  if (
    status.status == ModelDownloadStatusType.FAILED ||
      status.status == ModelDownloadStatusType.NOT_DOWNLOADED
  ) {
    deleteFileFromExternalFilesDir(curModel.downloadFileName)
  }

  _uiState.update { newUiState }
}

internal fun ModelManagerViewModel.setInitializationStatusExt(
  model: Model,
  status: ModelInitializationStatus,
) {
  val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
  if (curStatus.containsKey(model.name)) {
    val initializedBackends = curStatus[model.name]?.initializedBackends ?: setOf()
    val backend =
      model.getStringConfigValue(
        key = ConfigKeys.ACCELERATOR,
        defaultValue = Accelerator.GPU.label,
      )
    val newInitializedBackends =
      if (status.status == ModelInitializationStatusType.INITIALIZED) {
        initializedBackends + backend
      } else {
        initializedBackends
      }
    curStatus[model.name] = status.copy(initializedBackends = newInitializedBackends)
    _uiState.update { _uiState.value.copy(modelInitializationStatus = curStatus) }
  }
}
