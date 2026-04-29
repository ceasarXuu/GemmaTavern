/*
 * Copyright 2026 Google LLC
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

package selfgemma.talk.customtasks.agentchat

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import selfgemma.talk.common.SkillProgressAgentAction
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.BaseAppWebViewClient
import selfgemma.talk.ui.common.chat.ChatMessageCollapsableProgressPanel
import selfgemma.talk.ui.common.chat.ChatMessageType
import selfgemma.talk.ui.llmchat.LlmChatViewModel
import selfgemma.talk.ui.llmchat.resetSession
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.tool

private const val INTERNALS_TAG = "AGAgentChatScreen"

internal fun updateProgressPanel(
  viewModel: LlmChatViewModel,
  model: Model,
  agentTools: AgentTools,
) {
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
      lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
  ) {
    if (lastProgressPanelMessage.title.startsWith("Loading")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Loading", "Loaded"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Calling")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Calling", "Called"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Executing")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Executing", "Executed"),
          inProgress = false,
        )
      )
    }
  }
}

internal fun resetSessionWithCurrentSkills(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  onDone: (Model) -> Unit = {},
) {
  val model = modelManagerViewModel.uiState.value.selectedModel
  val newSelectedSkills = skillManagerViewModel.getSelectedSkills()
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction =
      if (newSelectedSkills.isEmpty()) null
      else skillManagerViewModel.getSystemPrompt(curSystemPrompt),
    tools = listOf(tool(agentTools)),
    supportImage = true,
    supportAudio = true,
    onDone = { onDone(model) },
    enableConversationConstrainedDecoding = true,
  )
}

internal class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    onResultListener?.invoke(result)
  }
}

internal class ChatWebViewClient(val context: Context) : BaseAppWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(INTERNALS_TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}
