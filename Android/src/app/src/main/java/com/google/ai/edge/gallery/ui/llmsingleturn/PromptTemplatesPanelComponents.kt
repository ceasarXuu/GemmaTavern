/*
 * Copyright 2025 Google LLC
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

package selfgemma.talk.ui.llmsingleturn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamplePromptsBottomSheet(
  examplePrompts: List<String>,
  sheetState: SheetState,
  expandedStates: SnapshotStateMap<String, Boolean>,
  onDismiss: () -> Unit,
  onPromptSelected: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.wrapContentHeight(),
  ) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Text(
        "Select an example",
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.titleLarge,
      )

      for (prompt in examplePrompts) {
        var textLayoutResultState by remember { mutableStateOf<TextLayoutResult?>(null) }
        val hasOverflow =
          remember(textLayoutResultState) { textLayoutResultState?.hasVisualOverflow ?: false }
        val isExpanded = expandedStates[prompt] ?: false

        Column(
          modifier =
            Modifier.fillMaxWidth()
              .clickable {
                onPromptSelected(prompt)
                scope.launch {
                  delay(200)
                  onDismiss()
                }
              }
              .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Outlined.Description, contentDescription = null)
            Text(
              prompt,
              maxLines = if (isExpanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
              onTextLayout = { textLayoutResultState = it },
            )
          }

          if (hasOverflow && !isExpanded) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
              horizontalArrangement = Arrangement.End,
            ) {
              Box(
                modifier =
                  Modifier.padding(end = 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { expandedStates[prompt] = true }
                    .padding(vertical = 1.dp, horizontal = 6.dp)
              ) {
                Icon(
                  Icons.Outlined.ExpandMore,
                  contentDescription = stringResource(R.string.cd_expand_icon),
                  modifier = Modifier.size(12.dp),
                )
              }
            }
          } else if (isExpanded) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
              horizontalArrangement = Arrangement.End,
            ) {
              Box(
                modifier =
                  Modifier.padding(end = 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { expandedStates[prompt] = false }
                    .padding(vertical = 1.dp, horizontal = 6.dp)
              ) {
                Icon(
                  Icons.Outlined.ExpandLess,
                  contentDescription = stringResource(R.string.cd_collapse_icon),
                  modifier = Modifier.size(12.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}
