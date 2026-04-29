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
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.BuildConfig
import selfgemma.talk.common.getJsonResponse
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelAllowlist
import selfgemma.talk.data.SOC

private const val TAG = "AGModelManagerVMAllowlist"

internal fun ModelManagerViewModel.loadModelAllowlistExt() {
  _uiState.update {
    uiState.value.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "")
  }

  viewModelScope.launch(Dispatchers.IO) {
    try {
      var modelAllowlist: ModelAllowlist? = null

      Log.d(TAG, "Loading test model allowlist.")
      modelAllowlist = readModelAllowlistFromDiskExt(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

      if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
        Log.d(TAG, "Loading local model allowlist for testing.")
        val gson = Gson()
        try {
          modelAllowlist = gson.fromJson(TEST_MODEL_ALLOW_LIST, ModelAllowlist::class.java)
        } catch (e: JsonSyntaxException) {
          Log.e(TAG, "Failed to parse local test json", e)
        }
      }

      if (modelAllowlist == null) {
        var version = BuildConfig.VERSION_NAME.replace(".", "_")
        val url = getAllowlistUrl(version)
        Log.d(TAG, "Loading model allowlist from internet. Url: $url")
        val data = getJsonResponse<ModelAllowlist>(url = url)
        modelAllowlist = data?.jsonObj

        if (modelAllowlist == null) {
          Log.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
          modelAllowlist = readModelAllowlistFromDiskExt()
        } else {
          Log.d(TAG, "Done: loading model allowlist from internet")
          saveModelAllowlistToDiskExt(modelAllowlistContent = data?.textContent ?: "{}")
        }
      }

      if (modelAllowlist == null) {
        publishModelAllowlistFailureExt("Failed to load model list")
        return@launch
      }

      Log.d(TAG, "Allowlist: $modelAllowlist")

      val curTasks = getActiveCustomTasks().map { it.task }
      val nameToModel = mutableMapOf<String, Model>()
      for (allowedModel in modelAllowlist.models) {
        if (allowedModel.disabled == true) continue

        val accelerators = allowedModel.defaultConfig.accelerators ?: ""
        val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
          val socToModelFiles = allowedModel.socToModelFiles
          if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
            Log.d(
              TAG,
              "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
            )
            continue
          }
        }

        val model = allowedModel.toModel()
        nameToModel.put(model.name, model)
        for (taskType in allowedModel.taskTypes) {
          val task = curTasks.find { it.id == taskType }
          task?.models?.add(model)

          if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
            val newConfigs = model.configs.toMutableList()
            newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
            model.configs = newConfigs
          }
        }
      }

      for (task in curTasks) {
        if (task.modelNames.isNotEmpty()) {
          for (modelName in task.modelNames) {
            val model = nameToModel[modelName]
            if (model == null) {
              Log.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
              continue
            }
            task.models.add(model)
          }
        }
      }

      processTasks()

      _uiState.update {
        createUiStateExt()
          .copy(
            loadingModelAllowlist = false,
            tasks = curTasks,
            tasksByCategory = groupTasksByCategoryExt(),
          )
      }

      preloadLastUsedLlmModelExt()

      processPendingDownloadsExt()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load model allowlist", e)
      publishModelAllowlistFailureExt("Failed to load model list")
    }
  }
}

internal fun ModelManagerViewModel.publishModelAllowlistFailureExt(message: String) {
  val curTasks = getActiveCustomTasks().map { it.task }
  processTasks()
  _uiState.update {
    createUiStateExt()
      .copy(
        loadingModelAllowlist = false,
        tasks = curTasks,
        loadingModelAllowlistError = message,
        tasksByCategory = groupTasksByCategoryExt(),
      )
  }
  preloadLastUsedLlmModelExt()
}

internal fun ModelManagerViewModel.clearLoadModelAllowlistErrorExt() {
  val curTasks = getActiveCustomTasks().map { it.task }
  processTasks()
  _uiState.update {
    createUiStateExt()
      .copy(
        loadingModelAllowlist = false,
        tasks = curTasks,
        loadingModelAllowlistError = "",
        tasksByCategory = groupTasksByCategoryExt(),
      )
  }
}

internal fun ModelManagerViewModel.saveModelAllowlistToDiskExt(modelAllowlistContent: String) {
  try {
    Log.d(TAG, "Saving model allowlist to disk...")
    val file = File(externalFilesDir, MODEL_ALLOWLIST_FILENAME)
    file.writeText(modelAllowlistContent)
    Log.d(TAG, "Done: saving model allowlist to disk.")
  } catch (e: Exception) {
    Log.e(TAG, "failed to write model allowlist to disk", e)
  }
}

internal fun ModelManagerViewModel.readModelAllowlistFromDiskExt(
  fileName: String = MODEL_ALLOWLIST_FILENAME
): ModelAllowlist? {
  try {
    Log.d(TAG, "Reading model allowlist from disk: $fileName")
    val baseDir =
      if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else externalFilesDir
    val file = File(baseDir, fileName)
    if (file.exists()) {
      val content = file.readText()
      Log.d(TAG, "Model allowlist content from local file: $content")
      val gson = Gson()
      return gson.fromJson(content, ModelAllowlist::class.java)
    }
  } catch (e: Exception) {
    Log.e(TAG, "failed to read model allowlist from disk", e)
    return null
  }
  return null
}
