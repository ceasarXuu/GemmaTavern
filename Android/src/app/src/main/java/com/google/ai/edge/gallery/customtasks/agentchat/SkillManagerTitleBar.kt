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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R

@Composable
internal fun SkillManagerTitleBar(
  inMultiSelectMode: Boolean,
  selectedCount: Int,
  onExitMultiSelect: () -> Unit,
  onRequestDeleteSelected: () -> Unit,
  onClose: () -> Unit,
) {
  if (inMultiSelectMode) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onExitMultiSelect) {
        Icon(
          Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_icon),
        )
      }
      Text(
        pluralStringResource(
          R.plurals.selected_custom_skills_count,
          selectedCount,
          selectedCount,
        ),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f).padding(start = 8.dp),
      )
      IconButton(
        modifier = Modifier.padding(end = 3.dp),
        onClick = onRequestDeleteSelected,
      ) {
        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
      }
    }
  } else {
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          stringResource(R.string.manage_skills),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          stringResource(R.string.manage_skills_description),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(
        modifier = Modifier.padding(end = 3.dp),
        onClick = onClose,
      ) {
        Icon(
          Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_icon),
        )
      }
    }
  }
}
