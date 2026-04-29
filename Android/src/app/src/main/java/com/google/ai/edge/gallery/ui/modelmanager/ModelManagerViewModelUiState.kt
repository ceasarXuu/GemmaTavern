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
import java.io.File
import kotlinx.coroutines.flow.update
import selfgemma.talk.R
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Category
import selfgemma.talk.data.CategoryInfo
import selfgemma.talk.data.Config
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatus
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.RuntimeType
import selfgemma.talk.data.TMP_FILE_EXT
import selfgemma.talk.data.Task
import selfgemma.talk.data.createLlmChatConfigs
import selfgemma.talk.proto.ImportedModel

private const val TAG = "AGModelManagerVMUiState"

internal fun ModelManagerViewModel.createEmptyUiStateExt(): ModelManagerUiState =
  ModelManagerUiState(
    tasks = listOf(),
    tasksByCategory = mapOf(),
    modelDownloadStatus = mapOf(),
    modelInitializationStatus = mapOf(),
  )

internal fun ModelManagerViewModel.createUiStateExt(): ModelManagerUiState {
  val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
  val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
  val tasks: MutableMap<String, Task> = mutableMapOf()
  val checkedModelNames = mutableSetOf<String>()
  for (customTask in getActiveCustomTasks()) {
    val task = customTask.task
    tasks.put(key = task.id, value = task)
    for (model in task.models) {
      if (checkedModelNames.contains(model.name)) continue
      modelDownloadStatus[model.name] = getModelDownloadStatusExt(model = model)
      modelInstances[model.name] =
        ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
      checkedModelNames.add(model.name)
    }
  }

  for (importedModel in dataStoreRepository.readImportedModels()) {
    Log.d(TAG, "stored imported model: $importedModel")
    val model = createModelFromImportedModelInfoExt(info = importedModel)
    tasks.get(key = BuiltInTaskId.LLM_CHAT)?.models?.add(model)
    tasks.get(key = BuiltInTaskId.LLM_PROMPT_LAB)?.models?.add(model)
    tasks.get(key = BuiltInTaskId.LLM_AGENT_CHAT)?.models?.add(model)
    if (model.llmSupportImage) tasks.get(key = BuiltInTaskId.LLM_ASK_IMAGE)?.models?.add(model)
    if (model.llmSupportAudio) tasks.get(key = BuiltInTaskId.LLM_ASK_AUDIO)?.models?.add(model)
    if (model.llmSupportTinyGarden) {
      tasks.get(key = BuiltInTaskId.LLM_TINY_GARDEN)?.models?.add(model)
      val newConfigs = model.configs.toMutableList()
      newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
      model.configs = newConfigs
      model.preProcess()
    }
    if (model.llmSupportMobileActions) {
      tasks.get(key = BuiltInTaskId.LLM_MOBILE_ACTIONS)?.models?.add(model)
    }

    modelDownloadStatus[model.name] =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = importedModel.fileSize,
        totalBytes = importedModel.fileSize,
      )
    modelInstances[model.name] =
      ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
  }

  val textInputHistory = dataStoreRepository.readTextInputHistory()
  Log.d(TAG, "text input history: $textInputHistory")
  Log.d(TAG, "model download status: $modelDownloadStatus")
  return ModelManagerUiState(
    tasks = getActiveCustomTasks().map { it.task }.toList(),
    tasksByCategory = mapOf(),
    modelDownloadStatus = modelDownloadStatus,
    modelInitializationStatus = modelInstances,
    textInputHistory = textInputHistory,
  )
}

internal fun ModelManagerViewModel.createModelFromImportedModelInfoExt(info: ImportedModel): Model {
  val accelerators: MutableList<Accelerator> =
    info.llmConfig.compatibleAcceleratorsList
      .mapNotNull { acceleratorLabel ->
        when (acceleratorLabel.trim()) {
          Accelerator.GPU.label -> Accelerator.GPU
          Accelerator.CPU.label -> Accelerator.CPU
          Accelerator.NPU.label -> Accelerator.NPU
          else -> null
        }
      }
      .toMutableList()
  val llmMaxToken = info.llmConfig.defaultMaxTokens
  val llmSupportImage = info.llmConfig.supportImage
  val llmSupportAudio = info.llmConfig.supportAudio
  val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
  val llmSupportMobileActions = info.llmConfig.supportMobileActions
  val llmSupportThinking = info.llmConfig.supportThinking
  val configs: MutableList<Config> =
    createLlmChatConfigs(
        defaultMaxToken = llmMaxToken,
        defaultMaxContextLength = IMPORTED_MODEL_MAX_CONTEXT_LENGTH,
        defaultTopK = info.llmConfig.defaultTopk,
        defaultTopP = info.llmConfig.defaultTopp,
        defaultTemperature = info.llmConfig.defaultTemperature,
        accelerators = accelerators,
        supportThinking = llmSupportThinking,
        defaultEnableThinking = info.llmConfig.defaultEnableThinking,
      )
      .toMutableList()
  val model =
    Model(
      name = info.fileName,
      url = "",
      configs = configs,
      sizeInBytes = info.fileSize,
      downloadFileName = "${selfgemma.talk.data.IMPORTS_DIR}/${info.fileName}",
      showBenchmarkButton = false,
      showRunAgainButton = false,
      imported = true,
      llmSupportImage = llmSupportImage,
      llmSupportAudio = llmSupportAudio,
      llmSupportTinyGarden = llmSupportTinyGarden,
      llmSupportMobileActions = llmSupportMobileActions,
      llmSupportThinking = llmSupportThinking,
      llmMaxToken = llmMaxToken,
      accelerators = accelerators,
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
    )
  model.preProcess()
  return model
}

