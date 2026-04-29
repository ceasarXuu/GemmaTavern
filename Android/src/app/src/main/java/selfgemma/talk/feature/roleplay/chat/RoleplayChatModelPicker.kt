package selfgemma.talk.feature.roleplay.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.data.Model

@Composable
internal fun ChatModelPickerDialog(
  downloadedModels: List<Model>,
  activeModelName: String?,
  onModelSelected: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.chat_select_model_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        downloadedModels.forEach { model ->
          val isSelected = model.name == activeModelName
          ListItem(
            headlineContent = {
              Text(
                text = model.displayName.ifEmpty { model.name },
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
              )
            },
            supportingContent = {
              if (isSelected) {
                Text(stringResource(R.string.chat_current_model))
              }
            },
            trailingContent = {
              RadioButton(
                selected = isSelected,
                onClick = null,
              )
            },
            colors =
              ListItemDefaults.colors(
                containerColor =
                  if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                  }
              ),
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.medium)
              .selectable(
                selected = isSelected,
                role = Role.RadioButton,
              ) {
                onModelSelected(model.name)
              }
          )
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}
