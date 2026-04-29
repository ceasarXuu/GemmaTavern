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

import android.util.Log
import selfgemma.talk.common.isPixel10
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.BooleanSwitchConfig
import selfgemma.talk.data.Config
import selfgemma.talk.data.ConfigKey
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DEFAULT_MAX_TOKEN
import selfgemma.talk.data.DEFAULT_TEMPERATURE
import selfgemma.talk.data.DEFAULT_TOPK
import selfgemma.talk.data.DEFAULT_TOPP
import selfgemma.talk.data.LabelConfig
import selfgemma.talk.data.NumberSliderConfig
import selfgemma.talk.data.SegmentedButtonConfig
import selfgemma.talk.data.ValueType
import selfgemma.talk.data.convertValueToTargetType

internal const val MODEL_IMPORT_DIALOG_TAG = "AGModelImportDialog"

internal val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU)
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

internal inline fun <reified T> readImportValue(
  values: Map<String, Any>,
  key: ConfigKey,
  valueType: ValueType,
): T {
  val raw = values[key.label]
  val source =
    if (raw != null) {
      raw
    } else {
      val fallback =
        IMPORT_CONFIGS_LLM.firstOrNull { it.key == key }?.defaultValue
          ?: error("Missing import config value for key=${key.label} and no default registered")
      Log.w(MODEL_IMPORT_DIALOG_TAG, "Import dialog values map missing key=${key.label}; falling back to default")
      fallback
    }
  val converted = convertValueToTargetType(value = source, valueType = valueType)
  return converted as? T
    ?: error(
      "Import config value for key=${key.label} could not be coerced to ${T::class.simpleName}"
    )
}

internal val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 5f,
      sliderMax = 40f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_IMAGE, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_AUDIO, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_TINY_GARDEN, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_MOBILE_ACTIONS, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )
