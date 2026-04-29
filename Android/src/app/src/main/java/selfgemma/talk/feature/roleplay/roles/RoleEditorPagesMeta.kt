package selfgemma.talk.feature.roleplay.roles

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.RoleMediaUsage
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppEditorSectionHeader

private const val TAG = "RoleEditorPagesMeta"

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

internal fun LazyListScope.roleEditorStatusItems(uiState: RoleEditorUiState) {
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
