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

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.chat.ChatMessageError
import selfgemma.talk.ui.common.chat.ChatMessageLoading
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatMessageWarning
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val LLMCHAT_OPS_TAG = "AGLlmChatViewModel"
private const val LLMCHAT_OPS_MAX_RESET_SESSION_RETRIES = 3

internal fun LlmChatViewModelBase.stopResponse(model: Model) {
  Log.d(LLMCHAT_OPS_TAG, "Stopping response for model ${model.name}...")
  if (getLastMessage(model = model) is ChatMessageLoading) {
    removeLastMessage(model = model)
  }
  setInProgress(false)
  model.runtimeHelper.stopResponse(model)
  Log.d(LLMCHAT_OPS_TAG, "Done stopping response")
}

@OptIn(ExperimentalApi::class)
internal fun LlmChatViewModelBase.resetSession(
  task: Task,
  model: Model,
  systemInstruction: Contents? = null,
  tools: List<ToolProvider> = listOf(),
  supportImage: Boolean = false,
  supportAudio: Boolean = false,
  onDone: () -> Unit = {},
  enableConversationConstrainedDecoding: Boolean = false,
) {
  viewModelScope.launch(Dispatchers.Default) {
    setIsResettingSession(true)
    clearAllMessages(model = model)
    stopResponse(model = model)

    var retries = 0
    var failureMessage: String? = null
    while (retries < LLMCHAT_OPS_MAX_RESET_SESSION_RETRIES) {
      try {
        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = supportImage,
          supportAudio = supportAudio,
          systemInstruction = systemInstruction,
          tools = tools,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        )
        setIsResettingSession(false)
        onDone()
        return@launch
      } catch (exception: Exception) {
        Log.d(LLMCHAT_OPS_TAG, "Failed to reset session. Trying again", exception)
        failureMessage = exception.message ?: "Failed to reset the session."
        if (LlmChatOverflowRecovery.isContextOverflow(exception.message)) {
          break
        }
      }
      retries += 1
      if (retries < LLMCHAT_OPS_MAX_RESET_SESSION_RETRIES) {
        delay(200)
      }
    }
    Log.e(
      LLMCHAT_OPS_TAG,
      "Failed to reset session after retries model=${model.name} retries=$retries message=$failureMessage",
    )
    setIsResettingSession(false)
    addMessage(
      model = model,
      message =
        ChatMessageError(
          content = LlmChatOverflowRecovery.toUserMessage(failureMessage),
        ),
    )
  }
}

internal fun LlmChatViewModelBase.runAgain(
  model: Model,
  message: ChatMessageText,
  currentSystemPrompt: String = "",
  onError: (String) -> Unit,
  allowThinking: Boolean = false,
  supportImage: Boolean = false,
  supportAudio: Boolean = false,
) {
  viewModelScope.launch(Dispatchers.Default) {
    while (model.instance == null) {
      delay(100)
    }

    val clonedMessage = message.clone()
    addMessage(model = model, message = clonedMessage)

    generateResponse(
      model = model,
      input = message.content,
      currentTurnMessages = listOf(clonedMessage),
      currentSystemPrompt = currentSystemPrompt,
      onError = onError,
      allowThinking = allowThinking,
      supportImage = supportImage,
      supportAudio = supportAudio,
    )
  }
}

internal fun LlmChatViewModelBase.handleError(
  context: Context,
  task: Task,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  errorMessage: String,
) {
  if (getLastMessage(model = model) is ChatMessageLoading) {
    removeLastMessage(model = model)
  }

  addMessage(
    model = model,
    message = ChatMessageError(content = LlmChatOverflowRecovery.toUserMessage(errorMessage)),
  )

  if (LlmChatOverflowRecovery.isContextOverflow(errorMessage)) {
    return
  }

  viewModelScope.launch(Dispatchers.Default) {
    modelManagerViewModel.cleanupModel(
      context = context,
      task = task,
      model = model,
      onDone = {
        modelManagerViewModel.initializeModel(context = context, task = task, model = model)
        addMessage(
          model = model,
          message = ChatMessageWarning(content = "Session re-initialized"),
        )
      },
    )
  }
}
