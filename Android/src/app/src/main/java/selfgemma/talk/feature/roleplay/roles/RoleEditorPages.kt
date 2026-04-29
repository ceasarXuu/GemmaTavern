package selfgemma.talk.feature.roleplay.roles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R

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

