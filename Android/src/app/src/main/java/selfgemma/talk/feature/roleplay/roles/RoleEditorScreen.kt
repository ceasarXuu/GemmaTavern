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
import selfgemma.talk.common.processLlmResponse
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.RoleMediaUsage
import selfgemma.talk.runtime.runtimeHelper
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorInfoCard
import selfgemma.talk.ui.common.AppEditorSectionHeader
import selfgemma.talk.ui.common.AppEditorStatusCard
import selfgemma.talk.ui.common.AppOutlinedTextField
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

private const val TAG = "RoleEditorScreen"
private const val ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES = 8
private const val ROLE_EDITOR_LARGE_TEXT_MAX_LINES = 12
private const val ROLE_EDITOR_XL_TEXT_MAX_LINES = 14
private const val ROLE_EDITOR_SINGLE_LINE_TEXTFIELD_MIN_HEIGHT_DP = 80
private const val ROLE_EDITOR_TEXTFIELD_BASE_HEIGHT_DP = 64
private const val ROLE_EDITOR_TEXTFIELD_LINE_STEP_DP = 24
private const val ROLE_EDITOR_COMPRESSION_INIT_TIMEOUT_MS = 60_000L
private const val ROLE_EDITOR_COMPRESSION_MAX_ATTEMPTS = 3

private fun RoleEditorTab.benchmarkTestTag(): String =
  when (this) {
    RoleEditorTab.CARD -> "role_editor_tab_card"
    RoleEditorTab.PROMPT -> "role_editor_tab_prompt"
    RoleEditorTab.LOREBOOK -> "role_editor_tab_lorebook"
    RoleEditorTab.METADATA -> "role_editor_tab_metadata"
    RoleEditorTab.MEDIA -> "role_editor_tab_media"
    RoleEditorTab.INTEROP -> "role_editor_tab_interop"
  }

private data class RoleEditorTextFieldSpec(
  val maxChars: Int? = null,
  val supportsAiCompress: Boolean = false,
)

private class ActiveRoleEditorCompression(
  val fieldKey: String,
  val originalValue: String,
  val restoreValue: (String) -> Unit,
  val job: Job,
) {
  var completed: Boolean = false
}

private enum class RoleEditorHelpTopic(val titleRes: Int, val bodyRes: Int) {
  ROLE_NAME(R.string.role_editor_name_label, R.string.role_editor_help_role_name_body),
  DESCRIPTION(R.string.role_editor_summary_label, R.string.role_editor_help_description_body),
  PERSONALITY(R.string.role_editor_persona_label, R.string.role_editor_help_personality_body),
  SCENARIO(R.string.role_editor_world_settings_label, R.string.role_editor_help_scenario_body),
  FIRST_MESSAGE(R.string.role_editor_opening_line_label, R.string.role_editor_help_first_message_body),
  EXAMPLE_DIALOGUE(R.string.role_editor_message_example_label, R.string.role_editor_help_example_dialogue_body),
  SYSTEM_PROMPT(R.string.role_editor_system_prompt_label, R.string.role_editor_help_system_prompt_body),
  POST_HISTORY(R.string.role_editor_post_history_instructions_label, R.string.role_editor_help_post_history_body),
  ALTERNATE_GREETINGS(R.string.role_editor_alternate_greetings_label, R.string.role_editor_help_alternate_greetings_body),
  LOREBOOK_NAME(R.string.role_editor_lorebook_name_label, R.string.role_editor_help_lorebook_name_body),
  LOREBOOK_DESCRIPTION(R.string.role_editor_lorebook_description_label, R.string.role_editor_help_lorebook_description_body),
  LOREBOOK_SCAN_DEPTH(R.string.role_editor_lorebook_scan_depth_label, R.string.role_editor_help_lorebook_scan_depth_body),
  LOREBOOK_TOKEN_BUDGET(R.string.role_editor_lorebook_token_budget_label, R.string.role_editor_help_lorebook_token_budget_body),
  LOREBOOK_RECURSIVE(R.string.role_editor_lorebook_recursive_label, R.string.role_editor_help_lorebook_recursive_body),
  LORE_ENTRY_ID(R.string.role_editor_lorebook_entry_id_label, R.string.role_editor_help_lore_entry_id_body),
  LORE_ENTRY_KEYS(R.string.role_editor_lorebook_entry_keys_label, R.string.role_editor_help_lore_entry_keys_body),
  LORE_ENTRY_SECONDARY_KEYS(R.string.role_editor_lorebook_entry_secondary_keys_label, R.string.role_editor_help_lore_entry_secondary_keys_body),
  LORE_ENTRY_COMMENT(R.string.role_editor_lorebook_entry_comment_label, R.string.role_editor_help_lore_entry_comment_body),
  LORE_ENTRY_CONTENT(R.string.role_editor_lorebook_entry_content_label, R.string.role_editor_help_lore_entry_content_body),
  LORE_ENTRY_ORDER(R.string.role_editor_lorebook_entry_order_label, R.string.role_editor_help_lore_entry_order_body),
  LORE_ENTRY_POSITION(R.string.role_editor_lorebook_entry_position_label, R.string.role_editor_help_lore_entry_position_body),
  LORE_ENTRY_ENABLED(R.string.role_editor_lorebook_entry_enabled_label, R.string.role_editor_help_lore_entry_enabled_body),
  LORE_ENTRY_CONSTANT(R.string.role_editor_lorebook_entry_constant_label, R.string.role_editor_help_lore_entry_constant_body),
  LORE_ENTRY_SELECTIVE(R.string.role_editor_lorebook_entry_selective_label, R.string.role_editor_help_lore_entry_selective_body),
  LORE_ENTRY_REGEX(R.string.role_editor_lorebook_entry_regex_label, R.string.role_editor_help_lore_entry_regex_body),
  CREATOR(R.string.role_editor_creator_label, R.string.role_editor_help_creator_body),
  CREATOR_NOTES(R.string.role_editor_creator_notes_label, R.string.role_editor_help_creator_notes_body),
  CHARACTER_VERSION(R.string.role_editor_character_version_label, R.string.role_editor_help_character_version_body),
  TAGS(R.string.role_editor_tags_label, R.string.role_editor_help_tags_body),
  TALKATIVENESS(R.string.role_editor_talkativeness_label, R.string.role_editor_help_talkativeness_body),
  FAVORITE(R.string.role_editor_favorite_label, R.string.role_editor_help_favorite_body),
  SAFETY_POLICY(R.string.role_editor_safety_policy_label, R.string.role_editor_help_safety_policy_body),
  DEFAULT_MODEL(R.string.role_editor_default_model_label, R.string.role_editor_help_default_model_body),
  INTEROP(R.string.role_editor_interop_title, R.string.role_editor_help_interop_body),
}

