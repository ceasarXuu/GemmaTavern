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

package selfgemma.talk.ui.llmchat

import android.util.Log
import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.Model
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.File

private const val HELPERS_TAG = "AGLlmChatModelHelper"

internal fun resolveImportedCpuWeightCacheFile(
  model: Model,
  accelerator: String,
  modelPath: String,
): File? {
  if (!model.imported || accelerator != Accelerator.CPU.label || modelPath.isBlank()) {
    return null
  }

  val cacheFile = File("${modelPath}.xnnpack_cache")
  return cacheFile.takeIf { it.exists() }
}

internal fun purgeImportedCpuWeightCacheIfPresent(
  model: Model,
  accelerator: String,
  modelPath: String,
) {
  val cacheFile =
    resolveImportedCpuWeightCacheFile(
      model = model,
      accelerator = accelerator,
      modelPath = modelPath,
    ) ?: return

  if (cacheFile.delete()) {
    Log.w(HELPERS_TAG, "Purged imported CPU weight cache before init: ${cacheFile.absolutePath}")
  } else {
    Log.w(HELPERS_TAG, "Failed to purge imported CPU weight cache before init: ${cacheFile.absolutePath}")
  }
}

internal fun buildConversationConfig(
  samplerConfig: SamplerConfig?,
  systemInstruction: Contents?,
  tools: List<ToolProvider>,
): ConversationConfig {
  return ConversationConfig(
    samplerConfig = samplerConfig,
    systemInstruction = systemInstruction,
    tools = tools,
  )
}

@OptIn(ExperimentalApi::class)
internal fun restoreConversationAfterResetFailure(
  engine: Engine,
  samplerConfig: SamplerConfig?,
  previousSessionConfig: LlmConversationSessionConfig,
): Conversation? {
  val fallbackConfigs =
    listOf(
      previousSessionConfig,
      LlmConversationSessionConfig(),
    ).distinct()

  for (fallbackConfig in fallbackConfigs) {
    try {
      val restoredConversation =
        createConversationWithConstrainedDecoding(
          engine = engine,
          enableConversationConstrainedDecoding =
            fallbackConfig.enableConversationConstrainedDecoding,
          config =
            buildConversationConfig(
              samplerConfig = samplerConfig,
              systemInstruction = fallbackConfig.toSystemInstructionContents(),
              tools = fallbackConfig.tools,
            ),
        )
      Log.w(
        HELPERS_TAG,
        "Restored fallback conversation after reset failure systemPromptChars=${fallbackConfig.systemInstructionText.length} tools=${fallbackConfig.tools.size} constrained=${fallbackConfig.enableConversationConstrainedDecoding}",
      )
      return restoredConversation
    } catch (restoreException: Exception) {
      Log.w(
        HELPERS_TAG,
        "Failed to restore fallback conversation systemPromptChars=${fallbackConfig.systemInstructionText.length} tools=${fallbackConfig.tools.size} constrained=${fallbackConfig.enableConversationConstrainedDecoding}",
        restoreException,
      )
    }
  }

  return null
}

@OptIn(ExperimentalApi::class)
internal fun createConversationWithConstrainedDecoding(
  engine: Engine,
  enableConversationConstrainedDecoding: Boolean,
  config: ConversationConfig,
): Conversation {
  return withConversationConstrainedDecoding(
    enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
  ) {
    engine.createConversation(config)
  }
}

internal fun LlmConversationSessionConfig.toSystemInstructionContents(): Contents? {
  if (systemInstructionText.isBlank()) {
    return null
  }
  return Contents.of(systemInstructionText)
}
