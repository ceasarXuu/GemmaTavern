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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.ui.theme.customColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SkillItemRow(
  skillState: SkillState,
  inMultiSelectMode: Boolean,
  isSelectedForDeletion: Boolean,
  onSelectionCheckedChange: (Boolean) -> Unit,
  onLongClick: () -> Unit,
  onSkillEnabledChange: (Boolean) -> Unit,
  onViewClick: () -> Unit,
  onSecretClick: () -> Unit,
  onDeleteClick: () -> Unit,
  uriHandler: UriHandler,
) {
  val skill = skillState.skill
  val isCustom = !skill.builtIn

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .then(if (inMultiSelectMode && skill.builtIn) Modifier.alpha(0.5f) else Modifier)
        .clip(shape = RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .then(
          if (isCustom) {
            Modifier.combinedClickable(
              onClick = {
                if (inMultiSelectMode) {
                  onSelectionCheckedChange(!isSelectedForDeletion)
                }
              },
              onLongClick = onLongClick,
            )
          } else Modifier
        )
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (inMultiSelectMode && isCustom) {
      Checkbox(
        checked = isSelectedForDeletion,
        onCheckedChange = onSelectionCheckedChange,
        modifier = Modifier.padding(end = 12.dp),
      )
    }

    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
          modifier = Modifier.weight(1f).padding(top = 2.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            val hasHomepage = !skill.homepage.isBlank()
            val textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)

            if (hasHomepage) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  skill.name,
                  style =
                    textStyle.copy(
                      color = MaterialTheme.colorScheme.primary,
                      textDecoration = TextDecoration.Underline,
                    ),
                  color = MaterialTheme.customColors.linkColor,
                  modifier = Modifier.clickable { uriHandler.openUri(skill.homepage) },
                )
                Icon(
                  Icons.AutoMirrored.Outlined.OpenInNew,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = MaterialTheme.customColors.linkColor,
                )
              }
            } else {
              Text(skill.name, style = textStyle)
            }
          }
          Text(
            (skill.description ?: "").replace("\n", " "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Switch(
          checked = skill.selected,
          onCheckedChange = onSkillEnabledChange,
          modifier = Modifier.offset(y = (-4).dp),
          enabled = !inMultiSelectMode,
        )
      }

      AnimatedVisibility(visible = !inMultiSelectMode) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Start,
          modifier = Modifier.padding(top = 8.dp),
        ) {
          FilledTonalButton(
            onClick = onViewClick,
            modifier = Modifier.height(32.dp).padding(end = 8.dp),
            contentPadding = BUTTON_CONTENT_PADDING,
          ) {
            Icon(
              Icons.Outlined.RemoveRedEye,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
            Text(
              stringResource(R.string.view),
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }

          if (skill.requireSecret) {
            FilledTonalButton(
              onClick = onSecretClick,
              modifier = Modifier.height(32.dp).padding(end = 8.dp),
              contentPadding = BUTTON_CONTENT_PADDING,
            ) {
              Icon(
                Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
              )
              Text(
                stringResource(R.string.secret),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp),
              )
            }
          }

          if (!skill.builtIn) {
            OutlinedButton(
              onClick = onDeleteClick,
              modifier = Modifier.height(32.dp),
              contentPadding = BUTTON_CONTENT_PADDING,
            ) {
              Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
              )
              Text(
                stringResource(R.string.delete),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp),
              )
            }
          }
        }
      }
    }
  }
}