private fun roleEditorTextFieldSpec(topic: RoleEditorHelpTopic?): RoleEditorTextFieldSpec? =
  when (topic) {
    RoleEditorHelpTopic.ROLE_NAME -> RoleEditorTextFieldSpec(maxChars = 120)
    RoleEditorHelpTopic.DESCRIPTION -> RoleEditorTextFieldSpec(maxChars = 400, supportsAiCompress = true)
    RoleEditorHelpTopic.PERSONALITY -> RoleEditorTextFieldSpec(maxChars = 600, supportsAiCompress = true)
    RoleEditorHelpTopic.SCENARIO -> RoleEditorTextFieldSpec(maxChars = 500, supportsAiCompress = true)
    RoleEditorHelpTopic.FIRST_MESSAGE -> RoleEditorTextFieldSpec(maxChars = 800, supportsAiCompress = true)
    RoleEditorHelpTopic.EXAMPLE_DIALOGUE -> RoleEditorTextFieldSpec(maxChars = 2400, supportsAiCompress = true)
    RoleEditorHelpTopic.SYSTEM_PROMPT -> RoleEditorTextFieldSpec(maxChars = 1200, supportsAiCompress = true)
    RoleEditorHelpTopic.POST_HISTORY -> RoleEditorTextFieldSpec(maxChars = 500, supportsAiCompress = true)
    RoleEditorHelpTopic.ALTERNATE_GREETINGS -> RoleEditorTextFieldSpec(maxChars = 600, supportsAiCompress = true)
    RoleEditorHelpTopic.LOREBOOK_NAME -> RoleEditorTextFieldSpec(maxChars = 120, supportsAiCompress = true)
    RoleEditorHelpTopic.LOREBOOK_DESCRIPTION -> RoleEditorTextFieldSpec(maxChars = 400, supportsAiCompress = true)
    RoleEditorHelpTopic.LOREBOOK_SCAN_DEPTH -> RoleEditorTextFieldSpec(maxChars = 4)
    RoleEditorHelpTopic.LOREBOOK_TOKEN_BUDGET -> RoleEditorTextFieldSpec(maxChars = 4)
    RoleEditorHelpTopic.LORE_ENTRY_ID -> RoleEditorTextFieldSpec(maxChars = 8)
    RoleEditorHelpTopic.LORE_ENTRY_KEYS -> RoleEditorTextFieldSpec(maxChars = 240)
    RoleEditorHelpTopic.LORE_ENTRY_SECONDARY_KEYS -> RoleEditorTextFieldSpec(maxChars = 240)
    RoleEditorHelpTopic.LORE_ENTRY_COMMENT -> RoleEditorTextFieldSpec(maxChars = 240, supportsAiCompress = true)
    RoleEditorHelpTopic.LORE_ENTRY_CONTENT -> RoleEditorTextFieldSpec(maxChars = 800, supportsAiCompress = true)
    RoleEditorHelpTopic.LORE_ENTRY_ORDER -> RoleEditorTextFieldSpec(maxChars = 6)
    RoleEditorHelpTopic.LORE_ENTRY_POSITION -> RoleEditorTextFieldSpec(maxChars = 24)
    RoleEditorHelpTopic.CREATOR -> RoleEditorTextFieldSpec(maxChars = 120, supportsAiCompress = true)
    RoleEditorHelpTopic.CREATOR_NOTES -> RoleEditorTextFieldSpec(maxChars = 600, supportsAiCompress = true)
    RoleEditorHelpTopic.CHARACTER_VERSION -> RoleEditorTextFieldSpec(maxChars = 32)
    RoleEditorHelpTopic.TAGS -> RoleEditorTextFieldSpec(maxChars = 200, supportsAiCompress = true)
    RoleEditorHelpTopic.TALKATIVENESS -> RoleEditorTextFieldSpec(maxChars = 4)
    RoleEditorHelpTopic.SAFETY_POLICY -> RoleEditorTextFieldSpec(maxChars = 400, supportsAiCompress = true)
    else -> null
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

  fun launchCompression(
    fieldKey: String,
    fieldTitle: String,
    maxChars: Int,
    currentValue: String,
    onValueChange: (String) -> Unit,
  ) {
    if (fieldKey in activeCompressions || activeCompressions.isNotEmpty()) {
      return
    }
    val resolvedModel = assistantModel
    if (resolvedModel == null) {
      viewModel.showErrorMessage(context.getString(R.string.role_editor_ai_compress_missing_model))
      Log.w(TAG, "Role editor AI compression requested without any local model")
      return
    }

    val job =
      compressionScope.launch(Dispatchers.Default) {
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

    activeCompressions[fieldKey] =
      ActiveRoleEditorCompression(
        fieldKey = fieldKey,
        originalValue = currentValue,
        restoreValue = onValueChange,
        job = job,
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

@Composable
private fun RoleEditorCardPage(
  uiState: RoleEditorUiState,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  onUpdateName: (String) -> Unit,
  onUpdateDescription: (String) -> Unit,
  onUpdatePersonality: (String) -> Unit,
  onUpdateScenario: (String) -> Unit,
  onUpdateFirstMessage: (String) -> Unit,
  onUpdateMessageExample: (String) -> Unit,
  isFieldCompressing: (String) -> Boolean,
  onCompressField: (String, String, Int, String, (String) -> Unit) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      RequiredFieldsHintCard()
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_name_label),
        value = uiState.name,
        onValueChange = onUpdateName,
        minLines = 1,
        testTag = "role_editor_name",
        required = true,
        helpTopic = RoleEditorHelpTopic.ROLE_NAME,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_name",
        isCompressing = isFieldCompressing("role_editor_name"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_summary_label),
        value = uiState.description,
        onValueChange = onUpdateDescription,
        minLines = 3,
        maxLines = ROLE_EDITOR_LARGE_TEXT_MAX_LINES,
        testTag = "role_editor_description",
        helpTopic = RoleEditorHelpTopic.DESCRIPTION,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_description",
        isCompressing = isFieldCompressing("role_editor_description"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_persona_label),
        value = uiState.personality,
        onValueChange = onUpdatePersonality,
        minLines = 4,
        maxLines = ROLE_EDITOR_LARGE_TEXT_MAX_LINES,
        testTag = "role_editor_personality",
        helpTopic = RoleEditorHelpTopic.PERSONALITY,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_personality",
        isCompressing = isFieldCompressing("role_editor_personality"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_world_settings_label),
        value = uiState.scenario,
        onValueChange = onUpdateScenario,
        minLines = 4,
        maxLines = ROLE_EDITOR_LARGE_TEXT_MAX_LINES,
        testTag = "role_editor_scenario",
        helpTopic = RoleEditorHelpTopic.SCENARIO,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_scenario",
        isCompressing = isFieldCompressing("role_editor_scenario"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_opening_line_label),
        value = uiState.firstMessage,
        onValueChange = onUpdateFirstMessage,
        minLines = 3,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_first_message",
        helpTopic = RoleEditorHelpTopic.FIRST_MESSAGE,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_first_message",
        isCompressing = isFieldCompressing("role_editor_first_message"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_message_example_label),
        value = uiState.messageExample,
        onValueChange = onUpdateMessageExample,
        minLines = 8,
        maxLines = ROLE_EDITOR_XL_TEXT_MAX_LINES,
        testTag = "role_editor_message_example",
        helpTopic = RoleEditorHelpTopic.EXAMPLE_DIALOGUE,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_message_example",
        isCompressing = isFieldCompressing("role_editor_message_example"),
        onCompressField = onCompressField,
      )
    }
    roleEditorStatusItems(uiState)
  }
}

