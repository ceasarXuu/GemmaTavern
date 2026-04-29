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
import com.google.ai.edge.litertlm.ExperimentalApi
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.chat.ChatMessage

internal data class LlmChatPreparationResult(
  val errorMessage: String? = null,
  val overflowDetected: Boolean = false,
)

private const val LLMCHAT_CONTEXT_TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
internal fun LlmChatViewModelBase.buildContextPlan(
  model: Model,
  currentTurnMessages: List<ChatMessage>,
  currentSystemPrompt: String,
  currentInput: String,
  imageCount: Int,
  audioCount: Int,
  contextProfile: ModelContextProfile,
  preferredMode: LlmChatContextMode,
): LlmChatContextPlan {
  val allMessages = getMessages(model = model)
  val priorMessages = allMessages.take((allMessages.size - currentTurnMessages.size).coerceAtLeast(0))
  return contextManager.buildPlan(
    baseSystemPrompt = resolveBaseSystemPrompt(model = model, currentSystemPrompt = currentSystemPrompt),
    historyMessages = priorMessages,
    pendingInput = currentInput,
    pendingImageCount = imageCount,
    pendingAudioCount = audioCount,
    contextProfile = contextProfile,
    preferredMode = preferredMode,
  )
}

@OptIn(ExperimentalApi::class)
internal fun LlmChatViewModelBase.prepareConversationForAttempt(
  model: Model,
  plan: LlmChatContextPlan,
  supportImage: Boolean,
  supportAudio: Boolean,
): LlmChatPreparationResult {
  val persistentSessionConfig =
    (model.instance as? LlmModelInstance)?.sessionConfig ?: LlmConversationSessionConfig()
  return try {
    model.runtimeHelper.resetConversation(
      model = model,
      supportImage = supportImage,
      supportAudio = supportAudio,
      systemInstruction = plan.systemInstruction,
      tools = persistentSessionConfig.tools,
      enableConversationConstrainedDecoding =
        persistentSessionConfig.enableConversationConstrainedDecoding,
    )
    (model.instance as? LlmModelInstance)?.sessionConfig = persistentSessionConfig
    Log.d(
      LLMCHAT_CONTEXT_TAG,
      "Prepared llmchat conversation model=${model.name} mode=${plan.report.mode} estimatedInstructionTokens=${plan.report.estimatedInstructionTokens} availableInstructionTokens=${plan.report.availableInstructionTokens} recentLines=${plan.report.recentLineCount} summaryLines=${plan.report.summaryLineCount} droppedLines=${plan.report.droppedLineCount} tools=${persistentSessionConfig.tools.size} constrained=${persistentSessionConfig.enableConversationConstrainedDecoding}",
    )
    LlmChatPreparationResult()
  } catch (exception: Exception) {
    Log.w(
      LLMCHAT_CONTEXT_TAG,
      "Failed to prepare llmchat conversation model=${model.name} mode=${plan.report.mode}",
      exception,
    )
    LlmChatPreparationResult(
      errorMessage = exception.message ?: "",
      overflowDetected = LlmChatOverflowRecovery.isContextOverflow(exception.message),
    )
  }
}

internal fun LlmChatViewModelBase.resolveBaseSystemPrompt(model: Model, currentSystemPrompt: String): String {
  val configuredSystemPrompt =
    (model.instance as? LlmModelInstance)?.sessionConfig?.systemInstructionText.orEmpty()
  return configuredSystemPrompt.ifBlank { currentSystemPrompt }
}
