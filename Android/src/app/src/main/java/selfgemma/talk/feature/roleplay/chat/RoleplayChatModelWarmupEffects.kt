package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import selfgemma.talk.data.Model
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "RoleplayChatWarmup"

@Composable
internal fun RoleplayChatModelWarmupEffects(
  context: Context,
  sessionId: String?,
  activeModel: Model?,
  activeModelDownloadStatus: ModelDownloadStatusType?,
  activeModelStatus: ModelInitializationStatusType?,
  activeModelHasInstance: Boolean,
  activeModelSupportsImage: Boolean,
  activeModelSupportsAudio: Boolean,
  isActiveModelInitializing: Boolean,
  needsImage: Boolean,
  needsAudio: Boolean,
  llmChatTask: Task?,
  modelManagerViewModel: ModelManagerViewModel,
) {
  LaunchedEffect(activeModel?.name) {
    if (activeModel != null) {
      Log.d(TAG, "sync active chat model to recent selection model=${activeModel.name}")
      modelManagerViewModel.selectModel(activeModel)
    }
  }

  LaunchedEffect(
    activeModel?.name,
    activeModelDownloadStatus,
    activeModelStatus,
    activeModelSupportsImage,
    activeModelSupportsAudio,
    needsImage,
    needsAudio,
  ) {
    val currentModel = activeModel ?: return@LaunchedEffect
    val warmupAction =
      resolveRoleplayWarmupAction(
        downloadStatus = activeModelDownloadStatus,
        isInitializing = isActiveModelInitializing,
        hasInstance = activeModelHasInstance,
        supportImage = activeModelSupportsImage,
        supportAudio = activeModelSupportsAudio,
        needsImage = needsImage,
        needsAudio = needsAudio,
      )
    when (warmupAction) {
      RoleplayWarmupAction.NONE -> Unit
      RoleplayWarmupAction.TEXT_ONLY -> {
        val task = llmChatTask ?: return@LaunchedEffect
        Log.d(
          TAG,
          "warm roleplay active model on screen entry sessionId=$sessionId model=${currentModel.name} mode=text-only",
        )
        modelManagerViewModel.initializeModel(
          context = context,
          task = task,
          model = currentModel,
          force = activeModelStatus == ModelInitializationStatusType.INITIALIZED,
        )
      }
      RoleplayWarmupAction.MULTIMODAL -> {
        Log.d(
          TAG,
          "warm roleplay active model on screen entry sessionId=$sessionId model=${currentModel.name} mode=multimodal needsImage=$needsImage needsAudio=$needsAudio",
        )
        modelManagerViewModel.initializeLlmModel(
          context = context,
          model = currentModel,
          supportImage = needsImage,
          supportAudio = needsAudio,
          force =
            activeModelHasInstance ||
              activeModelStatus == ModelInitializationStatusType.INITIALIZED,
        )
      }
    }
  }
}