@Composable
private fun RoleEditorPromptPage(
  uiState: RoleEditorUiState,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  onUpdateSystemPrompt: (String) -> Unit,
  onUpdatePostHistoryInstructions: (String) -> Unit,
  onUpdateAlternateGreetingsText: (String) -> Unit,
  isFieldCompressing: (String) -> Boolean,
  onCompressField: (String, String, Int, String, (String) -> Unit) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_system_prompt_label),
        value = uiState.systemPrompt,
        onValueChange = onUpdateSystemPrompt,
        minLines = 6,
        maxLines = ROLE_EDITOR_XL_TEXT_MAX_LINES,
        testTag = "role_editor_system_prompt",
        helpTopic = RoleEditorHelpTopic.SYSTEM_PROMPT,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_system_prompt",
        isCompressing = isFieldCompressing("role_editor_system_prompt"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_post_history_instructions_label),
        value = uiState.postHistoryInstructions,
        onValueChange = onUpdatePostHistoryInstructions,
        minLines = 4,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_post_history",
        helpTopic = RoleEditorHelpTopic.POST_HISTORY,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_post_history",
        isCompressing = isFieldCompressing("role_editor_post_history"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_alternate_greetings_label),
        subtitle = stringResource(R.string.role_editor_alternate_greetings_hint),
        value = uiState.alternateGreetingsText,
        onValueChange = onUpdateAlternateGreetingsText,
        minLines = 4,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_alternate_greetings",
        helpTopic = RoleEditorHelpTopic.ALTERNATE_GREETINGS,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_alternate_greetings",
        isCompressing = isFieldCompressing("role_editor_alternate_greetings"),
        onCompressField = onCompressField,
      )
    }
    roleEditorStatusItems(uiState)
  }
}

