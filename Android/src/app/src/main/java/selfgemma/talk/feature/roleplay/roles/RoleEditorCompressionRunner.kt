package selfgemma.talk.feature.roleplay.roles

import android.content.Context
import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.R
import selfgemma.talk.data.Model

private const val TAG = "RoleEditorCompression"

internal fun cancelAllRoleEditorFieldCompressions(
  activeCompressions: SnapshotStateMap<String, ActiveRoleEditorCompression>,
) {
  val sessions = activeCompressions.values.toList()
  sessions.forEach { session ->
    if (!session.completed) {
      Log.i(TAG, "Cancelling role editor compression field=${session.fieldKey} and restoring original content")
      session.job.cancel()
      session.restoreValue(session.originalValue)
    }
  }
  activeCompressions.clear()
}

internal fun launchRoleEditorFieldCompression(
  context: Context,
  viewModel: RoleEditorViewModel,
  compressionScope: CoroutineScope,
  activeCompressions: SnapshotStateMap<String, ActiveRoleEditorCompression>,
  resolvedModel: Model?,
  fieldKey: String,
  fieldTitle: String,
  maxChars: Int,
  currentValue: String,
  onValueChange: (String) -> Unit,
) {
  if (fieldKey in activeCompressions || activeCompressions.isNotEmpty()) {
    return
  }
  if (resolvedModel == null) {
    viewModel.showErrorMessage(context.getString(R.string.role_editor_ai_compress_missing_model))
    Log.w(TAG, "Role editor AI compression requested without any local model")
    return
  }

  val job =
    compressionScope.launch(Dispatchers.Default) {
      runRoleEditorCompressionJob(
        context = context,
        viewModel = viewModel,
        compressionScope = compressionScope,
        activeCompressions = activeCompressions,
        resolvedModel = resolvedModel,
        fieldKey = fieldKey,
        fieldTitle = fieldTitle,
        maxChars = maxChars,
        currentValue = currentValue,
        onValueChange = onValueChange,
      )
    }

  activeCompressions[fieldKey] =
    ActiveRoleEditorCompression(
      fieldKey = fieldKey,
      originalValue = currentValue,
      restoreValue = onValueChange,
      job = job,
    )
}

private suspend fun runRoleEditorCompressionJob(
  context: Context,
  viewModel: RoleEditorViewModel,
  compressionScope: CoroutineScope,
  activeCompressions: SnapshotStateMap<String, ActiveRoleEditorCompression>,
  resolvedModel: Model,
  fieldKey: String,
  fieldTitle: String,
  maxChars: Int,
  currentValue: String,
  onValueChange: (String) -> Unit,
) {
  try {
    Log.d(
      TAG,
      "Starting role editor AI compression field=$fieldKey model=${resolvedModel.name} sourceLength=${currentValue.length} targetLength=$maxChars",
    )
    ensureRoleEditorCompressionModelReady(
      context = context,
      model = resolvedModel,
      coroutineScope = compressionScope,
    )
    val compressionResult =
      compressRoleEditorFieldToTarget(
        model = resolvedModel,
        fieldTitle = fieldTitle,
        maxChars = maxChars,
        originalContent = currentValue,
        coroutineScope = compressionScope,
      )
    val cleanedResult = compressionResult.text.trim()
    when {
      cleanedResult.isBlank() -> {
        withContext(Dispatchers.Main) {
          viewModel.showErrorMessage(context.getString(R.string.role_editor_ai_compress_failed_blank))
        }
        Log.w(TAG, "Role editor AI compression returned blank result field=$fieldKey")
      }
      cleanedResult.length > maxChars -> {
        withContext(Dispatchers.Main) {
          viewModel.showErrorMessage(
            context.getString(
              R.string.role_editor_ai_compress_failed_limit,
              cleanedResult.length,
              maxChars,
            ),
          )
        }
        Log.w(
          TAG,
          "Role editor AI compression exceeded target after ${compressionResult.attempts} attempts field=$fieldKey resultLength=${cleanedResult.length} targetLength=$maxChars",
        )
      }
      else -> {
        withContext(Dispatchers.Main) {
          onValueChange(cleanedResult)
          viewModel.showStatusMessage(
            context.getString(
              R.string.role_editor_ai_compress_success,
              cleanedResult.length,
              maxChars,
            ),
          )
        }
        Log.i(
          TAG,
          "Role editor AI compression completed field=$fieldKey resultLength=${cleanedResult.length} targetLength=$maxChars",
        )
      }
    }
  } catch (_: kotlinx.coroutines.CancellationException) {
    Log.i(TAG, "Role editor AI compression cancelled field=$fieldKey")
  } catch (error: Exception) {
    withContext(Dispatchers.Main) {
      viewModel.showErrorMessage(
        error.message ?: context.getString(R.string.role_editor_ai_compress_failed_generic),
      )
    }
    Log.e(TAG, "Role editor AI compression failed field=$fieldKey", error)
  } finally {
    withContext(Dispatchers.Main) {
      activeCompressions[fieldKey]?.completed = true
      activeCompressions.remove(fieldKey)
    }
  }
}
