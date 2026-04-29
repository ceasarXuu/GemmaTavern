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

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import selfgemma.talk.R
import selfgemma.talk.proto.Skill
import selfgemma.talk.ui.theme.customColors

@Composable
internal fun SkillManagerDialogs(
  agentTools: AgentTools,
  skillManagerViewModel: SkillManagerViewModel,
  uiState: SkillManagerUiState,
  listState: LazyListState,
  showDeleteSkillDialog: Boolean,
  inMultiSelectMode: Boolean,
  selectedCustomSkillNames: List<String>,
  skillToDeleteName: String,
  onDeleteDialogDismiss: () -> Unit,
  onDeleteConfirmed: () -> Unit,
  showAddSkillFromUrlDialog: Boolean,
  onAddSkillFromUrlDismiss: () -> Unit,
  showAddSkillFromFeaturedListBottomSheet: Boolean,
  onAddSkillFromFeaturedListDismiss: () -> Unit,
  showAddSkillFromLocalImportDialog: Boolean,
  onAddSkillFromLocalImportDismiss: () -> Unit,
  showAddOrEditSkillBottomSheet: Boolean,
  skillToEditIndex: Int,
  onAddOrEditDismiss: () -> Unit,
  onAddOrEditSuccess: () -> Unit,
  showAddSkillOptionsSheet: Boolean,
  onAddSkillOptionsDismiss: () -> Unit,
  onAddSkillOptionSelected: (AddSkillOption) -> Unit,
  showJsSkillTesterBottomSheet: Boolean,
  skillToTest: Skill?,
  onJsSkillTesterDismiss: () -> Unit,
  showSecretEditorDialog: Boolean,
  onSecretEditorDismiss: () -> Unit,
  showDisclaimerDialog: Boolean,
  onDisclaimerDismiss: () -> Unit,
  onDisclaimerConfirm: () -> Unit,
) {
  val scope = rememberCoroutineScope()

  if (showDeleteSkillDialog) {
    AlertDialog(
      onDismissRequest = onDeleteDialogDismiss,
      title = {
        Text(
          if (inMultiSelectMode) stringResource(R.string.delete_selected_skills_title)
          else stringResource(R.string.delete_skill_dialog_title)
        )
      },
      text = {
        Text(
          if (inMultiSelectMode)
            pluralStringResource(
              R.plurals.delete_selected_skills_content,
              selectedCustomSkillNames.size,
              selectedCustomSkillNames.size,
            )
          else stringResource(R.string.delete_skill_dialog_content)
        )
      },
      confirmButton = {
        Button(
          onClick = onDeleteConfirmed,
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
        OutlinedButton(onClick = onDeleteDialogDismiss) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }

  if (showAddSkillFromUrlDialog) {
    AddSkillFromUrlDialog(
      skillManagerViewModel = skillManagerViewModel,
      onDismissRequest = onAddSkillFromUrlDismiss,
      onSuccess = { scrollToBottomOfList(scope, listState) },
    )
  }

  if (showAddSkillFromFeaturedListBottomSheet) {
    AddSkillFromFeatureListBottomSheet(
      skillManagerViewModel = skillManagerViewModel,
      onDismiss = onAddSkillFromFeaturedListDismiss,
      onSkillAdded = { scrollToBottomOfList(scope, listState) },
    )
  }

  if (showAddSkillFromLocalImportDialog) {
    AddSkillFromLocalImportDialog(
      skillManagerViewModel = skillManagerViewModel,
      onDismissRequest = onAddSkillFromLocalImportDismiss,
      onSuccess = { scrollToBottomOfList(scope, listState) },
    )
  }

  if (showAddOrEditSkillBottomSheet) {
    AddOrEditSkillBottomSheet(
      skillManagerViewModel = skillManagerViewModel,
      skillIndex = if (skillToEditIndex != -1) skillToEditIndex else uiState.skills.size,
      onDismiss = onAddOrEditDismiss,
      onSuccess = onAddOrEditSuccess,
    )
  }

  if (showAddSkillOptionsSheet) {
    AddSkillOptionsBottomSheet(
      onDismiss = onAddSkillOptionsDismiss,
      onOptionSelected = onAddSkillOptionSelected,
    )
  }

  if (showJsSkillTesterBottomSheet) {
    skillToTest?.let { skill ->
      SkillTesterBottomSheet(
        agentTools = agentTools,
        skill = skill,
        onDismiss = onJsSkillTesterDismiss,
      )
    }
  }

  if (showSecretEditorDialog) {
    val skillState = uiState.skills.getOrNull(skillToEditIndex)
    skillState?.let {
      var curSecret by remember {
        mutableStateOf(
          skillManagerViewModel.dataStoreRepository.readSecret(
            getSkillSecretKey(skillName = it.skill.name)
          ) ?: ""
        )
      }
      SecretEditorDialog(
        title = stringResource(R.string.edit_secret),
        fieldLabel = skillState.skill.requireSecretDescription,
        value = curSecret,
        onValueChange = { curSecret = it },
        onDone = {
          skillManagerViewModel.dataStoreRepository.saveSecret(
            key = getSkillSecretKey(skillName = it.skill.name),
            value = curSecret,
          )
          onSecretEditorDismiss()
        },
        onDismiss = onSecretEditorDismiss,
      )
    }
  }

  if (showDisclaimerDialog) {
    AddSkillDisclaimerDialog(onDismiss = onDisclaimerDismiss, onConfirm = onDisclaimerConfirm)
  }
}