@Composable
private fun RoleEditorLorebookPage(
  uiState: RoleEditorUiState,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  onUpdateCharacterBookName: (String) -> Unit,
  onUpdateCharacterBookDescription: (String) -> Unit,
  onUpdateCharacterBookScanDepth: (String) -> Unit,
  onUpdateCharacterBookTokenBudget: (String) -> Unit,
  onUpdateCharacterBookRecursiveScanning: (Boolean) -> Unit,
  onAddCharacterBookEntry: () -> Unit,
  onUpdateEntryId: (String, String) -> Unit,
  onUpdateEntryKeys: (String, String) -> Unit,
  onUpdateEntrySecondaryKeys: (String, String) -> Unit,
  onUpdateEntryComment: (String, String) -> Unit,
  onUpdateEntryContent: (String, String) -> Unit,
  onUpdateEntryConstant: (String, Boolean) -> Unit,
  onUpdateEntrySelective: (String, Boolean) -> Unit,
  onUpdateEntryInsertionOrder: (String, String) -> Unit,
  onUpdateEntryEnabled: (String, Boolean) -> Unit,
  onUpdateEntryPosition: (String, String) -> Unit,
  onUpdateEntryUseRegex: (String, Boolean) -> Unit,
  onRemoveEntry: (String) -> Unit,
  isFieldCompressing: (String) -> Boolean,
  onCompressField: (String, String, Int, String, (String) -> Unit) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_lorebook_name_label),
        value = uiState.characterBook.name,
        onValueChange = onUpdateCharacterBookName,
        minLines = 1,
        testTag = "role_editor_lorebook_name",
        helpTopic = RoleEditorHelpTopic.LOREBOOK_NAME,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_lorebook_name",
        isCompressing = isFieldCompressing("role_editor_lorebook_name"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_lorebook_description_label),
        value = uiState.characterBook.description,
        onValueChange = onUpdateCharacterBookDescription,
        minLines = 3,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_lorebook_description",
        helpTopic = RoleEditorHelpTopic.LOREBOOK_DESCRIPTION,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_lorebook_description",
        isCompressing = isFieldCompressing("role_editor_lorebook_description"),
        onCompressField = onCompressField,
      )
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.weight(1f)) {
          EditorTextCard(
            title = stringResource(R.string.role_editor_lorebook_scan_depth_label),
            value = uiState.characterBook.scanDepthText,
            onValueChange = onUpdateCharacterBookScanDepth,
            minLines = 1,
            testTag = "role_editor_lorebook_scan_depth",
            helpTopic = RoleEditorHelpTopic.LOREBOOK_SCAN_DEPTH,
            onShowHelp = onShowHelp,
          )
        }
        Box(modifier = Modifier.weight(1f)) {
          EditorTextCard(
            title = stringResource(R.string.role_editor_lorebook_token_budget_label),
            value = uiState.characterBook.tokenBudgetText,
            onValueChange = onUpdateCharacterBookTokenBudget,
            minLines = 1,
            testTag = "role_editor_lorebook_token_budget",
            helpTopic = RoleEditorHelpTopic.LOREBOOK_TOKEN_BUDGET,
            onShowHelp = onShowHelp,
          )
        }
      }
    }
    item {
      BooleanFieldCard(
        title = stringResource(R.string.role_editor_lorebook_recursive_label),
        checked = uiState.characterBook.recursiveScanning,
        onCheckedChange = onUpdateCharacterBookRecursiveScanning,
        helpTopic = RoleEditorHelpTopic.LOREBOOK_RECURSIVE,
        onShowHelp = onShowHelp,
      )
    }
    item {
      OutlinedButton(
        onClick = onAddCharacterBookEntry,
        modifier = Modifier.fillMaxWidth().testTag("role_editor_lorebook_add_entry"),
      ) {
        Text(stringResource(R.string.role_editor_lorebook_add_entry))
      }
    }
    uiState.characterBook.entries.forEach { entry ->
      item(key = entry.editorId) {
        LorebookEntryCard(
          entry = entry,
          onUpdateId = onUpdateEntryId,
          onUpdateKeys = onUpdateEntryKeys,
          onUpdateSecondaryKeys = onUpdateEntrySecondaryKeys,
          onUpdateComment = onUpdateEntryComment,
          onUpdateContent = onUpdateEntryContent,
          onUpdateConstant = onUpdateEntryConstant,
          onUpdateSelective = onUpdateEntrySelective,
          onUpdateInsertionOrder = onUpdateEntryInsertionOrder,
          onUpdateEnabled = onUpdateEntryEnabled,
          onUpdatePosition = onUpdateEntryPosition,
          onUpdateUseRegex = onUpdateEntryUseRegex,
          onRemove = onRemoveEntry,
          onShowHelp = onShowHelp,
          isFieldCompressing = isFieldCompressing,
          onCompressField = onCompressField,
        )
      }
    }
    roleEditorStatusItems(uiState)
  }
}

