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
package selfgemma.talk.customtasks.mobileactions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selfgemma.talk.R
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.MarkdownText
import selfgemma.talk.ui.common.chat.ChatMessageWarning
import selfgemma.talk.ui.common.chat.MessageBodyWarning
import selfgemma.talk.ui.common.getTaskIconColor

@Composable
internal fun MobileActionsWelcomeBlock(task: Task, modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        stringResource(R.string.mobile_actions_title),
        style = MaterialTheme.typography.headlineLarge,
        color = getTaskIconColor(task = task),
      )
      Text(
        stringResource(R.string.mobile_actions_description),
        style = MaterialTheme.typography.bodyMedium,
        color = getTaskIconColor(task = task),
      )
      Column {
        Text(
          stringResource(R.string.mobile_actions_supported_actions),
          style = MaterialTheme.typography.labelLarge,
          modifier =
            Modifier.padding(top = 64.dp, bottom = 8.dp).graphicsLayer { alpha = 0.7f },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (item in SAMPLE_ACTION_ITEMS) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              item.icon,
              contentDescription = null,
              modifier = Modifier.size(24.dp).padding(end = 8.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              stringResource(item.labelResId),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileActionsResponseTabs(
  selectedTabIndex: Int,
  onTabSelected: (Int) -> Unit,
  taskColor: Color,
  noFunctionRecognized: Boolean,
  modelResponse: String,
  functionCallDetails: List<String>,
  doneGeneratingResponse: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(modifier = Modifier.fillMaxWidth()) {
    PrimaryTabRow(
      selectedTabIndex = selectedTabIndex,
      containerColor = Color.Transparent,
      indicator = {
        TabRowDefaults.PrimaryIndicator(
          modifier =
            Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
          color = taskColor,
          width = Dp.Unspecified,
        )
      },
    ) {
      for ((index, tab) in MOBILE_ACTIONS_TABS.withIndex()) {
        val enabled = index == 0 || (index == 1 && !noFunctionRecognized)
        Tab(
          selected = selectedTabIndex == index,
          enabled = enabled,
          onClick = { onTabSelected(index) },
          modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.3f },
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              val titleColor =
                if (selectedTabIndex == index) taskColor
                else MaterialTheme.colorScheme.onSurfaceVariant
              Icon(
                tab.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp).alpha(0.7f),
                tint = titleColor,
              )
              BasicText(
                text = stringResource(tab.labelResId),
                maxLines = 1,
                color = { titleColor },
                style =
                  MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                  ),
                autoSize =
                  TextAutoSize.StepBased(
                    minFontSize = 9.sp,
                    maxFontSize = 14.sp,
                    stepSize = 1.sp,
                  ),
              )
            }
          },
        )
      }
    }
  }

  Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    AnimatedContent(
      selectedTabIndex,
      transitionSpec = {
        if (targetState > initialState) {
          slideInHorizontally { 40 } + fadeIn() togetherWith
            slideOutHorizontally { -40 } + fadeOut(animationSpec = tween(50))
        } else {
          slideInHorizontally { -40 } + fadeIn() togetherWith
            slideOutHorizontally { 40 } + fadeOut(animationSpec = tween(50))
        }
      },
      modifier = Modifier,
    ) {
      if (selectedTabIndex == 0) {
        Column(modifier = Modifier.fillMaxWidth()) {
          val cdResponse = stringResource(R.string.cd_model_response_text)
          MarkdownText(
            text = modelResponse,
            modifier =
              Modifier.semantics(mergeDescendants = true) {
                  contentDescription = cdResponse
                  if (doneGeneratingResponse) {
                    liveRegion = LiveRegionMode.Polite
                  }
                }
                .padding(16.dp),
          )

          if (noFunctionRecognized) {
            MessageBodyWarning(
              ChatMessageWarning(
                content = stringResource(R.string.warning_no_function_call)
              )
            )
          }
        }
      } else if (selectedTabIndex == 1) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for ((index, details) in functionCallDetails.withIndex()) {
            MarkdownText(text = details, modifier = Modifier.padding(16.dp))

            if (index != functionCallDetails.size - 1) {
              HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun MobileActionsPromptTemplateChips(processing: Boolean, onPromptClick: (String) -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).graphicsLayer {
        alpha = if (processing) 0.5f else 1f
      },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Spacer(modifier = Modifier.width(12.dp))
    for (item in PROMPT_TEMPLATES) {
      Text(
        stringResource(item.labelResId),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier =
          Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !processing) { onPromptClick(item.prompt) }
            .background(color = MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              shape = RoundedCornerShape(12.dp),
            )
            .padding(all = 12.dp),
      )
    }
    Spacer(modifier = Modifier.width(12.dp))
  }
}

@Composable
internal fun MobileActionsResetEngineDialog(
  errorDialogContent: String,
  taskColor: Color,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    title = { Text(stringResource(R.string.error)) },
    text = { Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium) },
    onDismissRequest = onDismiss,
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = ButtonDefaults.buttonColors(containerColor = taskColor),
      ) {
        Text(stringResource(R.string.reset), color = Color.White)
      }
    },
  )
}
