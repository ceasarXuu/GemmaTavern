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

import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DEFAULT_MAX_TOKEN
import selfgemma.talk.data.DEFAULT_TEMPERATURE
import selfgemma.talk.data.DEFAULT_TOPK
import selfgemma.talk.data.DEFAULT_TOPP
import selfgemma.talk.data.NumberSliderConfig
import selfgemma.talk.data.ValueType
import selfgemma.talk.proto.ImportedModel

/**
 * Internal helpers and lookup constants extracted from [ModelManagerViewModel] to keep the
 * view-model file focused on lifecycle and state-flow orchestration. No behavior change.
 */

internal const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
internal const val MODEL_ALLOWLIST_FILENAME = "selfgemma_talk_model_allowlist.json"
internal const val MODEL_ALLOWLIST_TEST_FILENAME = "selfgemma_talk_model_allowlist_test.json"
internal const val IMPORTED_MODEL_MAX_CONTEXT_LENGTH = 4096
internal const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/ceasarXuu/GemmaTavern/refs/heads/main/model_allowlists"

internal const val TEST_MODEL_ALLOW_LIST = ""

internal val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

internal val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

internal fun runInitializationAfterOptionalCleanup(
  hasExistingInstance: Boolean,
  startCleanup: (onDone: () -> Unit) -> Unit,
  startInitialization: () -> Unit,
) {
  if (hasExistingInstance) {
    startCleanup(startInitialization)
  } else {
    startInitialization()
  }
}

internal fun updatedImportedModelWithConfigValues(
  importedModel: ImportedModel,
  values: Map<String, Any>,
): ImportedModel {
  val llmConfig = importedModel.llmConfig
  val selectedAccelerator = values.stringValue(ConfigKeys.ACCELERATOR.label)
  val compatibleAccelerators =
    reorderCompatibleAccelerators(
      compatibleAccelerators = llmConfig.compatibleAcceleratorsList,
      selectedAccelerator = selectedAccelerator,
    )

  return importedModel
    .toBuilder()
    .setLlmConfig(
      llmConfig
        .toBuilder()
        .clearCompatibleAccelerators()
        .addAllCompatibleAccelerators(compatibleAccelerators)
        .setDefaultMaxTokens(
          values.intValue(
            key = ConfigKeys.MAX_TOKENS.label,
            defaultValue = llmConfig.defaultMaxTokens.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN,
          )
        )
        .setDefaultTopk(
          values.intValue(
            key = ConfigKeys.TOPK.label,
            defaultValue = llmConfig.defaultTopk.takeIf { it > 0 } ?: DEFAULT_TOPK,
          )
        )
        .setDefaultTopp(
          values.floatValue(
            key = ConfigKeys.TOPP.label,
            defaultValue = llmConfig.defaultTopp.takeIf { it > 0f } ?: DEFAULT_TOPP,
          )
        )
        .setDefaultTemperature(
          values.floatValue(
            key = ConfigKeys.TEMPERATURE.label,
            defaultValue =
              llmConfig.defaultTemperature.takeIf { it > 0f } ?: DEFAULT_TEMPERATURE,
          )
        )
        .setDefaultEnableThinking(
          values.booleanValue(
            key = ConfigKeys.ENABLE_THINKING.label,
            defaultValue = llmConfig.defaultEnableThinking,
          )
        )
        .build()
    )
    .build()
}

private fun reorderCompatibleAccelerators(
  compatibleAccelerators: List<String>,
  selectedAccelerator: String?,
): List<String> {
  if (selectedAccelerator.isNullOrBlank()) {
    return compatibleAccelerators
  }
  val normalizedSelection = selectedAccelerator.trim()
  val existing = compatibleAccelerators.map { it.trim() }.filter { it.isNotEmpty() }
  if (existing.isEmpty()) {
    return listOf(normalizedSelection)
  }
  return buildList {
    add(normalizedSelection)
    addAll(existing.filterNot { it == normalizedSelection })
  }
}

private fun Map<String, Any>.intValue(key: String, defaultValue: Int): Int {
  return when (val value = get(key)) {
    is Int -> value
    is Float -> value.toInt()
    is Double -> value.toInt()
    is String -> value.toIntOrNull() ?: defaultValue
    else -> defaultValue
  }
}

private fun Map<String, Any>.floatValue(key: String, defaultValue: Float): Float {
  return when (val value = get(key)) {
    is Int -> value.toFloat()
    is Float -> value
    is Double -> value.toFloat()
    is String -> value.toFloatOrNull() ?: defaultValue
    else -> defaultValue
  }
}

private fun Map<String, Any>.stringValue(key: String): String? {
  return get(key)?.toString()?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any>.booleanValue(key: String, defaultValue: Boolean): Boolean {
  return when (val value = get(key)) {
    is Boolean -> value
    is Int -> value != 0
    is Float -> value != 0f
    is Double -> value != 0.0
    is String -> value.equals("true", ignoreCase = true)
    else -> defaultValue
  }
}