@Composable
private fun RoleEditorMetadataPage(
  uiState: RoleEditorUiState,
  downloadedModels: List<Model>,
  modelMenuExpanded: Boolean,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  onModelMenuExpandedChange: (Boolean) -> Unit,
  onUpdateCreator: (String) -> Unit,
  onUpdateCreatorNotes: (String) -> Unit,
  onUpdateCharacterVersion: (String) -> Unit,
  onUpdateTagsText: (String) -> Unit,
  onUpdateTalkativenessText: (String) -> Unit,
  onUpdateFav: (Boolean) -> Unit,
  onUpdateSafetyPolicy: (String) -> Unit,
  onUpdateDefaultModelId: (String?) -> Unit,
  isFieldCompressing: (String) -> Boolean,
  onCompressField: (String, String, Int, String, (String) -> Unit) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_creator_label),
        value = uiState.creator,
        onValueChange = onUpdateCreator,
        minLines = 1,
        testTag = "role_editor_creator",
        helpTopic = RoleEditorHelpTopic.CREATOR,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_creator",
        isCompressing = isFieldCompressing("role_editor_creator"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_creator_notes_label),
        value = uiState.creatorNotes,
        onValueChange = onUpdateCreatorNotes,
        minLines = 4,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_creator_notes",
        helpTopic = RoleEditorHelpTopic.CREATOR_NOTES,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_creator_notes",
        isCompressing = isFieldCompressing("role_editor_creator_notes"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_character_version_label),
        value = uiState.characterVersion,
        onValueChange = onUpdateCharacterVersion,
        minLines = 1,
        testTag = "role_editor_character_version",
        helpTopic = RoleEditorHelpTopic.CHARACTER_VERSION,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_character_version",
        isCompressing = isFieldCompressing("role_editor_character_version"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_tags_label),
        value = uiState.tagsText,
        onValueChange = onUpdateTagsText,
        minLines = 2,
        maxLines = 4,
        testTag = "role_editor_tags",
        helpTopic = RoleEditorHelpTopic.TAGS,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_tags",
        isCompressing = isFieldCompressing("role_editor_tags"),
        onCompressField = onCompressField,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_talkativeness_label),
        value = uiState.talkativenessText,
        onValueChange = onUpdateTalkativenessText,
        minLines = 1,
        testTag = "role_editor_talkativeness",
        helpTopic = RoleEditorHelpTopic.TALKATIVENESS,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_talkativeness",
        isCompressing = isFieldCompressing("role_editor_talkativeness"),
        onCompressField = onCompressField,
      )
    }
    item {
      BooleanFieldCard(
        title = stringResource(R.string.role_editor_favorite_label),
        checked = uiState.fav,
        onCheckedChange = onUpdateFav,
        helpTopic = RoleEditorHelpTopic.FAVORITE,
        onShowHelp = onShowHelp,
      )
    }
    item {
      EditorTextCard(
        title = stringResource(R.string.role_editor_safety_policy_label),
        value = uiState.safetyPolicy,
        onValueChange = onUpdateSafetyPolicy,
        minLines = 3,
        maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
        testTag = "role_editor_safety_policy",
        helpTopic = RoleEditorHelpTopic.SAFETY_POLICY,
        onShowHelp = onShowHelp,
        compressionFieldKey = "role_editor_safety_policy",
        isCompressing = isFieldCompressing("role_editor_safety_policy"),
        onCompressField = onCompressField,
      )
    }
    item {
      AppEditorCard {
        AppEditorSectionHeader(
          title = stringResource(R.string.role_editor_default_model_label),
          onShowHelp = {
            Log.d(TAG, "Role editor help opened topic=${RoleEditorHelpTopic.DEFAULT_MODEL}")
            onShowHelp(RoleEditorHelpTopic.DEFAULT_MODEL)
          },
        )
        Box {
          OutlinedButton(onClick = { onModelMenuExpandedChange(true) }) {
            Text(uiState.defaultModelId ?: stringResource(R.string.role_editor_no_default_model))
          }
          DropdownMenu(
            expanded = modelMenuExpanded,
            onDismissRequest = { onModelMenuExpandedChange(false) },
          ) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.role_editor_no_default_model)) },
              onClick = {
                onModelMenuExpandedChange(false)
                onUpdateDefaultModelId(null)
              },
            )
            downloadedModels.forEach { model ->
              DropdownMenuItem(
                text = { Text(model.displayName.ifEmpty { model.name }) },
                onClick = {
                  onModelMenuExpandedChange(false)
                  onUpdateDefaultModelId(model.name)
                },
              )
            }
          }
        }
      }
    }
    roleEditorStatusItems(uiState)
  }
}

@Composable
private fun RoleEditorMediaPage(
  uiState: RoleEditorUiState,
  onPickAvatar: () -> Unit,
  onClearAvatar: () -> Unit,
  onAddGallery: () -> Unit,
  onRenameGalleryAsset: (String, String) -> Unit,
  onUpdateGalleryUsage: (String, RoleMediaUsage) -> Unit,
  onSetGalleryAsAvatar: (String) -> Unit,
  onRemoveGalleryAsset: (String) -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      RoleEditorMediaSection(
        avatarUri = uiState.avatarUri,
        avatarSource = uiState.avatarSource,
        galleryAssets = uiState.galleryAssets,
        importedFromStPng = uiState.importedFromStPng,
        showAvatarSection = true,
        showGallerySection = true,
        onPickAvatar = onPickAvatar,
        onClearAvatar = onClearAvatar,
        onAddGallery = onAddGallery,
        onRenameGalleryAsset = onRenameGalleryAsset,
        onUpdateGalleryUsage = onUpdateGalleryUsage,
        onSetGalleryAsAvatar = onSetGalleryAsAvatar,
        onRemoveGalleryAsset = onRemoveGalleryAsset,
      )
    }
    roleEditorStatusItems(uiState)
  }
}

@Composable
private fun RoleEditorInteropPage(
  uiState: RoleEditorUiState,
  context: android.content.Context,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  onImportStCard: () -> Unit,
  onExportStJson: () -> Unit,
  onExportStPng: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      ReadonlyInfoCard(
        title = stringResource(R.string.role_editor_interop_title),
        helpTopic = RoleEditorHelpTopic.INTEROP,
        onShowHelp = onShowHelp,
        lines =
          listOf(
            stringResource(R.string.role_editor_interop_source_format, uiState.sourceFormat.name),
            stringResource(R.string.role_editor_interop_spec, uiState.sourceSpec ?: "-"),
            stringResource(R.string.role_editor_interop_spec_version, uiState.sourceSpecVersion ?: "-"),
          ) + uiState.compatibilityWarnings.map { warning ->
            context.getString(R.string.role_editor_interop_warning, warning)
          },
      )
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = onImportStCard,
          modifier = Modifier.fillMaxWidth().testTag("role_editor_import_st_json"),
        ) {
          Text(stringResource(R.string.role_editor_import_st_card))
        }
        OutlinedButton(
          onClick = onExportStJson,
          modifier = Modifier.fillMaxWidth().testTag("role_editor_export_st_json"),
        ) {
          Text(stringResource(R.string.role_editor_export_st_json))
        }
        OutlinedButton(
          onClick = onExportStPng,
          modifier = Modifier.fillMaxWidth().testTag("role_editor_export_st_png"),
        ) {
          Text(stringResource(R.string.role_editor_export_st_png))
        }
      }
    }
    roleEditorStatusItems(uiState)
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.roleEditorStatusItems(uiState: RoleEditorUiState) {
  uiState.statusMessage?.let { statusMessage ->
    item { StatusText(statusMessage, isError = false) }
  }
  uiState.errorMessage?.let { errorMessage ->
    item { StatusText(errorMessage, isError = true) }
  }
}

