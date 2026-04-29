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

package selfgemma.talk.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.ui.theme.customColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Composable for the "Scripts" tab content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScriptsTabContent(
  scope: CoroutineScope,
  scriptContents: Map<String, String>,
  selectedScript: String?,
  onScriptSelected: (String?) -> Unit,
  onAddDefaultScript: () -> Unit,
  onScriptChanged: (name: String, content: String) -> Unit,
  onScriptAdded: (scriptName: String) -> Unit,
  onScriptDeleted: (scriptName: String) -> Unit,
  curDescription: String,
  requirements: String,
  onRequirementsChange: (String) -> Unit,
  inputData: String,
  onInputDataChange: (String) -> Unit,
  outputData: String,
  onOutputDataChange: (String) -> Unit,
  snackbarHostState: SnackbarHostState,
  modifier: Modifier = Modifier,
) {
  val scripts = scriptContents.keys.toList()
  var scriptContent by remember { mutableStateOf(scriptContents[selectedScript] ?: "") }
  var showAddScriptDialog by remember { mutableStateOf(false) }
  var showDeleteConfirmation by remember { mutableStateOf(false) }
  var showGenerateLlmPromptBottomSheet by remember { mutableStateOf(false) }
  var newScriptName by remember { mutableStateOf("") }
  val clipboard = LocalClipboard.current

  LaunchedEffect(selectedScript, scriptContents.toMap()) {
    scriptContent = scriptContents[selectedScript] ?: ""
  }

  if (scriptContents.isEmpty()) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      FilledTonalButton(onClick = onAddDefaultScript) {
        Text(stringResource(R.string.add_default_script))
      }
    }
  } else {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
          expanded = expanded,
          onExpandedChange = { expanded = !expanded },
          modifier = Modifier.weight(1f),
        ) {
          OutlinedTextField(
            value = selectedScript ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.select_script)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
              Modifier.menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
          )
          ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (script in scripts) {
              DropdownMenuItem(
                text = { Text(script) },
                onClick = {
                  onScriptSelected(script)
                  expanded = false
                },
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = { showAddScriptDialog = true }) {
          Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cd_add_icon))
        }

        IconButton(onClick = { showDeleteConfirmation = true }) {
          Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete_icon))
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
          onClick = { showGenerateLlmPromptBottomSheet = true },
          modifier = Modifier.height(32.dp),
          contentPadding = BUTTON_CONTENT_PADDING,
        ) {
          Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.generate_llm_prompt_button_label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledTonalButton(
          onClick = {
            scope.launch {
              val clipEntry = clipboard.getClipEntry()
              val pastedText = clipEntry?.clipData?.getItemAt(0)?.text?.toString()

              if (pastedText != null) {
                selectedScript?.let { curSelectedScript ->
                  onScriptChanged(curSelectedScript, pastedText)
                }
              }
            }
          },
          modifier = Modifier.height(32.dp),
          contentPadding = BUTTON_CONTENT_PADDING,
        ) {
          Icon(
            Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.paste_from_clipboard),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      CursorTrackingTextField(
        minLines = 16,
        initialValue = scriptContent,
        onValueChange = { newContent ->
          selectedScript?.let { curSelectedScript ->
            onScriptChanged(curSelectedScript, newContent)
          }
        },
        monoFont = true,
      )
    }
  }

  if (showAddScriptDialog) {
    AlertDialog(
      onDismissRequest = { showAddScriptDialog = false },
      title = { Text(stringResource(R.string.add_script)) },
      text = {
        OutlinedTextField(
          value = newScriptName,
          onValueChange = { newScriptName = it },
          label = { Text(stringResource(R.string.script_name)) },
          isError = scriptContents.containsKey(newScriptName),
          supportingText = {
            if (scriptContents.containsKey(newScriptName)) {
              Text(
                stringResource(R.string.duplicated_script_name),
                color = MaterialTheme.colorScheme.error,
              )
            }
          },
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val trimmedName = newScriptName.trim()
            onScriptAdded(trimmedName)
            newScriptName = ""
            showAddScriptDialog = false
          },
          enabled = !scriptContents.containsKey(newScriptName) && newScriptName.isNotBlank(),
        ) {
          Text(stringResource(R.string.add))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddScriptDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (showDeleteConfirmation) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text(stringResource(R.string.delete_script_dialog_title)) },
      text = { Text("Are you sure you want to delete '$selectedScript'?") },
      confirmButton = {
        Button(
          onClick = {
            selectedScript?.let { curSelectedScript -> onScriptDeleted(curSelectedScript) }
            showDeleteConfirmation = false
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.customColors.errorTextColor,
              contentColor = Color.White,
            ),
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmation = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (showGenerateLlmPromptBottomSheet) {
    val promptCopiedMessage = stringResource(R.string.prompt_copied_message)
    GenerateLlmPromptBottomSheet(
      requirements = requirements,
      curDescription = curDescription,
      onRequirementsChange = onRequirementsChange,
      inputData = inputData,
      onInputDataChange = onInputDataChange,
      outputData = outputData,
      onOutputDataChange = onOutputDataChange,
      onDismiss = { showGenerateLlmPromptBottomSheet = false },
      onLlmPromptGenerated = {
        scope.launch {
          snackbarHostState.showSnackbar(
            message = promptCopiedMessage,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
          )
        }
      },
    )
  }
}
