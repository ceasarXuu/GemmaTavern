/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.modelmanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.TaskIcon

internal fun getFileName(context: Context, uri: Uri): String? {
  if (uri.scheme == "content") {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex != -1) {
          return cursor.getString(nameIndex)
        }
      }
    }
  } else if (uri.scheme == "file") {
    return uri.lastPathSegment
  }
  return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskSelectorBottomSheet(
  taskCandidates: List<Task>,
  sheetState: SheetState,
  onDismiss: () -> Unit,
  onTaskSelected: (Task) -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        stringResource(R.string.model_manager_select_task_title),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 8.dp).padding(start = 16.dp),
      )
      for (task in taskCandidates) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier =
            Modifier.fillMaxWidth()
              .clickable { onTaskSelected(task) }
              .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
          Text(
            task.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
          )
          TaskIcon(task = task, width = 40.dp)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportModelBottomSheet(
  sheetState: SheetState,
  onDismiss: () -> Unit,
  onImportFromLocalFile: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Text(
      "Import model",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
    )
    val cbImportFromLocalFile = stringResource(R.string.cd_import_model_from_local_file_button)
    Box(
      modifier =
        Modifier.clickable { onImportFromLocalFile() }
          .semantics {
            role = Role.Button
            contentDescription = cbImportFromLocalFile
          }
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null)
        Text("From local model file", modifier = Modifier.clearAndSetSemantics {})
      }
    }
  }
}

@Composable
internal fun EmptyImportedModelsState(bottomPaddingDp: Dp) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    modifier =
      Modifier.fillMaxSize()
        .padding(horizontal = 32.dp)
        .padding(bottom = bottomPaddingDp),
  ) {
    Text(
      text = stringResource(R.string.model_library_imported_empty_title),
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = stringResource(R.string.model_library_imported_empty_content),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun UnsupportedFileTypeAlertDialog(onDismiss: () -> Unit) {
  AlertDialog(
    icon = {
      Icon(
        Icons.Rounded.Error,
        contentDescription = stringResource(R.string.cd_error),
        tint = MaterialTheme.colorScheme.error,
      )
    },
    onDismissRequest = onDismiss,
    title = { Text("Unsupported file type") },
    text = { Text("Only \".task\" or \".litertlm\" file type is supported.") },
    confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
  )
}

@Composable
internal fun UnsupportedWebModelAlertDialog(onDismiss: () -> Unit) {
  AlertDialog(
    icon = {
      Icon(
        Icons.Rounded.Error,
        contentDescription = stringResource(R.string.cd_error),
        tint = MaterialTheme.colorScheme.error,
      )
    },
    onDismissRequest = onDismiss,
    title = { Text("Unsupported model type") },
    text = { Text("Looks like the model is a web-only model and is not supported by the app.") },
    confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
  )
}