@Composable
private fun EditorTextCard(
  title: String,
  value: String,
  onValueChange: (String) -> Unit,
  minLines: Int,
  maxLines: Int = minLines,
  testTag: String,
  subtitle: String? = null,
  required: Boolean = false,
  helpTopic: RoleEditorHelpTopic? = null,
  onShowHelp: ((RoleEditorHelpTopic) -> Unit)? = null,
  compressionFieldKey: String? = null,
  isCompressing: Boolean = false,
  onCompressField: ((String, String, Int, String, (String) -> Unit) -> Unit)? = null,
) {
  val fieldSpec = roleEditorTextFieldSpec(helpTopic)
  val maxChars = fieldSpec?.maxChars
  AppEditorCard {
    AppEditorSectionHeader(
      title = title,
      required = required,
      supportingText = subtitle,
      onShowHelp =
        if (helpTopic != null && onShowHelp != null) {
          {
            Log.d(TAG, "Role editor help opened topic=$helpTopic")
            onShowHelp(helpTopic)
          }
        } else {
          null
        },
      actions =
        if (
          compressionFieldKey != null &&
            onCompressField != null &&
            fieldSpec?.supportsAiCompress == true &&
            maxChars != null
        ) {
          val canCompress = value.length > maxChars
          {
            TextButton(
              onClick = {
                onCompressField(
                  compressionFieldKey,
                  title,
                  maxChars,
                  value,
                  onValueChange,
                )
              },
              enabled = !isCompressing && value.isNotBlank() && canCompress,
            ) {
              Text(
                if (isCompressing) {
                  stringResource(R.string.role_editor_ai_compress_running)
                } else {
                  stringResource(R.string.role_editor_ai_compress_action)
                },
              )
            }
          }
        } else {
          null
        },
    )
    RoleEditorOutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier =
        Modifier
          .fillMaxWidth()
          .then(roleEditorTextFieldHeightModifier(maxLines))
          .testTag(testTag),
      minLines = minLines,
      maxLines = maxLines,
      fieldSpec = fieldSpec,
      helpTopic = helpTopic,
      enabled = !isCompressing,
    )
  }
}

