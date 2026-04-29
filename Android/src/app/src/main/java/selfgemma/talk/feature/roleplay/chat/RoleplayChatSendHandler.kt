package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.util.Log
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.llmchat.LlmModelInstance
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "RoleplayChatSendHandler"

internal fun handleRoleplaySend(
  context: Context,
  sessionId: String?,
  messages: List<ChatMessage>,
  conversationMessages: List<Message>,
  currentModel: Model,
  isActiveModelInitialized: Boolean,
  isActiveModelInitializing: Boolean,
  viewModel: RoleplayChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val sendRequirements =
    resolveRoleplaySendRequirements(
      messages = messages,
      conversationMessages = conversationMessages,
    )
  val submitMessages: () -> Unit = {
    sendRequirements.primaryTextInput?.let(modelManagerViewModel::addTextInputHistory)
    viewModel.sendChatMessages(
      model = currentModel,
      messages = messages,
      clearDraft = true,
    )
  }
  val initializedInstance = currentModel.instance as? LlmModelInstance
  val sendExecutionPlan =
    resolveRoleplaySendExecutionPlan(
      needsImage = sendRequirements.needsImage,
      needsAudio = sendRequirements.needsAudio,
      hasReusableMultimodalSession =
        canReuseRoleplayModelSession(
          instance = initializedInstance,
          needsImage = sendRequirements.needsImage,
          needsAudio = sendRequirements.needsAudio,
        ),
      hasInitializedSession =
        initializedInstance != null || isActiveModelInitialized || currentModel.initializing,
    )
  Log.d(
    TAG,
    "roleplay send requested sessionId=$sessionId model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio} hasInitializedInstance=${initializedInstance != null} isModelInitializing=$isActiveModelInitializing queueImmediately=${sendExecutionPlan.queueImmediately} warmupAction=${sendExecutionPlan.warmupAction}",
  )

  if (sendExecutionPlan.queueImmediately) {
    submitMessages()
    when (sendExecutionPlan.warmupAction) {
      RoleplayWarmupAction.NONE -> {
        Log.d(
          TAG,
          "queue roleplay send immediately sessionId=$sessionId model=${currentModel.name} using current session",
        )
      }
      RoleplayWarmupAction.MULTIMODAL -> {
        Log.d(
          TAG,
          "queue roleplay send immediately and reinitialize multimodal session sessionId=$sessionId model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio}",
        )
        modelManagerViewModel.initializeLlmModel(
          context = context,
          model = currentModel,
          supportImage = sendRequirements.needsImage,
          supportAudio = sendRequirements.needsAudio,
          force = true,
        )
      }
      RoleplayWarmupAction.TEXT_ONLY -> {
        Log.d(
          TAG,
          "queue roleplay send immediately sessionId=$sessionId model=${currentModel.name} while text session is already warming",
        )
      }
    }
  } else {
    when (sendExecutionPlan.warmupAction) {
      RoleplayWarmupAction.NONE -> {
        Log.d(
          TAG,
          "dispatch roleplay text send with existing or warming session sessionId=$sessionId model=${currentModel.name}",
        )
        submitMessages()
      }
      RoleplayWarmupAction.TEXT_ONLY -> {
        val task = modelManagerViewModel.getTaskById(selfgemma.talk.data.BuiltInTaskId.LLM_CHAT)
          ?: return
        Log.d(
          TAG,
          "initialize text roleplay session before send sessionId=$sessionId model=${currentModel.name}",
        )
        modelManagerViewModel.initializeModel(
          context = context,
          task = task,
          model = currentModel,
          onDone = submitMessages,
        )
      }
      RoleplayWarmupAction.MULTIMODAL -> {
        Log.d(
          TAG,
          "initialize multimodal roleplay session before send sessionId=$sessionId model=${currentModel.name} needsImage=${sendRequirements.needsImage} needsAudio=${sendRequirements.needsAudio}",
        )
        modelManagerViewModel.initializeLlmModel(
          context = context,
          model = currentModel,
          supportImage = sendRequirements.needsImage,
          supportAudio = sendRequirements.needsAudio,
          force = true,
          onDone = submitMessages,
        )
      }
    }
  }
}
