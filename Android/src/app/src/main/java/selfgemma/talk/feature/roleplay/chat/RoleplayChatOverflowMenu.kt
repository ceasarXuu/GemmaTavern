package selfgemma.talk.feature.roleplay.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.ui.common.TopBarOverflowMenuButton

@Composable
internal fun RoleplayChatOverflowMenu(
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  internalDiagnosticsEnabled: Boolean,
  onShowModelPicker: () -> Unit,
  onOpenModelLibrary: () -> Unit,
  onShowContinuityDebug: () -> Unit,
  onExportDebugBundle: () -> Unit,
) {
  TopBarOverflowMenuButton(
    expanded = expanded,
    onExpandedChange = onExpandedChange,
  ) {
    DropdownMenuItem(
      text = { Text(stringResource(R.string.chat_switch_model)) },
      onClick = {
        onExpandedChange(false)
        onShowModelPicker()
      },
      leadingIcon = {
        Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
      },
    )
    DropdownMenuItem(
      text = { Text(stringResource(R.string.chat_open_model_library_menu)) },
      onClick = {
        onExpandedChange(false)
        onOpenModelLibrary()
      },
      leadingIcon = {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
      },
    )
    if (internalDiagnosticsEnabled) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_continuity_debug_action)) },
        onClick = {
          onExpandedChange(false)
          onShowContinuityDebug()
        },
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.chat_export_debug_bundle_action)) },
        onClick = {
          onExpandedChange(false)
          onExportDebugBundle()
        },
        leadingIcon = {
          Icon(Icons.Rounded.BugReport, contentDescription = null)
        },
      )
    }
  }
}
