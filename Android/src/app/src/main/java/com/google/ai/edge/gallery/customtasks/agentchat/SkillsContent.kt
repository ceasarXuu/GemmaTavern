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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.data.MAX_RECOMMENDED_SKILL_COUNT
import selfgemma.talk.ui.common.FloatingBanner

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SkillsContent(
  filteredSkills: List<SkillState>,
  listState: LazyListState,
  isBuiltInExpanded: Boolean,
  onBuiltInToggle: () -> Unit,
  isCustomExpanded: Boolean,
  onCustomToggle: () -> Unit,
  inMultiSelectMode: Boolean,
  isSelectedForDeletion: (String) -> Boolean,
  onMultiSelectToggle: (String, Boolean) -> Unit,
  onMultiSelectStart: (String) -> Unit,
  onSkillEnabledChange: (SkillState, Boolean) -> Unit,
  onViewClick: (SkillState) -> Unit,
  onSecretClick: (SkillState) -> Unit,
  onDeleteClick: (String) -> Unit,
  uriHandler: UriHandler,
  showSkillLimitBanner: Boolean,
  modifier: Modifier = Modifier,
) {
  CompositionLocalProvider(LocalOverscrollFactory provides null) {
    val builtInSkills = remember(filteredSkills) { filteredSkills.filter { it.skill.builtIn } }
    val customSkills = remember(filteredSkills) { filteredSkills.filter { !it.skill.builtIn } }

    Box(modifier = modifier.fillMaxSize()) {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (builtInSkills.isNotEmpty()) {
          item(key = "built_in_header") {
            SkillSectionHeader(
              title = stringResource(R.string.built_in_skills_title),
              expanded = isBuiltInExpanded,
              onToggle = onBuiltInToggle,
            )
          }
          if (isBuiltInExpanded) {
            items(builtInSkills, key = { it.skill.name }) { skillState ->
              SkillItemRow(
                skillState = skillState,
                inMultiSelectMode = inMultiSelectMode,
                isSelectedForDeletion = false,
                onSelectionCheckedChange = {},
                onLongClick = {},
                onSkillEnabledChange = { onSkillEnabledChange(skillState, it) },
                onViewClick = { onViewClick(skillState) },
                onSecretClick = { onSecretClick(skillState) },
                onDeleteClick = { onDeleteClick(skillState.skill.name) },
                uriHandler = uriHandler,
              )
            }
          }
        }

        if (customSkills.isNotEmpty()) {
          item(key = "custom_header") {
            SkillSectionHeader(
              title = stringResource(R.string.custom_skills_title),
              expanded = isCustomExpanded,
              onToggle = onCustomToggle,
            )
          }
          if (isCustomExpanded) {
            items(customSkills, key = { it.skill.name }) { skillState ->
              SkillItemRow(
                skillState = skillState,
                inMultiSelectMode = inMultiSelectMode,
                isSelectedForDeletion = isSelectedForDeletion(skillState.skill.name),
                onSelectionCheckedChange = { checked ->
                  onMultiSelectToggle(skillState.skill.name, checked)
                },
                onLongClick = { onMultiSelectStart(skillState.skill.name) },
                onSkillEnabledChange = { onSkillEnabledChange(skillState, it) },
                onViewClick = { onViewClick(skillState) },
                onSecretClick = { onSecretClick(skillState) },
                onDeleteClick = { onDeleteClick(skillState.skill.name) },
                uriHandler = uriHandler,
              )
            }
          }
        }
      }

      FloatingBanner(
        visible = showSkillLimitBanner,
        text =
          stringResource(
            R.string.skill_limit_warning,
            pluralStringResource(
              R.plurals.skills_count,
              MAX_RECOMMENDED_SKILL_COUNT,
              MAX_RECOMMENDED_SKILL_COUNT,
            ),
          ),
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
  }
}

@Composable
private fun SkillSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape = RoundedCornerShape(20.dp))
        .clickable(onClick = onToggle)
        .padding(vertical = 12.dp, horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    Icon(
      imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
      contentDescription =
        if (expanded) stringResource(R.string.cd_collapse_icon)
        else stringResource(R.string.cd_expand_icon),
    )
  }
}
