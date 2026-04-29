package selfgemma.talk.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.ui.common.tos.AppTosDialog
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

@Composable
internal fun HomeScreenOverlayDialogs(
  showTosDialog: Boolean,
  onTosAccepted: () -> Unit,
  showSettingsDialog: Boolean,
  onSettingsDismissed: () -> Unit,
  modelManagerViewModel: ModelManagerViewModel,
  loadingModelAllowlistError: String,
) {
  // Show TOS dialog for users to accept.
  if (showTosDialog) {
    AppTosDialog(onTosAccepted = onTosAccepted)
  }

  // Settings dialog.
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = onSettingsDismissed,
    )
  }

  if (loadingModelAllowlistError.isNotEmpty()) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = { Text(loadingModelAllowlistError) },
      text = { Text("Please check your internet connection and try again later.") },
      onDismissRequest = { modelManagerViewModel.loadModelAllowlist() },
      confirmButton = {
        TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) { Text("Retry") }
      },
      dismissButton = {
        TextButton(onClick = { modelManagerViewModel.clearLoadModelAllowlistError() }) {
          Text("Cancel")
        }
      },
    )
  }
}