@Composable
private fun BooleanFieldCard(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  helpTopic: RoleEditorHelpTopic? = null,
  onShowHelp: ((RoleEditorHelpTopic) -> Unit)? = null,
) {
  AppEditorCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      AppEditorSectionHeader(
        title = title,
        onShowHelp =
          if (helpTopic != null && onShowHelp != null) {
            {
              Log.d(TAG, "Role editor help opened topic=$helpTopic")
              onShowHelp(helpTopic)
            }
          } else {
            null
          },
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.size(12.dp))
      Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun ReadonlyInfoCard(
  title: String,
  lines: List<String>,
  helpTopic: RoleEditorHelpTopic? = null,
  onShowHelp: ((RoleEditorHelpTopic) -> Unit)? = null,
) {
  AppEditorCard {
    AppEditorSectionHeader(
      title = title,
      onShowHelp =
        if (helpTopic != null && onShowHelp != null) {
          {
            Log.d(TAG, "Role editor help opened topic=$helpTopic")
            onShowHelp(helpTopic)
          }
        } else {
          null
        },
    )
    lines.forEach { line ->
      Text(line, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun StatusText(
  message: String,
  isError: Boolean,
) {
  AppEditorStatusCard(
    message = message,
    isError = isError,
  )
}

@Composable
private fun LorebookEntryCard(
  entry: RoleEditorCharacterBookEntryState,
  onUpdateId: (String, String) -> Unit,
  onUpdateKeys: (String, String) -> Unit,
  onUpdateSecondaryKeys: (String, String) -> Unit,
  onUpdateComment: (String, String) -> Unit,
  onUpdateContent: (String, String) -> Unit,
  onUpdateConstant: (String, Boolean) -> Unit,
  onUpdateSelective: (String, Boolean) -> Unit,
  onUpdateInsertionOrder: (String, String) -> Unit,
  onUpdateEnabled: (String, Boolean) -> Unit,
  onUpdatePosition: (String, String) -> Unit,
  onUpdateUseRegex: (String, Boolean) -> Unit,
  onRemove: (String) -> Unit,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  isFieldCompressing: (String) -> Boolean,
  onCompressField: (String, String, Int, String, (String) -> Unit) -> Unit,
) {
  AppEditorCard {
    AppEditorSectionHeader(
      title = stringResource(R.string.role_editor_lorebook_entry_title),
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_id_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_ID,
      onShowHelp = onShowHelp,
      value = entry.idText,
      onValueChange = { onUpdateId(entry.editorId, it) },
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_keys_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_KEYS,
      onShowHelp = onShowHelp,
      value = entry.keysText,
      onValueChange = { onUpdateKeys(entry.editorId, it) },
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_secondary_keys_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_SECONDARY_KEYS,
      onShowHelp = onShowHelp,
      value = entry.secondaryKeysText,
      onValueChange = { onUpdateSecondaryKeys(entry.editorId, it) },
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_comment_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_COMMENT,
      onShowHelp = onShowHelp,
      value = entry.comment,
      onValueChange = { onUpdateComment(entry.editorId, it) },
      minLines = 2,
      maxLines = ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES,
      compressionFieldKey = "role_editor_lore_entry_comment_${entry.editorId}",
      isCompressing = isFieldCompressing("role_editor_lore_entry_comment_${entry.editorId}"),
      onCompressField = onCompressField,
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_content_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_CONTENT,
      onShowHelp = onShowHelp,
      value = entry.content,
      onValueChange = { onUpdateContent(entry.editorId, it) },
      minLines = 4,
      maxLines = ROLE_EDITOR_LARGE_TEXT_MAX_LINES,
      compressionFieldKey = "role_editor_lore_entry_content_${entry.editorId}",
      isCompressing = isFieldCompressing("role_editor_lore_entry_content_${entry.editorId}"),
      onCompressField = onCompressField,
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_order_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_ORDER,
      onShowHelp = onShowHelp,
      value = entry.insertionOrderText,
      onValueChange = { onUpdateInsertionOrder(entry.editorId, it) },
    )
    LabeledTextField(
      title = stringResource(R.string.role_editor_lorebook_entry_position_label),
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_POSITION,
      onShowHelp = onShowHelp,
      value = entry.position,
      onValueChange = { onUpdatePosition(entry.editorId, it) },
    )
    BooleanFieldCard(
      title = stringResource(R.string.role_editor_lorebook_entry_enabled_label),
      checked = entry.enabled,
      onCheckedChange = { onUpdateEnabled(entry.editorId, it) },
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_ENABLED,
      onShowHelp = onShowHelp,
    )
    BooleanFieldCard(
      title = stringResource(R.string.role_editor_lorebook_entry_constant_label),
      checked = entry.constant,
      onCheckedChange = { onUpdateConstant(entry.editorId, it) },
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_CONSTANT,
      onShowHelp = onShowHelp,
    )
    BooleanFieldCard(
      title = stringResource(R.string.role_editor_lorebook_entry_selective_label),
      checked = entry.selective,
      onCheckedChange = { onUpdateSelective(entry.editorId, it) },
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_SELECTIVE,
      onShowHelp = onShowHelp,
    )
    BooleanFieldCard(
      title = stringResource(R.string.role_editor_lorebook_entry_regex_label),
      checked = entry.useRegex,
      onCheckedChange = { onUpdateUseRegex(entry.editorId, it) },
      helpTopic = RoleEditorHelpTopic.LORE_ENTRY_REGEX,
      onShowHelp = onShowHelp,
    )
    OutlinedButton(onClick = { onRemove(entry.editorId) }, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.role_editor_lorebook_remove_entry))
    }
  }
}

private fun editorTextFieldMaxHeight(maxLines: Int) =
  (ROLE_EDITOR_TEXTFIELD_BASE_HEIGHT_DP + ((maxLines - 1).coerceAtLeast(0) * ROLE_EDITOR_TEXTFIELD_LINE_STEP_DP)).dp

private fun roleEditorTextFieldHeightModifier(maxLines: Int): Modifier =
  if (maxLines <= 1) {
    Modifier.requiredHeightIn(min = ROLE_EDITOR_SINGLE_LINE_TEXTFIELD_MIN_HEIGHT_DP.dp)
  } else {
    Modifier.heightIn(max = editorTextFieldMaxHeight(maxLines))
  }

@Composable
private fun RequiredFieldsHintCard() {
  AppEditorInfoCard(
    message = stringResource(R.string.role_editor_required_hint),
  )
}

@Composable
private fun LabeledTextField(
  title: String,
  helpTopic: RoleEditorHelpTopic,
  onShowHelp: (RoleEditorHelpTopic) -> Unit,
  value: String,
  onValueChange: (String) -> Unit,
  minLines: Int = 1,
  maxLines: Int = minLines,
  compressionFieldKey: String? = null,
  isCompressing: Boolean = false,
  onCompressField: ((String, String, Int, String, (String) -> Unit) -> Unit)? = null,
) {
  val fieldSpec = roleEditorTextFieldSpec(helpTopic)
  val maxChars = fieldSpec?.maxChars
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    AppEditorSectionHeader(
      title = title,
      onShowHelp = {
        Log.d(TAG, "Role editor help opened topic=$helpTopic")
        onShowHelp(helpTopic)
      },
      actions =
        if (
          compressionFieldKey != null &&
            onCompressField != null &&
            fieldSpec?.supportsAiCompress == true &&
            maxChars != null
        ) {
          val canCompress = value.length > maxChars
          {
            TextButton(
              onClick = {
                onCompressField(
                  compressionFieldKey,
                  title,
                  maxChars,
                  value,
                  onValueChange,
                )
              },
              enabled = !isCompressing && value.isNotBlank() && canCompress,
            ) {
              Text(
                if (isCompressing) {
                  stringResource(R.string.role_editor_ai_compress_running)
                } else {
                  stringResource(R.string.role_editor_ai_compress_action)
                },
              )
            }
          }
        } else {
          null
        },
    )
    RoleEditorOutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth().heightIn(max = editorTextFieldMaxHeight(maxLines)),
      minLines = minLines,
      maxLines = maxLines,
      fieldSpec = fieldSpec,
      helpTopic = helpTopic,
      enabled = !isCompressing,
    )
  }
}

@Composable
private fun RoleEditorOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  minLines: Int = 1,
  maxLines: Int = minLines,
  fieldSpec: RoleEditorTextFieldSpec? = null,
  helpTopic: RoleEditorHelpTopic? = null,
  enabled: Boolean = true,
) {
  val currentCount = value.length
  val maxChars = fieldSpec?.maxChars
  val isOverLimit = maxChars != null && currentCount > maxChars
  LaunchedEffect(isOverLimit, helpTopic) {
    if (isOverLimit && helpTopic != null) {
      Log.w(TAG, "Role editor field exceeds budget topic=$helpTopic count=$currentCount limit=$maxChars")
    }
  }
  AppOutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    minLines = minLines,
    maxLines = maxLines,
    singleLine = maxLines == 1,
    isError = isOverLimit,
    textStyle =
      MaterialTheme.typography.bodyLarge.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = true),
      ),
    supportingText = {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
          text =
            if (maxChars != null) {
              stringResource(R.string.role_editor_character_count_with_limit, currentCount, maxChars)
            } else {
              stringResource(R.string.role_editor_character_count_without_limit, currentCount)
            },
          style = MaterialTheme.typography.labelSmall,
          color =
            if (isOverLimit) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    },
  )
}

