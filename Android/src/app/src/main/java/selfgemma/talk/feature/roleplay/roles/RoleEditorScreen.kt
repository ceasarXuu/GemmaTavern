package selfgemma.talk.feature.roleplay.roles

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.RoleMediaUsage
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorInfoCard
import selfgemma.talk.ui.common.AppEditorSectionHeader
import selfgemma.talk.ui.common.AppEditorStatusCard
import selfgemma.talk.ui.common.AppOutlinedTextField
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RoleEditorScreen"

private fun RoleEditorTab.benchmarkTestTag(): String =
  when (this) {
    RoleEditorTab.CARD -> "role_editor_tab_card"
    RoleEditorTab.PROMPT -> "role_editor_tab_prompt"
    RoleEditorTab.LOREBOOK -> "role_editor_tab_lorebook"
    RoleEditorTab.METADATA -> "role_editor_tab_metadata"
    RoleEditorTab.MEDIA -> "role_editor_tab_media"
    RoleEditorTab.INTEROP -> "role_editor_tab_interop"
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleEditorScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: RoleEditorViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
  val compressionScope = rememberCoroutineScope()
  val activeCompressions = remember { mutableStateMapOf<String, ActiveRoleEditorCompression>() }
  var modelMenuExpanded by remember { mutableStateOf(false) }
  var showMissingAvatarExportDialog by remember { mutableStateOf(false) }
  var exportPngAfterAvatarPick by remember { mutableStateOf(false) }
  var activeHelpTopic by remember { mutableStateOf<RoleEditorHelpTopic?>(null) }
  val context = LocalContext.current
  val configuredAssistantModelId = modelManagerViewModel.dataStoreRepository.getRoleEditorAssistantModelId()
  val assistantModel =
    downloadedModels.firstOrNull { it.name == configuredAssistantModelId }
      ?: downloadedModels.firstOrNull()

  fun cancelAllFieldCompressions() {
    cancelAllRoleEditorFieldCompressions(activeCompressions)
  }

  fun launchCompression(
    fieldKey: String,
    fieldTitle: String,
    maxChars: Int,
    currentValue: String,
    onValueChange: (String) -> Unit,
  ) {
    launchRoleEditorFieldCompression(
      context = context,
      viewModel = viewModel,
      compressionScope = compressionScope,
      activeCompressions = activeCompressions,
      resolvedModel = assistantModel,
      fieldKey = fieldKey,
      fieldTitle = fieldTitle,
      maxChars = maxChars,
      currentValue = currentValue,
      onValueChange = onValueChange,
    )
  }

  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { viewModel.importStCardFromUri(it.toString()) }
    }
  val exportJsonLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
      uri?.let { viewModel.exportStCardToUri(it.toString()) }
    }
  val exportPngLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
      uri?.let { viewModel.exportStCardToUri(it.toString()) }
    }
  val avatarLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri == null) {
        exportPngAfterAvatarPick = false
      } else {
        takeReadPermission(context = context, uri = uri)
        viewModel.updateAvatarUri(uri.toString())
        if (exportPngAfterAvatarPick) {
          exportPngAfterAvatarPick = false
          val fileName = uiState.name.ifBlank { "role-card" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
          exportPngLauncher.launch("${fileName}.png")
        }
      }
    }
  val galleryLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
      if (uris.isNotEmpty()) {
        uris.forEach { takeReadPermission(context = context, uri = it) }
        viewModel.addGalleryAssets(uris.map(Uri::toString))
      }
    }

  val handleNavigateUp: () -> Unit = {
    if (modelMenuExpanded) {
      modelMenuExpanded = false
      Log.d(TAG, "dismiss model picker before navigating up")
    } else {
      cancelAllFieldCompressions()
      navigateUp()
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      cancelAllFieldCompressions()
    }
  }

  BackHandler { handleNavigateUp() }

  Scaffold(
    modifier = modifier.semantics { testTagsAsResourceId = true },
    topBar = {
      AppTopBar(
        title = if (uiState.isNewRole) stringResource(R.string.role_editor_create_title) else stringResource(R.string.role_editor_edit_title),
        leftAction = AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = handleNavigateUp),
        rightActionContent = {
          IconButton(
            onClick = viewModel::undo,
            enabled = uiState.canUndo,
            modifier = Modifier.size(36.dp),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Undo,
              contentDescription = stringResource(R.string.undo),
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = viewModel::redo,
            enabled = uiState.canRedo,
            modifier = Modifier.size(36.dp),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Redo,
              contentDescription = stringResource(R.string.redo),
              modifier = Modifier.size(18.dp),
            )
          }
          TextButton(
            onClick = {
              cancelAllFieldCompressions()
              viewModel.saveRole { handleNavigateUp() }
            },
            modifier = Modifier.testTag("role_editor_save"),
          ) {
            Text(stringResource(R.string.save))
          }
        },
      )
    },
  ) { innerPadding ->
    if (uiState.loading) {
      Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.role_editor_loading), style = MaterialTheme.typography.headlineSmall)
      }
      return@Scaffold
    }

    val tabs =
      listOf(
        RoleEditorTab.CARD to stringResource(R.string.role_editor_tab_card),
        RoleEditorTab.PROMPT to stringResource(R.string.role_editor_tab_prompt),
        RoleEditorTab.LOREBOOK to stringResource(R.string.role_editor_tab_lorebook),
        RoleEditorTab.METADATA to stringResource(R.string.role_editor_tab_metadata),
        RoleEditorTab.MEDIA to stringResource(R.string.role_editor_tab_media),
        RoleEditorTab.INTEROP to stringResource(R.string.role_editor_tab_interop),
      )
    val pagerState = rememberPagerState(initialPage = uiState.selectedTab.ordinal) { tabs.size }
    val pagerScope = rememberCoroutineScope()

    LaunchedEffect(uiState.selectedTab) {
      if (pagerState.currentPage != uiState.selectedTab.ordinal) {
        pagerState.animateScrollToPage(uiState.selectedTab.ordinal)
      }
    }

    LaunchedEffect(pagerState.settledPage) {
      val pagerTab = RoleEditorTab.entries.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
      if (pagerTab != uiState.selectedTab) {
        Log.d(TAG, "Role editor page changed by swipe tab=$pagerTab")
        viewModel.selectTab(pagerTab)
      }
    }

    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      PrimaryScrollableTabRow(
        selectedTabIndex = uiState.selectedTab.ordinal,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 16.dp,
      ) {
        tabs.forEach { (tab, title) ->
          Tab(
            selected = uiState.selectedTab == tab,
            onClick = {
              viewModel.selectTab(tab)
              pagerScope.launch {
                pagerState.animateScrollToPage(tab.ordinal)
              }
            },
            modifier = Modifier.testTag(tab.benchmarkTestTag()),
            text = {
              Text(
                text = title,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
              )
            },
          )
        }
      }

      HorizontalPager(
        state = pagerState,
        userScrollEnabled = true,
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
      ) { page ->
        when (RoleEditorTab.entries[page]) {
          RoleEditorTab.CARD ->
            RoleEditorCardPage(
              uiState = uiState,
              onShowHelp = { activeHelpTopic = it },
              onUpdateName = viewModel::updateName,
              onUpdateDescription = viewModel::updateDescription,
              onUpdatePersonality = viewModel::updatePersonality,
              onUpdateScenario = viewModel::updateScenario,
              onUpdateFirstMessage = viewModel::updateFirstMessage,
              onUpdateMessageExample = viewModel::updateMessageExample,
              isFieldCompressing = { it in activeCompressions },
              onCompressField = ::launchCompression,
            )
          RoleEditorTab.PROMPT ->
            RoleEditorPromptPage(
              uiState = uiState,
              onShowHelp = { activeHelpTopic = it },
              onUpdateSystemPrompt = viewModel::updateSystemPrompt,
              onUpdatePostHistoryInstructions = viewModel::updatePostHistoryInstructions,
              onUpdateAlternateGreetingsText = viewModel::updateAlternateGreetingsText,
              isFieldCompressing = { it in activeCompressions },
              onCompressField = ::launchCompression,
            )
          RoleEditorTab.LOREBOOK ->
            RoleEditorLorebookPage(
              uiState = uiState,
              onShowHelp = { activeHelpTopic = it },
              onUpdateCharacterBookName = viewModel::updateCharacterBookName,
              onUpdateCharacterBookDescription = viewModel::updateCharacterBookDescription,
              onUpdateCharacterBookScanDepth = viewModel::updateCharacterBookScanDepth,
              onUpdateCharacterBookTokenBudget = viewModel::updateCharacterBookTokenBudget,
              onUpdateCharacterBookRecursiveScanning = viewModel::updateCharacterBookRecursiveScanning,
              onAddCharacterBookEntry = viewModel::addCharacterBookEntry,
              onUpdateEntryId = viewModel::updateCharacterBookEntryId,
              onUpdateEntryKeys = viewModel::updateCharacterBookEntryKeys,
              onUpdateEntrySecondaryKeys = viewModel::updateCharacterBookEntrySecondaryKeys,
              onUpdateEntryComment = viewModel::updateCharacterBookEntryComment,
              onUpdateEntryContent = viewModel::updateCharacterBookEntryContent,
              onUpdateEntryConstant = viewModel::updateCharacterBookEntryConstant,
              onUpdateEntrySelective = viewModel::updateCharacterBookEntrySelective,
              onUpdateEntryInsertionOrder = viewModel::updateCharacterBookEntryInsertionOrder,
              onUpdateEntryEnabled = viewModel::updateCharacterBookEntryEnabled,
              onUpdateEntryPosition = viewModel::updateCharacterBookEntryPosition,
              onUpdateEntryUseRegex = viewModel::updateCharacterBookEntryUseRegex,
              onRemoveEntry = viewModel::removeCharacterBookEntry,
              isFieldCompressing = { it in activeCompressions },
              onCompressField = ::launchCompression,
            )
          RoleEditorTab.METADATA ->
            RoleEditorMetadataPage(
              uiState = uiState,
              downloadedModels = downloadedModels,
              modelMenuExpanded = modelMenuExpanded,
              onShowHelp = { activeHelpTopic = it },
              onModelMenuExpandedChange = { modelMenuExpanded = it },
              onUpdateCreator = viewModel::updateCreator,
              onUpdateCreatorNotes = viewModel::updateCreatorNotes,
              onUpdateCharacterVersion = viewModel::updateCharacterVersion,
              onUpdateTagsText = viewModel::updateTagsText,
              onUpdateTalkativenessText = viewModel::updateTalkativenessText,
              onUpdateFav = viewModel::updateFav,
              onUpdateSafetyPolicy = viewModel::updateSafetyPolicy,
              onUpdateDefaultModelId = viewModel::updateDefaultModelId,
              isFieldCompressing = { it in activeCompressions },
              onCompressField = ::launchCompression,
            )
          RoleEditorTab.MEDIA ->
            RoleEditorMediaPage(
              uiState = uiState,
              onPickAvatar = { avatarLauncher.launch(arrayOf("image/*")) },
              onClearAvatar = { viewModel.updateAvatarUri(null) },
              onAddGallery = { galleryLauncher.launch(arrayOf("image/*")) },
              onRenameGalleryAsset = viewModel::updateGalleryAssetName,
              onUpdateGalleryUsage = viewModel::updateGalleryAssetUsage,
              onSetGalleryAsAvatar = viewModel::setGalleryAssetAsAvatar,
              onRemoveGalleryAsset = viewModel::removeGalleryAsset,
            )
          RoleEditorTab.INTEROP ->
            RoleEditorInteropPage(
              uiState = uiState,
              context = context,
              onShowHelp = { activeHelpTopic = it },
              onImportStCard = { importLauncher.launch("*/*") },
              onExportStJson = {
                val fileName = uiState.name.ifBlank { "role-card" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                exportJsonLauncher.launch("${fileName}.json")
              },
              onExportStPng = {
                if (uiState.avatarUri.isNullOrBlank()) {
                  showMissingAvatarExportDialog = true
                } else {
                  val fileName = uiState.name.ifBlank { "role-card" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                  exportPngLauncher.launch("${fileName}.png")
                }
              },
            )
        }
      }
    }
  }

  if (showMissingAvatarExportDialog) {
    AlertDialog(
      onDismissRequest = {
        showMissingAvatarExportDialog = false
        exportPngAfterAvatarPick = false
      },
      title = { Text(stringResource(R.string.role_editor_export_png_missing_avatar_title)) },
      text = { Text(stringResource(R.string.role_editor_export_png_missing_avatar_content)) },
      confirmButton = {
        FilledTonalButton(
          onClick = {
            showMissingAvatarExportDialog = false
            val fileName = uiState.name.ifBlank { "role-card" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            exportPngLauncher.launch("${fileName}.png")
          },
        ) {
          Text(stringResource(R.string.role_editor_export_png_use_default))
        }
      },
      dismissButton = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = {
              showMissingAvatarExportDialog = false
              exportPngAfterAvatarPick = true
              avatarLauncher.launch(arrayOf("image/*"))
            },
          ) {
            Text(stringResource(R.string.role_editor_export_png_upload_image))
          }
          OutlinedButton(
            onClick = {
              showMissingAvatarExportDialog = false
              exportPngAfterAvatarPick = false
            },
          ) {
            Text(stringResource(R.string.cancel))
          }
        }
      },
    )
  }

  activeHelpTopic?.let { helpTopic ->
    RoleEditorHelpDialog(
      topic = helpTopic,
      onDismiss = { activeHelpTopic = null },
    )
  }
}

