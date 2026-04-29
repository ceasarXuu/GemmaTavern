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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.AnalyticsEvent
import selfgemma.talk.R
import selfgemma.talk.firebaseAnalytics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSkillOptionsBottomSheet(
  onDismiss: () -> Unit,
  onOptionSelected: (AddSkillOption) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
      Text(
        stringResource(R.string.add_skill),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 16.dp).padding(horizontal = 16.dp),
      )
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ADD_SKILL_OPTIONS.forEach { option ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable {
                  onOptionSelected(option)
                  firebaseAnalytics?.logEvent(
                    AnalyticsEvent.BUTTON_CLICKED.id,
                    Bundle().apply {
                      putString("event_type", "agent_skills_add_skill")
                      putString("button_id", option.type.toString())
                    },
                  )
                  onDismiss()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 4.dp),
              ) {
                Icon(option.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(stringResource(option.titleResId), style = MaterialTheme.typography.bodyLarge)
              }
              Text(
                stringResource(option.descriptionResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp),
              )
            }
          }
        }
      }
    }
  }
}
