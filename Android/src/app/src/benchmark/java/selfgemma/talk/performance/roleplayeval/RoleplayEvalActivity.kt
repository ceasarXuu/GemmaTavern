package selfgemma.talk.performance.roleplayeval

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.Model
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManagerUiState
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "RoleplayEvalActivity"
private const val MODEL_CATALOG_TIMEOUT_MS = 20_000L
private const val MODEL_CATALOG_FALLBACK_TIMEOUT_MS = 5_000L
private const val MODEL_INIT_TIMEOUT_MS = 90_000L
private const val POLL_INTERVAL_MS = 250L

@AndroidEntryPoint
class RoleplayEvalActivity : ComponentActivity() {
  @Inject lateinit var runner: RoleplayEvalRunner
  @Inject lateinit var dataStoreRepository: DataStoreRepository

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val manifestPath = intent.getStringExtra(ROLEPLAY_EVAL_MANIFEST_PATH_EXTRA).orEmpty().trim()
    val runId =
      intent.getStringExtra(ROLEPLAY_EVAL_RUN_ID_EXTRA)
        .orEmpty()
        .trim()
        .ifBlank { "run-${System.currentTimeMillis()}" }

    lifecycleScope.launch {
      val startedAt = System.currentTimeMillis()
      val runDir = withContext(Dispatchers.IO) { RoleplayEvalStorage.runDir(this@RoleplayEvalActivity, runId) }
      var suiteId = ""
      var totalCases = 0
      var completedCases = 0
      var currentCaseId: String? = null
      var resolvedModelName: String? = null
      var currentPhase = "startup"

      if (manifestPath.isBlank()) {
        val errorMessage = "Missing manifest path extra."
        withContext(Dispatchers.IO) {
          writeStatus(
            runDir = runDir,
            state = "FAILED",
            phase = currentPhase,
            runId = runId,
            suiteId = "",
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
            errorMessage = errorMessage,
          )
          writeRunError(
            runDir = runDir,
            runId = runId,
            suiteId = "",
            phase = currentPhase,
            throwable = IllegalArgumentException(errorMessage),
          )
        }
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", errorMessage))
        finishAndRemoveTask()
        return@launch
      }

      try {
        currentPhase = "loading_manifest"
        withContext(Dispatchers.IO) {
          writeStatus(
            runDir = runDir,
            state = "RUNNING",
            phase = currentPhase,
            runId = runId,
            suiteId = "",
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
          )
        }

        val manifest = withContext(Dispatchers.IO) { RoleplayEvalStorage.readManifest(manifestPath) }
        suiteId = manifest.suiteId
        totalCases = manifest.cases.size
        currentPhase = "resolving_model"
        withContext(Dispatchers.IO) {
          RoleplayEvalStorage.copyManifest(manifestPath = manifestPath, runDir = runDir)
          writeStatus(
            runDir = runDir,
            state = "RUNNING",
            phase = currentPhase,
            runId = runId,
            suiteId = suiteId,
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
          )
        }

        val modelBinding = resolveModel(manifest = manifest)
        resolvedModelName = modelBinding.resolvedModel.resolvedModelName
        currentPhase = "running_cases"

        withContext(Dispatchers.IO) {
          writeStatus(
            runDir = runDir,
            state = "RUNNING",
            phase = currentPhase,
            runId = runId,
            suiteId = suiteId,
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
            resolvedModelName = resolvedModelName,
          )
        }

        val summary =
          runner.run(
            context = this@RoleplayEvalActivity,
            manifest = manifest,
            manifestPath = manifestPath,
            runId = runId,
            modelBinding = modelBinding,
            onCaseProgress = { completedCount, totalCount, activeCaseId ->
              completedCases = completedCount
              totalCases = totalCount
              currentCaseId = activeCaseId
              writeStatus(
                runDir = runDir,
                state = "RUNNING",
                phase = currentPhase,
                runId = runId,
                suiteId = suiteId,
                manifestPath = manifestPath,
                startedAtEpochMs = startedAt,
                completedCases = completedCount,
                totalCases = totalCount,
                currentCaseId = activeCaseId,
                resolvedModelName = resolvedModelName,
              )
            },
          )

        completedCases = summary.caseResults.size
        totalCases = manifest.cases.size
        currentCaseId = null
        currentPhase = "completed"
        withContext(Dispatchers.IO) {
          RoleplayEvalStorage.writeJson(RoleplayEvalStorage.summaryFile(runDir), summary)
          writeStatus(
            runDir = runDir,
            state = "COMPLETED",
            phase = currentPhase,
            runId = runId,
            suiteId = suiteId,
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
            resolvedModelName = summary.resolvedModel.resolvedModelName,
          )
        }

        setResult(
          Activity.RESULT_OK,
          Intent()
            .putExtra("runId", runId)
            .putExtra("runDir", runDir.absolutePath)
            .putExtra("suiteId", manifest.suiteId),
        )
      } catch (throwable: Throwable) {
        Log.e(TAG, "roleplay eval failed runId=$runId manifestPath=$manifestPath", throwable)
        if (suiteId.isBlank()) {
          suiteId =
            runCatching { withContext(Dispatchers.IO) { RoleplayEvalStorage.readManifest(manifestPath) } }
              .getOrNull()
              ?.suiteId
              .orEmpty()
        }
        val errorMessage = throwable.message ?: throwable.javaClass.simpleName
        currentPhase = "failed"
        withContext(Dispatchers.IO) {
          writeStatus(
            runDir = runDir,
            state = "FAILED",
            phase = currentPhase,
            runId = runId,
            suiteId = suiteId,
            manifestPath = manifestPath,
            startedAtEpochMs = startedAt,
            completedCases = completedCases,
            totalCases = totalCases,
            currentCaseId = currentCaseId,
            resolvedModelName = resolvedModelName,
            errorMessage = errorMessage,
          )
          writeRunError(
            runDir = runDir,
            runId = runId,
            suiteId = suiteId,
            phase = currentPhase,
            throwable = throwable,
          )
        }
        setResult(
          Activity.RESULT_CANCELED,
          Intent().putExtra("runId", runId).putExtra("error", errorMessage),
        )
      } finally {
        finishAndRemoveTask()
      }
    }
  }

  private suspend fun resolveModel(manifest: RoleplayEvalManifest): RoleplayEvalModelBinding {
    modelManagerViewModel.loadModelAllowlist()
    var uiState = awaitModelCatalogState(timeoutMs = MODEL_CATALOG_TIMEOUT_MS)
    if (uiState.loadingModelAllowlist || uiState.loadingModelAllowlistError.isNotBlank() || uiState.tasks.isEmpty()) {
      Log.w(
        TAG,
        "model allowlist unavailable, falling back to imported-model catalog error=${uiState.loadingModelAllowlistError}",
      )
      modelManagerViewModel.clearLoadModelAllowlistError()
      uiState = awaitModelCatalogState(timeoutMs = MODEL_CATALOG_FALLBACK_TIMEOUT_MS)
    }

    if (uiState.tasks.isEmpty()) {
      error(uiState.loadingModelAllowlistError.ifBlank { "No model tasks are available for roleplay evaluation." })
    }

    val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
    if (downloadedModels.isEmpty()) {
      error("No downloaded LLM models are available on device for roleplay evaluation.")
    }

    val requestedModelName = manifest.modelName.trim()
    val lastUsedModelName = dataStoreRepository.getLastUsedLlmModelId().orEmpty().trim()
    val selection =
      when {
        requestedModelName.isNotBlank() -> {
          val requestedDownloaded = downloadedModels.firstOrNull { it.name == requestedModelName }
          if (requestedDownloaded != null) {
            requestedDownloaded to "manifest"
          } else {
            val catalogModel = modelManagerViewModel.getModelByName(requestedModelName)
            if (catalogModel != null) {
              error("Requested model '$requestedModelName' exists but is not downloaded on this device.")
            }
            error("Requested model '$requestedModelName' was not found in the device model catalog.")
          }
        }
        lastUsedModelName.isNotBlank() -> {
          val lastUsedModel = downloadedModels.firstOrNull { it.name == lastUsedModelName }
          if (lastUsedModel != null) {
            lastUsedModel to "last_used"
          } else {
            downloadedModels.first() to "first_downloaded"
          }
        }
        else -> downloadedModels.first() to "first_downloaded"
      }

    val selectedModel = selection.first
    val llmTask =
      modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
        ?: error("LLM chat task is unavailable, cannot initialize roleplay evaluation model.")
    val wasAlreadyInitialized = selectedModel.instance != null
    modelManagerViewModel.selectModel(selectedModel)
    if (!wasAlreadyInitialized) {
      modelManagerViewModel.initializeModel(context = this, task = llmTask, model = selectedModel)
      awaitModelInitialization(selectedModel)
    }

    return RoleplayEvalModelBinding(
      model = selectedModel,
      resolvedModel =
        RoleplayEvalResolvedModel(
          requestedModelName = requestedModelName,
          resolvedModelName = selectedModel.name,
          selectionSource = selection.second,
          runtimeType = selectedModel.runtimeType.name,
          modelPath = selectedModel.getPath(this),
          accelerator =
            selectedModel.getStringConfigValue(
              key = ConfigKeys.ACCELERATOR,
              defaultValue = selectedModel.accelerators.firstOrNull()?.label.orEmpty(),
            ),
          llmMaxToken = selectedModel.llmMaxToken,
          supportImage = selectedModel.llmSupportImage,
          supportAudio = selectedModel.llmSupportAudio,
          supportThinking = selectedModel.llmSupportThinking,
          wasAlreadyInitialized = wasAlreadyInitialized,
        ),
    )
  }

  private suspend fun awaitModelCatalogState(timeoutMs: Long): ModelManagerUiState {
    val startedAt = System.currentTimeMillis()
    var lastState = modelManagerViewModel.uiState.value
    while (System.currentTimeMillis() - startedAt < timeoutMs) {
      lastState = modelManagerViewModel.uiState.value
      if (!lastState.loadingModelAllowlist) {
        return lastState
      }
      delay(POLL_INTERVAL_MS)
    }
    return modelManagerViewModel.uiState.value
  }

  private suspend fun awaitModelInitialization(model: Model) {
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < MODEL_INIT_TIMEOUT_MS) {
      if (model.instance != null) {
        return
      }

      val status = modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]
      if (status?.status == ModelInitializationStatusType.ERROR) {
        error(status.error.ifBlank { "Model '${model.name}' failed to initialize." })
      }

      delay(POLL_INTERVAL_MS)
    }

    val status = modelManagerViewModel.uiState.value.modelInitializationStatus[model.name]
    error(
      status?.error?.ifBlank { null }
        ?: "Timed out waiting for model '${model.name}' to initialize within ${MODEL_INIT_TIMEOUT_MS / 1000}s.",
    )
  }

  private fun writeStatus(
    runDir: java.io.File,
    state: String,
    phase: String,
    runId: String,
    suiteId: String,
    manifestPath: String,
    startedAtEpochMs: Long,
    completedCases: Int,
    totalCases: Int,
    currentCaseId: String? = null,
    resolvedModelName: String? = null,
    errorMessage: String? = null,
  ) {
    RoleplayEvalStorage.writeJson(
      RoleplayEvalStorage.statusFile(runDir),
      RoleplayEvalRunStatus(
        state = state,
        phase = phase,
        runId = runId,
        suiteId = suiteId,
        manifestPath = manifestPath,
        startedAtEpochMs = startedAtEpochMs,
        updatedAtEpochMs = System.currentTimeMillis(),
        completedCases = completedCases,
        totalCases = totalCases,
        currentCaseId = currentCaseId,
        resolvedModelName = resolvedModelName,
        errorMessage = errorMessage,
      ),
    )
  }

  private fun writeRunError(
    runDir: java.io.File,
    runId: String,
    suiteId: String,
    phase: String,
    throwable: Throwable,
  ) {
    RoleplayEvalStorage.writeJson(
      RoleplayEvalStorage.errorFile(runDir),
      RoleplayEvalRunError(
        runId = runId,
        suiteId = suiteId,
        phase = phase,
        message = throwable.message ?: throwable.javaClass.simpleName,
        throwableClass = throwable.javaClass.name,
        stackTrace = throwable.stackTraceToString(),
        createdAtEpochMs = System.currentTimeMillis(),
      ),
    )
  }
}
