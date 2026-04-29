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

package selfgemma.talk.ui.common.chat

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selfgemma.talk.R
import selfgemma.talk.data.Model
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.RotationalLoader
import selfgemma.talk.ui.theme.customColors

@Composable
internal fun FirstInitializingOverlay(visible: Boolean) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    AnimatedVisibility(
      visible,
      enter = fadeIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioLowBouncy
        )
      ) + scaleIn(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioLowBouncy
        ),
        initialScale = 0.8f
      ) + slideInVertically(
        animationSpec = spring(
          stiffness = Spring.StiffnessMediumLow,
          dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        initialOffsetY = { it / 4 }
      ),
      exit = fadeOut(
        animationSpec = spring(stiffness = Spring.StiffnessHigh)
      ) + scaleOut(
        targetScale = 0.9f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh)
      )
    ) {
      Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
          .fillMaxSize()
          .shadow(
            elevation = 6.dp,
            shape = RoundedCornerShape(24.dp),
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
          )
      ) {
        Column(
          modifier = Modifier.padding(32.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(80.dp)
          ) {
            RotationalLoader(size = 40.dp)
          }

          Text(
            stringResource(R.string.aichat_initializing_title),
            style =
              MaterialTheme.typography.headlineLarge.copy(
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
              ),
            color = MaterialTheme.colorScheme.onSurface,
          )

          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp)
          ) {
            Text(
              stringResource(R.string.aichat_initializing_content),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 12.dp, horizontal = 20.dp),
              lineHeight = 22.sp,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun ChatMessageRow(
  index: Int,
  message: ChatMessage,
  task: Task,
  selectedModel: Model,
  isLast: Boolean,
  inProgress: Boolean,
  imageHistoryCurIndex: MutableIntState,
  onSendMessage: (Model, List<ChatMessage>) -> Unit,
  onRunAgainClicked: (Model, ChatMessage) -> Unit,
  onImageSelected: (List<Bitmap>, Int) -> Unit,
  onAudioPlaybackStateChanged: (Boolean) -> Unit,
  onRequestBenchmark: (ChatMessage) -> Unit,
) {
  var hAlign: Alignment.Horizontal = Alignment.End
  var backgroundColor: Color = MaterialTheme.customColors.userBubbleBgColor
  var hardCornerAtLeftOrRight = false
  var extraPaddingStart = 48.dp
  var extraPaddingEnd = 0.dp
  if (message.side == ChatSide.AGENT) {
    hAlign = Alignment.Start
    backgroundColor = MaterialTheme.customColors.agentBubbleBgColor
    hardCornerAtLeftOrRight = true
    extraPaddingStart = 0.dp
    if (
      message.type !== ChatMessageType.LOADING &&
        message.type !== ChatMessageType.WEBVIEW &&
        message.type !== ChatMessageType.COLLAPSABLE_PROGRESS_PANEL
    ) {
      extraPaddingEnd = 48.dp
    }
  } else if (message.side == ChatSide.SYSTEM) {
    extraPaddingStart = 24.dp
    extraPaddingEnd = 24.dp
    if (message.type == ChatMessageType.PROMPT_TEMPLATES) {
      extraPaddingStart = 12.dp
      extraPaddingEnd = 12.dp
    }
  }
  if (message.type == ChatMessageType.IMAGE) {
    backgroundColor = Color.Transparent
  }
  val bubbleBorderRadius = dimensionResource(R.dimen.chat_bubble_corner_radius)

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .padding(
          start = 12.dp + extraPaddingStart,
          end = 12.dp + extraPaddingEnd,
          top = 6.dp,
          bottom = 6.dp,
        ),
    horizontalAlignment = hAlign,
  ) messageColumn@{
    var agentName = stringResource(task.agentNameRes)
    if (message.accelerator.isNotEmpty()) {
      agentName = "$agentName on ${message.accelerator}"
    }
    if (!message.hideSenderLabel) {
      MessageSender(
        message = message,
        agentName = agentName,
        imageHistoryCurIndex = imageHistoryCurIndex.intValue,
      )
    }

    when (message) {
      is ChatMessageLoading -> MessageBodyLoading(message = message)
      is ChatMessageInfo -> MessageBodyInfo(message = message)
      is ChatMessageWarning -> MessageBodyWarning(message = message)
      is ChatMessageError -> MessageBodyError(message = message)
      is ChatMessageConfigValuesChange -> MessageBodyConfigUpdate(message = message)
      is ChatMessagePromptTemplates ->
        MessageBodyPromptTemplates(
          message = message,
          task = task,
          onPromptClicked = { template ->
            onSendMessage(
              selectedModel,
              listOf(ChatMessageText(content = template.prompt, side = ChatSide.USER)),
            )
          },
        )
      else -> {
        var messageBubbleModifier: Modifier = Modifier
        if (!message.disableBubbleShape) {
          if (message is ChatMessageImage && message.bitmaps.size > 1) {
            messageBubbleModifier = messageBubbleModifier.clip(RoundedCornerShape(6.dp))
          } else {
            messageBubbleModifier =
              messageBubbleModifier.clip(
                MessageBubbleShape(
                  radius = bubbleBorderRadius,
                  hardCornerAtLeftOrRight = hardCornerAtLeftOrRight,
                )
              )
          }
          messageBubbleModifier = messageBubbleModifier.background(backgroundColor)
        }
        Box(modifier = messageBubbleModifier) {
          when (message) {
            is ChatMessageText ->
              MessageBodyText(
                message = message,
                inProgress = inProgress && isLast,
              )
            is ChatMessageImage -> {
              MessageBodyImage(message = message, onImageClicked = onImageSelected)
            }
            is ChatMessageImageWithHistory ->
              MessageBodyImageWithHistory(
                message = message,
                imageHistoryCurIndex = imageHistoryCurIndex,
              )
            is ChatMessageAudioClip ->
              MessageBodyAudioClip(
                message = message,
                onPlaybackStateChanged = onAudioPlaybackStateChanged,
              )
            is ChatMessageClassification ->
              MessageBodyClassification(
                message = message,
                modifier =
                  Modifier.width(message.maxBarWidth ?: CLASSIFICATION_BAR_MAX_WIDTH),
              )
            is ChatMessageBenchmarkResult -> MessageBodyBenchmark(message = message)
            is ChatMessageBenchmarkLlmResult ->
              MessageBodyBenchmarkLlm(
                message = message,
                modifier = Modifier.wrapContentWidth(),
              )
            is ChatMessageWebView -> MessageBodyWebview(message = message)
            is ChatMessageCollapsableProgressPanel ->
              MessageBodyCollapsableProgressPanel(message = message)
            is ChatMessageThinking ->
              MessageBodyThinking(
                thinkingText = message.content,
                inProgress = message.inProgress,
              )
            else -> {}
          }
        }

        if (message.side == ChatSide.AGENT) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            LatencyText(message = message)
          }
        } else if (message.side == ChatSide.USER) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (selectedModel.showRunAgainButton) {
              MessageActionButton(
                label = stringResource(R.string.run_again),
                icon = Icons.Rounded.Refresh,
                onClick = { onRunAgainClicked(selectedModel, message) },
                enabled = !inProgress,
              )
            }

            if (selectedModel.showBenchmarkButton) {
              MessageActionButton(
                label = stringResource(R.string.run_benchmark),
                icon = Icons.Outlined.Timer,
                onClick = { onRequestBenchmark(message) },
                enabled = !inProgress,
              )
            }
          }
        }
      }
    }
  }
}
