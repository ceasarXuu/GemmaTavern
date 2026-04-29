package selfgemma.talk.feature.roleplay.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.ui.common.AppOutlinedTextField

@Composable
internal fun ConfirmDeletePersonaDialog(
  personaName: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.my_profile_delete_title)) },
    text = {
      Text(
        stringResource(
          R.string.my_profile_delete_content,
          personaName,
        ),
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(R.string.delete))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
internal fun CreatePersonaSlotDialog(
  slotId: String,
  onSlotIdChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onCreate: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("${stringResource(R.string.create)} ${stringResource(R.string.my_profile_avatar_slot_title)}")
    },
    text = {
      AppOutlinedTextField(
        value = slotId,
        onValueChange = onSlotIdChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.my_profile_avatar_slot_new_label)) },
      )
    },
    confirmButton = {
      TextButton(
        enabled = slotId.trim().isNotBlank(),
        onClick = onCreate,
      ) {
        Text(stringResource(R.string.create))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
internal fun PersonaHelpDialog(
  topic: PersonaHelpTopic,
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
