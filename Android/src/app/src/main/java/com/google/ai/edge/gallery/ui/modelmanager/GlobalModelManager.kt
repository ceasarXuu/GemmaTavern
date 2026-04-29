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

package selfgemma.talk.ui.modelmanager

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import selfgemma.talk.BuildConfig
import selfgemma.talk.R
import selfgemma.talk.data.Model
import selfgemma.talk.data.RuntimeType
import selfgemma.talk.data.Task
import selfgemma.talk.proto.ImportedModel
import selfgemma.talk.ui.common.ConfigDialog
import selfgemma.talk.ui.common.TaskIcon
import selfgemma.talk.ui.common.modelitem.ModelItem
import kotlin.text.endsWith
import kotlin.text.lowercase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGGlobalMM"
private const val ONLINE_MODELS_TAB_INDEX = 0
private const val IMPORTED_MODELS_TAB_INDEX = 1
private const val MODEL_LIBRARY_TAB_COUNT = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalModelManager(
  viewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  onModelSelected: (Task, Model) -> Unit,
  onBenchmarkClicked: (Model) -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()
  val builtInModels = remember { mutableStateListOf<Model>() }
  val importedModels = remember { mutableStateListOf<Model>() }
  val taskCandidates = remember { mutableStateListOf<Task>() }
  var modelForTaskCandidate by remember { mutableStateOf<Model?>(null) }
  var showTaskSelectorBottomSheet by remember { mutableStateOf(false) }
  var showImportModelSheet by remember { mutableStateOf(false) }
  var showUnsupportedFileTypeDialog by remember { mutableStateOf(false) }
  var showUnsupportedWebModelDialog by remember { mutableStateOf(false) }
  val selectedLocalModelFileUri = remember { mutableStateOf<Uri?>(null) }
  val selectedImportedModelInfo = remember { mutableStateOf<ImportedModel?>(null) }
  var importedModelForConfig by remember { mutableStateOf<Model?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showImportDialog by remember { mutableStateOf(false) }
  var showImportingDialog by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val modelItemExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
  val pagerState =
    rememberPagerState(initialPage = ONLINE_MODELS_TAB_INDEX, pageCount = { MODEL_LIBRARY_TAB_COUNT })
  val selectedTabIndex by remember { derivedStateOf { pagerState.currentPage } }

  val promoId = "gm4_banner"
  var showPromo by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    showPromo = !viewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)
  }

  val filePickerLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          val fileName = getFileName(context = context, uri = uri)
          Log.d(TAG, "Selected file: $fileName")
          // Show warning for model file types other than .task and .litertlm.
          if (fileName != null && !fileName.endsWith(".task") && !fileName.endsWith(".litertlm")) {
            showUnsupportedFileTypeDialog = true
          }
          // Show warning for web-only model (by checking if the file name has "-web" in it).
          else if (fileName != null && fileName.lowercase().contains("-web")) {
            showUnsupportedWebModelDialog = true
          } else {
            selectedLocalModelFileUri.value = uri
            showImportDialog = true
          }
        } ?: run { Log.d(TAG, "No file selected or URI is null.") }
      } else {
        Log.d(TAG, "File picking cancelled.")
      }
    }

  LaunchedEffect(uiState.modelImportingUpdateTrigger) {
    val allModelsSet = mutableSetOf<Model>()
    for (task in uiState.tasks) {
      for (model in task.models) {
        allModelsSet.add(model)
      }
    }
    val sortedModels = allModelsSet.toList().sortedBy { it.displayName.ifEmpty { it.name } }
    builtInModels.clear()
    builtInModels.addAll(sortedModels.filter { !it.imported })
    importedModels.clear()
    importedModels.addAll(sortedModels.filter { it.imported })
    Log.d(
      TAG,
      "model library refreshed builtInCount=${builtInModels.size} importedCount=${importedModels.size}",
    )
  }

  LaunchedEffect(pagerState.settledPage, builtInModels.size, importedModels.size) {
    val tabName =
      if (pagerState.settledPage == ONLINE_MODELS_TAB_INDEX) "online_models" else "imported_models"
    Log.d(
      TAG,
      "model library tab changed tab=$tabName builtInCount=${builtInModels.size} importedCount=${importedModels.size}",
    )
  }

  val handleClickModel: (Model) -> Unit = { model ->
    val tasks = viewModel.uiState.value.tasks
    val tasksForModel = tasks.filter { task -> task.models.any { it.name == model.name } }
    // If there is only one task for the model, navigate to the model directly.
    if (tasksForModel.size == 1) {
      onModelSelected(tasksForModel[0], model)
    }
    // If there are multiple tasks for the model, show a bottom sheet for the user to choose which
    // task to use.
    else if (tasksForModel.size > 1) {
      taskCandidates.clear()
      taskCandidates.addAll(tasksForModel)
      modelForTaskCandidate = model
      showTaskSelectorBottomSheet = true
    }
  }

  // Handle system's edge swipe.
  BackHandler { navigateUp() }

  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                Icons.AutoMirrored.Rounded.ListAlt,
                modifier = Modifier.size(20.dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  "${stringResource(R.string.drawer_models_label)} (${builtInModels.size + importedModels.size})",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
            }
          }
        },
        // The "action" component at the right.
        actions = {
          IconButton(onClick = { navigateUp() }) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.cd_close_icon),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
        modifier = modifier,
      )
    },
    floatingActionButton = {
      // A floating action button to show "import model" bottom sheet.
      val cdImportModelFab = stringResource(R.string.cd_import_model_button)
      SmallFloatingActionButton(
        onClick = { showImportModelSheet = true },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.semantics { contentDescription = cdImportModelFab },
      ) {
        Icon(Icons.Filled.Add, contentDescription = null)
      }
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
          .fillMaxWidth()
          .padding(top = innerPadding.calculateTopPadding()),
    ) {
      PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color.Transparent,
        modifier = Modifier.padding(horizontal = 16.dp),
      ) {
        Tab(
          selected = selectedTabIndex == ONLINE_MODELS_TAB_INDEX,
          onClick = {
            scope.launch {
              pagerState.animateScrollToPage(page = ONLINE_MODELS_TAB_INDEX)
            }
          },
          text = { Text(stringResource(R.string.model_library_tab_online_models)) },
        )
        Tab(
          selected = selectedTabIndex == IMPORTED_MODELS_TAB_INDEX,
          onClick = {
            scope.launch {
              pagerState.animateScrollToPage(page = IMPORTED_MODELS_TAB_INDEX)
            }
          },
          text = { Text(stringResource(R.string.model_library_tab_local_imports)) },
        )
      }

      Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize(),
          verticalAlignment = Alignment.Top,
          key = { page -> page },
        ) { page ->
          if (page == IMPORTED_MODELS_TAB_INDEX && importedModels.isEmpty()) {
            EmptyImportedModelsState(bottomPaddingDp = innerPadding.calculateBottomPadding())
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding =
                PaddingValues(top = 16.dp, bottom = innerPadding.calculateBottomPadding() + 80.dp),
            ) {
              if (page == ONLINE_MODELS_TAB_INDEX) {
                item(key = "promo") {
                  androidx.compose.animation.AnimatedVisibility(
                    visible = showPromo,
                    enter =
                      fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }) + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                  ) {
                    PromoBannerGm4(
                      onDismiss = {
                        showPromo = false
                        viewModel.dataStoreRepository.addViewedPromoId(promoId = promoId)
                      }
                    )
                  }
                }

                items(items = builtInModels, key = { it.name }) { model ->
                  val expanded = modelItemExpandedStates.getOrDefault(model.name, false)
                  ModelItem(
                    model = model,
                    task = null,
                    modelManagerViewModel = viewModel,
                    onModelClicked = handleClickModel,
                    onBenchmarkClicked = onBenchmarkClicked,
                    expanded = expanded,
                    showBenchmarkButton =
                      BuildConfig.ENABLE_BENCHMARK_UI && model.runtimeType == RuntimeType.LITERT_LM,
                    downloadStatusOverride = uiState.modelDownloadStatus[model.name],
                    onExpanded = { modelItemExpandedStates[model.name] = it },
                  )
                }
              } else {
                items(items = importedModels, key = { it.name }) { model ->
                  ModelItem(
                    model = model,
                    task = null,
                    modelManagerViewModel = viewModel,
                    onModelClicked = handleClickModel,
                    onBenchmarkClicked = onBenchmarkClicked,
                    expanded = true,
                    showBenchmarkButton =
                      BuildConfig.ENABLE_BENCHMARK_UI && model.runtimeType == RuntimeType.LITERT_LM,
                    downloadStatusOverride = uiState.modelDownloadStatus[model.name],
                    onImportedModelConfigClicked = { importedModelForConfig = it },
                  )
                }
              }
            }
          }
        }

        SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(alignment = Alignment.BottomCenter).padding(bottom = 32.dp),
        )

        // Gradient overlay at the bottom.
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(innerPadding.calculateBottomPadding())
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
                )
              )
              .align(Alignment.BottomCenter)
        )
      }
    }
  }

  if (showTaskSelectorBottomSheet) {
    TaskSelectorBottomSheet(
      taskCandidates = taskCandidates,
      sheetState = sheetState,
      onDismiss = { showTaskSelectorBottomSheet = false },
      onTaskSelected = { task ->
        val model = modelForTaskCandidate
        if (model != null) {
          onModelSelected(task, model)
        }
        scope.launch {
          sheetState.hide()
          showTaskSelectorBottomSheet = false
        }
      },
    )
  }

  // Import model bottom sheet.
  if (showImportModelSheet) {
    ImportModelBottomSheet(
      sheetState = sheetState,
      onDismiss = { showImportModelSheet = false },
      onImportFromLocalFile = {
        scope.launch {
          delay(200)
          showImportModelSheet = false
          val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
              addCategory(Intent.CATEGORY_OPENABLE)
              type = "*/*"
              putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            }
          filePickerLauncher.launch(intent)
        }
      },
    )
  }

  // Import dialog
  if (showImportDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      ModelImportDialog(
        uri = uri,
        onDismiss = { showImportDialog = false },
        onDone = { info ->
          selectedImportedModelInfo.value = info
          showImportDialog = false
          showImportingDialog = true
        },
      )
    }
  }

  // Importing in progress dialog.
  if (showImportingDialog) {
    selectedLocalModelFileUri.value?.let { uri ->
      selectedImportedModelInfo.value?.let { info ->
        ModelImportingDialog(
          uri = uri,
          info = info,
          onDismiss = { showImportingDialog = false },
          onDone = {
            viewModel.addImportedLlmModel(info = it)
            showImportingDialog = false

            // Show a snack bar for successful import.
            scope.launch { snackbarHostState.showSnackbar("Model imported successfully") }
          },
        )
      }
    }
  }

  importedModelForConfig?.let { model ->
    ConfigDialog(
      title = stringResource(R.string.model_library_edit_imported_model_config_title),
      subtitle = model.displayName.ifEmpty { model.name },
      configs = model.configs,
      initialValues = model.configValues,
      onDismissed = { importedModelForConfig = null },
      onOk = { values, _, _ ->
        val saved = viewModel.updateImportedLlmModelConfig(model = model, values = values)
        importedModelForConfig = null
        scope.launch {
          snackbarHostState.showSnackbar(
            if (saved) {
              context.getString(R.string.model_library_imported_model_config_saved)
            } else {
              context.getString(R.string.model_library_imported_model_config_save_failed)
            }
          )
        }
      },
    )
  }

  // Alert dialog for unsupported file type.
  if (showUnsupportedFileTypeDialog) {
    UnsupportedFileTypeAlertDialog(onDismiss = { showUnsupportedFileTypeDialog = false })
  }

  // Alert dialog for unsupported web model.
  if (showUnsupportedWebModelDialog) {
    UnsupportedWebModelAlertDialog(onDismiss = { showUnsupportedWebModelDialog = false })
  }
}

// Helper function to get the file name from a URI moved to GlobalModelManagerComponents.kt.

