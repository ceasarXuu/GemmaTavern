package selfgemma.talk.feature.roleplay.roles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorSectionHeader

@Composable
internal fun LorebookEntryCard(
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