internal fun ModelManagerViewModel.groupTasksByCategoryExt(): Map<String, List<Task>> {
  val tasks = getActiveCustomTasks().map { it.task }
  val categoryMap: Map<String, CategoryInfo> =
    tasks.associateBy { it.category.id }.mapValues { it.value.category }
  val groupedTasks = tasks.groupBy { it.category.id }
  val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
  for (categoryId in groupedTasks.keys) {
    val sortedTasks =
      groupedTasks[categoryId]!!.sortedWith { a, b ->
        if (categoryId == Category.LLM.id) {
          val order: List<String> =
            when (categoryId) {
              Category.LLM.id -> PREDEFINED_LLM_TASK_ORDER
              else -> listOf()
            }
          val indexA = order.indexOf(a.id)
          val indexB = order.indexOf(b.id)
          if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
          else if (indexA != -1) -1
          else if (indexB != -1) 1
          else {
            val ca = categoryMap[a.id]!!
            val cb = categoryMap[b.id]!!
            getCategoryLabelExt(context = context, category = ca)
              .compareTo(getCategoryLabelExt(context = context, category = cb))
          }
        } else a.label.compareTo(b.label)
      }
    for ((index, task) in sortedTasks.withIndex()) task.index = index
    groupedSortedTasks[categoryId] = sortedTasks
  }
  return groupedSortedTasks
}

internal fun getCategoryLabelExt(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) return context.getString(stringRes)
  if (label != null) return label
  return context.getString(R.string.category_unlabeled)
}

internal fun ModelManagerViewModel.preloadLastUsedLlmModelExt() {
  val lastUsedModelId = dataStoreRepository.getLastUsedLlmModelId()
  if (lastUsedModelId.isNullOrBlank()) {
    Log.d(TAG, "Skipping startup preload because no last used LLM model is stored")
    return
  }
  val model = getModelByName(lastUsedModelId)
  if (model == null) {
    Log.w(TAG, "Skipping startup preload because model '$lastUsedModelId' was not found")
    return
  }
  if (!model.isLlm) {
    Log.w(TAG, "Skipping startup preload because model '$lastUsedModelId' is not an LLM")
    return
  }
  val llmChatTask = getTaskById(BuiltInTaskId.LLM_CHAT)
  if (llmChatTask == null) {
    Log.w(TAG, "Skipping startup preload because LLM chat task is unavailable")
    return
  }
  val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
  if (downloadStatus != ModelDownloadStatusType.SUCCEEDED) {
    Log.d(
      TAG,
      "Skipping startup preload for '${model.name}' because download status is $downloadStatus",
    )
    return
  }
  val initStatus = uiState.value.modelInitializationStatus[model.name]?.status
  if (
    initStatus == ModelInitializationStatusType.INITIALIZED ||
      initStatus == ModelInitializationStatusType.INITIALIZING ||
      model.initializing
  ) {
    Log.d(TAG, "Skipping startup preload for '${model.name}' because it is already warm")
    return
  }
  Log.d(TAG, "Preloading last used LLM model during startup animation: ${model.name}")
  selectModel(model)
  initializeModel(context = context, task = llmChatTask, model = model)
}

internal fun ModelManagerViewModel.getModelDownloadStatusExt(model: Model): ModelDownloadStatus {
  Log.d(TAG, "Checking model ${model.name} download status...")
  if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
    Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
    return ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = 0,
      totalBytes = 0,
    )
  }
  var status = ModelDownloadStatusType.NOT_DOWNLOADED
  var receivedBytes = 0L
  var totalBytes = 0L
  if (isModelPartiallyDownloaded(model = model)) {
    status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    val tmpFile = File(tmpFilePath)
    receivedBytes = tmpFile.length()
    totalBytes = model.totalBytes
    Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
  } else if (isModelDownloaded(model = model)) {
    status = ModelDownloadStatusType.SUCCEEDED
    Log.d(TAG, "${model.name} has been downloaded.")
  } else {
    Log.d(TAG, "${model.name} has not been downloaded.")
  }
  return ModelDownloadStatus(status = status, receivedBytes = receivedBytes, totalBytes = totalBytes)
}

internal fun ModelManagerViewModel.updateModelInitializationStatusExt(
  model: Model,
  status: ModelInitializationStatusType,
  error: String = "",
) {
  val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
  val initializedBackends = curModelInstance[model.name]?.initializedBackends ?: setOf()
  val backend =
    model.getStringConfigValue(
      key = selfgemma.talk.data.ConfigKeys.ACCELERATOR,
      defaultValue = Accelerator.GPU.label,
    )
  val newInitializedBackends =
    if (status == ModelInitializationStatusType.INITIALIZED) initializedBackends + backend
    else initializedBackends
  curModelInstance[model.name] =
    ModelInitializationStatus(
      status = status,
      error = error,
      initializedBackends = newInitializedBackends,
    )
  val newUiState = uiState.value.copy(modelInitializationStatus = curModelInstance)
  _uiState.update { newUiState }
}