@Composable
private fun RoleEditorHelpDialog(
  topic: RoleEditorHelpTopic,
  onDismiss: () -> Unit,
) {
  val paragraphs = stringResource(topic.bodyRes).split("\n\n")
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(topic.titleRes)) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(paragraphs) { paragraph ->
          Text(paragraph, style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = {
      FilledTonalButton(onClick = onDismiss) {
        Text(stringResource(R.string.ok))
      }
    },
  )
}

private fun buildRoleEditorCompressionPrompt(
  fieldTitle: String,
  maxChars: Int,
  content: String,
  attempt: Int,
  previousLength: Int? = null,
): String {
  val retryInstructions =
    if (attempt <= 1) {
      ""
    } else {
      """
      Previous rewrite was still too long${previousLength?.let { " ($it characters)" } ?: ""}.
      Compress much more aggressively this time.
      It is acceptable to drop secondary details as long as the core roleplay intent remains.
      This retry must be under $maxChars characters.

      """.trimIndent()
    }
  val hardConstraint =
    """
    HARD CONSTRAINT:
    Output length MUST be between 1 and $maxChars characters (inclusive upper bound), count every character including spaces and line breaks.
    Return only rewritten text. No preface, no suffix, no markdown, no quotes.
    """.trimIndent()
  return """
    You are helping edit a role card field.
    Rewrite the field below and compress it as much as needed.
    Preserve the original meaning, tone, and roleplay intent.
    Keep useful line breaks or list structure when they matter.
    Remove redundancy first. If needed, aggressively shorten until the limit is satisfied.
    $hardConstraint
    $retryInstructions
    Do not use any text that is not part of the rewritten field.

    Field: $fieldTitle
    Target max characters: $maxChars

    Original content:
    $content
  """.trimIndent()
}

private data class RoleEditorCompressionResult(
  val text: String,
  val attempts: Int,
)

private suspend fun ensureRoleEditorCompressionModelReady(
  context: android.content.Context,
  model: Model,
  coroutineScope: CoroutineScope,
) {
  if (model.instance != null) {
    return
  }

  suspendCancellableCoroutine { continuation ->
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = { error ->
        if (!continuation.isActive) {
          return@initialize
        }
        if (model.instance != null) {
          continuation.resume(Unit)
        } else {
          continuation.resumeWithException(
            IllegalStateException(error.ifBlank { "Failed to initialize editor assistant model." }),
          )
        }
      },
      coroutineScope = coroutineScope,
    )
  }

  withTimeout(ROLE_EDITOR_COMPRESSION_INIT_TIMEOUT_MS) {
    while (model.instance == null) {
      delay(100)
    }
  }
}

private suspend fun runRoleEditorCompressionInference(
  model: Model,
  input: String,
  coroutineScope: CoroutineScope,
): String {
  return suspendCancellableCoroutine { continuation ->
    var response = ""
    model.runtimeHelper.runInference(
      model = model,
      input = input,
      resultListener = { partialResult, done, _ ->
        response = processLlmResponse(response = "$response$partialResult")
        if (done && continuation.isActive) {
          continuation.resume(response)
        }
      },
      cleanUpListener = {},
      onError = { message ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(message))
        }
      },
      coroutineScope = coroutineScope,
    )
    continuation.invokeOnCancellation {
      model.runtimeHelper.stopResponse(model)
    }
  }
}

private suspend fun compressRoleEditorFieldToTarget(
  model: Model,
  fieldTitle: String,
  maxChars: Int,
  originalContent: String,
  coroutineScope: CoroutineScope,
): RoleEditorCompressionResult {
  var currentText = originalContent
  var attempt = 0
  while (attempt < ROLE_EDITOR_COMPRESSION_MAX_ATTEMPTS) {
    attempt += 1
    model.runtimeHelper.resetConversation(model = model)
    val nextText =
      runRoleEditorCompressionInference(
        model = model,
        input =
          buildRoleEditorCompressionPrompt(
            fieldTitle = fieldTitle,
            maxChars = maxChars,
            content = currentText,
            attempt = attempt,
            previousLength = currentText.length.takeIf { attempt > 1 },
          ),
        coroutineScope = coroutineScope,
      ).trim()
    if (nextText.isBlank()) {
      return RoleEditorCompressionResult(text = nextText, attempts = attempt)
    }
    currentText = nextText
    if (currentText.length <= maxChars) {
      return RoleEditorCompressionResult(text = currentText, attempts = attempt)
    }
  }
  return RoleEditorCompressionResult(text = currentText, attempts = attempt)
}

private fun takeReadPermission(context: android.content.Context, uri: Uri) {
  runCatching {
    context.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
  }
}
