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

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.update
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatus
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.proto.ImportedModel

private const val TAG = "AGModelManagerVMImport"

internal fun ModelManagerViewModel.getModelUrlResponseExt(model: Model, accessToken: String? = null): Int {
  return try {
    val url = URL(model.url)
    val connection = url.openConnection() as HttpURLConnection
    if (accessToken != null) {
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }
    connection.connect()
    connection.responseCode
  } catch (e: Exception) {
    Log.e(TAG, "$e")
    -1
  }
}

internal fun ModelManagerViewModel.addImportedLlmModelExt(info: ImportedModel) {
  Log.d(TAG, "adding imported llm model: $info")
  val model = createModelFromImportedModelInfoExt(info = info)
  val setOfTasks =
    mutableSetOf(
      BuiltInTaskId.LLM_CHAT,
      BuiltInTaskId.LLM_ASK_IMAGE,
      BuiltInTaskId.LLM_ASK_AUDIO,
      BuiltInTaskId.LLM_PROMPT_LAB,
      BuiltInTaskId.LLM_TINY_GARDEN,
      BuiltInTaskId.LLM_MOBILE_ACTIONS,
      BuiltInTaskId.LLM_AGENT_CHAT,
    )
  for (task in getTasksByIds(ids = setOfTasks)) {
    val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
    if (modelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in task. Removing it first")
      task.models.removeAt(modelIndex)
    }
    if (
      (task.id == BuiltInTaskId.LLM_ASK_IMAGE && model.llmSupportImage) ||
        (task.id == BuiltInTaskId.LLM_ASK_AUDIO && model.llmSupportAudio) ||
        (task.id == BuiltInTaskId.LLM_TINY_GARDEN && model.llmSupportTinyGarden) ||
        (task.id == BuiltInTaskId.LLM_MOBILE_ACTIONS && model.llmSupportMobileActions) ||
        (task.id != BuiltInTaskId.LLM_ASK_IMAGE &&
          task.id != BuiltInTaskId.LLM_ASK_AUDIO &&
          task.id != BuiltInTaskId.LLM_TINY_GARDEN &&
          task.id != BuiltInTaskId.LLM_MOBILE_ACTIONS)
    ) {
      task.models.add(model)
      if (task.id == BuiltInTaskId.LLM_TINY_GARDEN) {
        val newConfigs = model.configs.toMutableList()
        newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
        model.configs = newConfigs
        model.preProcess()
      }
    }
    task.updateTrigger.value = System.currentTimeMillis()
  }

  val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
  val modelInstances = uiState.value.modelInitializationStatus.toMutableMap()
  modelDownloadStatus[model.name] =
    ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = info.fileSize,
      totalBytes = info.fileSize,
    )
  modelInstances[model.name] =
    ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

  _uiState.update {
    uiState.value.copy(
      tasks = uiState.value.tasks.toList(),
      modelDownloadStatus = modelDownloadStatus,
      modelInitializationStatus = modelInstances,
      modelImportingUpdateTrigger = System.currentTimeMillis(),
    )
  }

  val importedModels = dataStoreRepository.readImportedModels().toMutableList()
  val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
  if (importedModelIndex >= 0) {
    Log.d(TAG, "duplicated imported model found in data store. Removing it first")
    importedModels.removeAt(importedModelIndex)
  }
  importedModels.add(info)
  dataStoreRepository.saveImportedModels(importedModels = importedModels)
}

internal fun ModelManagerViewModel.updateImportedLlmModelConfigExt(
  model: Model,
  values: Map<String, Any>,
): Boolean {
  if (!model.imported) {
    Log.w(TAG, "Ignoring config update for non-imported model '${model.name}'")
    return false
  }

  val importedModels = dataStoreRepository.readImportedModels().toMutableList()
  val importedModelIndex = importedModels.indexOfFirst { model.name == it.fileName }
  if (importedModelIndex < 0) {
    Log.w(TAG, "Cannot update imported model config because '${model.name}' is not in data store")
    return false
  }

  val updatedInfo =
    updatedImportedModelWithConfigValues(
      importedModel = importedModels[importedModelIndex],
      values = values,
    )
  importedModels[importedModelIndex] = updatedInfo
  dataStoreRepository.saveImportedModels(importedModels = importedModels)

  val rebuiltModel = createModelFromImportedModelInfoExt(info = updatedInfo)
  val llmChatTask = getTaskById(BuiltInTaskId.LLM_CHAT)
  var touched = false
  val updatedModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
  val updatedModelInitializationStatus = uiState.value.modelInitializationStatus.toMutableMap()
  val cleanupRequestedModels = mutableListOf<Model>()

  for (task in uiState.value.tasks) {
    for (taskModel in task.models) {
      if (taskModel.name != model.name || !taskModel.imported) continue
      taskModel.prevConfigValues = taskModel.configValues
      taskModel.configs = rebuiltModel.configs
      taskModel.configValues = rebuiltModel.configValues
      taskModel.totalBytes = rebuiltModel.totalBytes
      task.updateTrigger.value = System.currentTimeMillis()
      touched = true

      if (taskModel.instance != null || taskModel.initializing) {
        if (cleanupRequestedModels.any { it === taskModel }) continue
        cleanupRequestedModels.add(taskModel)
        if (llmChatTask != null) {
          cleanupModel(context = context, task = llmChatTask, model = taskModel)
        } else {
          taskModel.cleanUpAfterInit = true
        }
      }
    }
  }

  updatedModelDownloadStatus[model.name] =
    ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = updatedInfo.fileSize,
      totalBytes = updatedInfo.fileSize,
    )
  updatedModelInitializationStatus[model.name] =
    ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

  _uiState.update {
    uiState.value.copy(
      tasks = uiState.value.tasks.toList(),
      modelDownloadStatus = updatedModelDownloadStatus,
      modelInitializationStatus = updatedModelInitializationStatus,
      modelImportingUpdateTrigger = System.currentTimeMillis(),
      configValuesUpdateTrigger = System.currentTimeMillis(),
    )
  }

  Log.d(
    TAG,
    "updated imported model config model=${model.name} touched=$touched " +
      "maxTokens=${updatedInfo.llmConfig.defaultMaxTokens} topK=${updatedInfo.llmConfig.defaultTopk} " +
      "topP=${updatedInfo.llmConfig.defaultTopp} temperature=${updatedInfo.llmConfig.defaultTemperature}",
  )
  return true
}
