package selfgemma.talk.feature.roleplay.roles

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorInfoCard
import selfgemma.talk.ui.common.AppEditorSectionHeader
import selfgemma.talk.ui.common.AppEditorStatusCard
import selfgemma.talk.ui.common.AppOutlinedTextField

private const val TAG = "RoleEditorComponents"

internal const val ROLE_EDITOR_MEDIUM_TEXT_MAX_LINES = 8
internal const val ROLE_EDITOR_LARGE_TEXT_MAX_LINES = 12
internal const val ROLE_EDITOR_XL_TEXT_MAX_LINES = 14
private const val ROLE_EDITOR_SINGLE_LINE_TEXTFIELD_MIN_HEIGHT_DP = 80
private const val ROLE_EDITOR_TEXTFIELD_BASE_HEIGHT_DP = 64
private const val ROLE_EDITOR_TEXTFIELD_LINE_STEP_DP = 24

internal data class RoleEditorTextFieldSpec(
  val maxChars: Int? = null,
  val supportsAiCompress: Boolean = false,
)

internal enum class RoleEditorHelpTopic(val titleRes: Int, val bodyRes: Int) {
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

internal fun roleEditorTextFieldSpec(topic: RoleEditorHelpTopic?): RoleEditorTextFieldSpec? =
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

private fun editorTextFieldMaxHeight(maxLines: Int) =
  (ROLE_EDITOR_TEXTFIELD_BASE_HEIGHT_DP + ((maxLines - 1).coerceAtLeast(0) * ROLE_EDITOR_TEXTFIELD_LINE_STEP_DP)).dp

internal fun roleEditorTextFieldHeightModifier(maxLines: Int): Modifier =
  if (maxLines <= 1) {
    Modifier.requiredHeightIn(min = ROLE_EDITOR_SINGLE_LINE_TEXTFIELD_MIN_HEIGHT_DP.dp)
  } else {
    Modifier.heightIn(max = editorTextFieldMaxHeight(maxLines))
  }

@Composable
internal fun EditorTextCard(
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
internal fun BooleanFieldCard(
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
internal fun ReadonlyInfoCard(
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
internal fun StatusText(
  message: String,
  isError: Boolean,
) {
  AppEditorStatusCard(
    message = message,
    isError = isError,
  )
}

@Composable
internal fun RequiredFieldsHintCard() {
  AppEditorInfoCard(
    message = stringResource(R.string.role_editor_required_hint),
  )
}

@Composable
internal fun LabeledTextField(
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
internal fun RoleEditorOutlinedTextField(
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
internal fun RoleEditorHelpDialog(
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
