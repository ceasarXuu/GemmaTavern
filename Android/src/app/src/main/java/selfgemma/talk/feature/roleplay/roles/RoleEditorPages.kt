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

private const val TAG = "RoleEditorPages"

@Composable
internal fun RoleEditorCardPage(
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
internal fun RoleEditorPromptPage(
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
internal fun RoleEditorLorebookPage(
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
internal fun RoleEditorMetadataPage(
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
internal fun RoleEditorMediaPage(
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
internal fun RoleEditorInteropPage(
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

internal fun androidx.compose.foundation.lazy.LazyListScope.roleEditorStatusItems(uiState: RoleEditorUiState) {
  uiState.statusMessage?.let { statusMessage ->
    item { StatusText(statusMessage, isError = false) }
  }
  uiState.errorMessage?.let { errorMessage ->
    item { StatusText(errorMessage, isError = true) }
  }
}

internal fun takeReadPermission(context: android.content.Context, uri: Uri) {
  runCatching {
    context.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
  }
}

