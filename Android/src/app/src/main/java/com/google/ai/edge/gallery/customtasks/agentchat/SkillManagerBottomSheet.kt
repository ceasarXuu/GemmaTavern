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

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import selfgemma.talk.AnalyticsEvent
import selfgemma.talk.R
import selfgemma.talk.common.clearFocusOnKeyboardDismiss
import selfgemma.talk.data.MAX_RECOMMENDED_SKILL_COUNT
import selfgemma.talk.firebaseAnalytics
import selfgemma.talk.proto.Skill
import selfgemma.talk.ui.common.FloatingBanner
import selfgemma.talk.ui.theme.customColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SkillManagerBottomSheet(
  agentTools: AgentTools,
  skillManagerViewModel: SkillManagerViewModel,
  onDismiss: (selectedSkillsChanged: Boolean) -> Unit,
) {
  val uiState by skillManagerViewModel.uiState.collectAsState()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var showAddSkillFromUrlDialog by remember { mutableStateOf(false) }
  var showAddSkillFromLocalImportDialog by remember { mutableStateOf(false) }
  var showAddSkillFromFeaturedListBottomSheet by remember { mutableStateOf(false) }
  var showAddOrEditSkillBottomSheet by remember { mutableStateOf(false) }
  var showAddSkillOptionsSheet by remember { mutableStateOf(false) }
  var showDeleteSkillDialog by remember { mutableStateOf(false) }
  var showJsSkillTesterBottomSheet by remember { mutableStateOf(false) }
  var showSecretEditorDialog by remember { mutableStateOf(false) }
  var showDisclaimerDialog by remember { mutableStateOf(false) }
  var skillToDeleteName by remember { mutableStateOf("") }
  var skillToTest by remember { mutableStateOf<Skill?>(null) }
  var addSkillOptionTypeToConfirm by remember { mutableStateOf<AddSkillOptionType?>(null) }
  var skillToEditIndex by remember { mutableIntStateOf(-1) }
  var searchQuery by remember { mutableStateOf("") }
  var savedSelectedSkillsNamesAndDescriptions = remember { "" }
  var filteredSkills by remember { mutableStateOf(uiState.skills) }
  val listState = rememberLazyListState()
  val uriHandler = LocalUriHandler.current

  // Additional states for multi-select and section collapsing
  var hasDeterminedExpansionStates by remember { mutableStateOf(false) }
  var isBuiltInExpanded by remember { mutableStateOf(false) }
  var isCustomExpanded by remember { mutableStateOf(true) }
  var inMultiSelectMode by remember { mutableStateOf(false) }
  val selectedCustomSkillNames = remember { mutableStateListOf<String>() }
  var previousSearchQuery by remember { mutableStateOf(searchQuery) }

  var showSkillLimitBanner by remember { mutableStateOf(false) }
  val selectedSkillsCount by remember {
    derivedStateOf { uiState.skills.count { it.skill.selected } }
  }

  LaunchedEffect(selectedSkillsCount) {
    if (selectedSkillsCount > MAX_RECOMMENDED_SKILL_COUNT) {
      showSkillLimitBanner = true
    }
  }

  LaunchedEffect(showSkillLimitBanner) {
    if (showSkillLimitBanner) {
      delay(3000) // 3 seconds
      showSkillLimitBanner = false
    }
  }

  LaunchedEffect(uiState.skills, searchQuery, uiState.loading) {
    if (!uiState.loading && !hasDeterminedExpansionStates) {
      val hasCustomSkills = uiState.skills.any { !it.skill.builtIn }
      isBuiltInExpanded = !hasCustomSkills
      hasDeterminedExpansionStates = true
    }

    val trimmedQuery = searchQuery.trim().lowercase()
    filteredSkills =
      if (trimmedQuery.isBlank()) {
        uiState.skills
      } else {
        uiState.skills.filter { skillState ->
          val skill = skillState.skill
          (skill.name?.lowercase()?.contains(trimmedQuery) == true) ||
            (skill.description?.lowercase()?.contains(trimmedQuery) == true)
        }
      }

    if (searchQuery != previousSearchQuery) {
      if (searchQuery.isNotEmpty()) {
        isBuiltInExpanded = true
        isCustomExpanded = true
      }
      if (filteredSkills.isNotEmpty()) {
        listState.scrollToItem(0)
      }
      previousSearchQuery = searchQuery
    }
  }

  LaunchedEffect(Unit) {
    savedSelectedSkillsNamesAndDescriptions =
      skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
  }

  ModalBottomSheet(
    onDismissRequest = {
      onDismiss(
        savedSelectedSkillsNamesAndDescriptions !=
          skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
      )
    },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      // Spinner when loading.
      if (uiState.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp,
            modifier = Modifier.padding(end = 8.dp).size(24.dp),
          )
        }
      }
      // Loaded content.
      else {
        val focusManager = LocalFocusManager.current

        Column(
          modifier =
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxSize().pointerInput(
              Unit
            ) {
              detectTapGestures(onTap = { focusManager.clearFocus() })
            }
        ) {
          // Title or Multi-Select Context Bar.
          SkillManagerTitleBar(
            inMultiSelectMode = inMultiSelectMode,
            selectedCount = selectedCustomSkillNames.size,
            onExitMultiSelect = {
              inMultiSelectMode = false
              selectedCustomSkillNames.clear()
            },
            onRequestDeleteSelected = {
              if (selectedCustomSkillNames.isNotEmpty()) {
                showDeleteSkillDialog = true
              }
            },
            onClose = {
              scope.launch {
                sheetState.hide()
                onDismiss(
                  savedSelectedSkillsNamesAndDescriptions !=
                    skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
                )
              }
            },
          )

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
              Modifier.padding(top = 8.dp, bottom = if (searchQuery.isEmpty()) 8.dp else 18.dp)
                .height(IntrinsicSize.Min),
          ) {
            // Search bar.
            TextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.weight(1f).clearFocusOnKeyboardDismiss(),
              shape = CircleShape,
              placeholder = { Text(stringResource(R.string.search_skill)) },
              leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
              trailingIcon = {
                if (searchQuery.trim().isNotEmpty()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Outlined.Cancel, contentDescription = null)
                  }
                }
              },
              singleLine = true,
              colors =
                TextFieldDefaults.colors(
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                  disabledIndicatorColor = Color.Transparent,
                ),
            )

            // Button to add skill.
            Box(
              modifier =
                Modifier.fillMaxHeight()
                  .aspectRatio(1f)
                  .clip(CircleShape)
                  .clickable {
                    searchQuery = ""
                    showAddSkillOptionsSheet = true
                  }
                  .background(MaterialTheme.colorScheme.primary),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.cd_add_icon),
                tint = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }

          AnimatedVisibility(visible = searchQuery.isEmpty()) {
            Row(
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
              // Skill count.
              Text(
                pluralStringResource(
                  R.plurals.skills_count,
                  uiState.skills.size,
                  uiState.skills.size,
                ),
                style = MaterialTheme.typography.labelLarge,
              )

              // Select all / Deselect all.
              Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                  onClick = { skillManagerViewModel.setAllSkillsSelected(selected = true) }
                ) {
                  Text(stringResource(R.string.turn_on_all))
                }
                TextButton(
                  onClick = { skillManagerViewModel.setAllSkillsSelected(selected = false) }
                ) {
                  Text(stringResource(R.string.turn_off_all))
                }
              }
            }
          }

          // Content.
          //
          // Disable over-scroll "stretch" effect.
          SkillsContent(
            modifier = Modifier.weight(1f),
            filteredSkills = filteredSkills,
            listState = listState,
            isBuiltInExpanded = isBuiltInExpanded,
            onBuiltInToggle = { isBuiltInExpanded = !isBuiltInExpanded },
            isCustomExpanded = isCustomExpanded,
            onCustomToggle = { isCustomExpanded = !isCustomExpanded },
            inMultiSelectMode = inMultiSelectMode,
            isSelectedForDeletion = { name -> selectedCustomSkillNames.contains(name) },
            onMultiSelectToggle = { name, checked ->
              if (checked) {
                selectedCustomSkillNames.add(name)
              } else {
                selectedCustomSkillNames.remove(name)
                if (selectedCustomSkillNames.isEmpty()) inMultiSelectMode = false
              }
            },
            onMultiSelectStart = { name ->
              if (!inMultiSelectMode) {
                inMultiSelectMode = true
                selectedCustomSkillNames.add(name)
              }
            },
            onSkillEnabledChange = { skillState, checked ->
              skillManagerViewModel.setSkillSelected(skillState, checked)
            },
            onViewClick = { skillState ->
              skillToEditIndex = uiState.skills.indexOf(skillState)
              showAddOrEditSkillBottomSheet = true
            },
            onSecretClick = { skillState ->
              skillToEditIndex = uiState.skills.indexOf(skillState)
              showSecretEditorDialog = true
            },
            onDeleteClick = { name ->
              skillToDeleteName = name
              showDeleteSkillDialog = true
            },
            uriHandler = uriHandler,
            showSkillLimitBanner = showSkillLimitBanner,
          )
        }
      }
    }
  }


  SkillManagerDialogs(
    agentTools = agentTools,
    skillManagerViewModel = skillManagerViewModel,
    uiState = uiState,
    listState = listState,
    showDeleteSkillDialog = showDeleteSkillDialog,
    inMultiSelectMode = inMultiSelectMode,
    selectedCustomSkillNames = selectedCustomSkillNames,
    skillToDeleteName = skillToDeleteName,
    onDeleteDialogDismiss = { showDeleteSkillDialog = false },
    onDeleteConfirmed = {
      if (inMultiSelectMode) {
        skillManagerViewModel.deleteSkills(selectedCustomSkillNames.toSet())
        inMultiSelectMode = false
        selectedCustomSkillNames.clear()
      } else {
        skillManagerViewModel.deleteSkill(name = skillToDeleteName)
      }
      showDeleteSkillDialog = false
    },
    showAddSkillFromUrlDialog = showAddSkillFromUrlDialog,
    onAddSkillFromUrlDismiss = { showAddSkillFromUrlDialog = false },
    showAddSkillFromFeaturedListBottomSheet = showAddSkillFromFeaturedListBottomSheet,
    onAddSkillFromFeaturedListDismiss = { showAddSkillFromFeaturedListBottomSheet = false },
    showAddSkillFromLocalImportDialog = showAddSkillFromLocalImportDialog,
    onAddSkillFromLocalImportDismiss = { showAddSkillFromLocalImportDialog = false },
    showAddOrEditSkillBottomSheet = showAddOrEditSkillBottomSheet,
    skillToEditIndex = skillToEditIndex,
    onAddOrEditDismiss = {
      showAddOrEditSkillBottomSheet = false
      skillToEditIndex = -1
    },
    onAddOrEditSuccess = {
      scrollToBottomOfList(scope, listState)
      skillToEditIndex = -1
    },
    showAddSkillOptionsSheet = showAddSkillOptionsSheet,
    onAddSkillOptionsDismiss = { showAddSkillOptionsSheet = false },
    onAddSkillOptionSelected = { option ->
      skillManagerViewModel.setValidationError(null)
      addSkillOptionTypeToConfirm = option.type
      when (option.type) {
        AddSkillOptionType.FeaturedList -> { showAddSkillFromFeaturedListBottomSheet = true }
        AddSkillOptionType.RemoteUrl -> { showAddSkillFromUrlDialog = true }
        AddSkillOptionType.LocalImport -> { showDisclaimerDialog = true }
        else -> {}
      }
      showAddSkillOptionsSheet = false
    },
    showJsSkillTesterBottomSheet = showJsSkillTesterBottomSheet,
    skillToTest = skillToTest,
    onJsSkillTesterDismiss = { showJsSkillTesterBottomSheet = false },
    showSecretEditorDialog = showSecretEditorDialog,
    onSecretEditorDismiss = { showSecretEditorDialog = false },
    showDisclaimerDialog = showDisclaimerDialog,
    onDisclaimerDismiss = {
      showDisclaimerDialog = false
      addSkillOptionTypeToConfirm = null
    },
    onDisclaimerConfirm = {
      addSkillOptionTypeToConfirm?.let { type ->
        if (type == AddSkillOptionType.LocalImport) {
          showAddSkillFromLocalImportDialog = true
        }
      }
      showDisclaimerDialog = false
      addSkillOptionTypeToConfirm = null
    },
  )
}
