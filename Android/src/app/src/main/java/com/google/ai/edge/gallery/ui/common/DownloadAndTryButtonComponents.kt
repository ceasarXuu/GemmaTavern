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

package selfgemma.talk.ui.common

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import selfgemma.talk.R
import selfgemma.talk.data.ModelDownloadStatus
import selfgemma.talk.data.Task

internal val MODEL_NAMES_TO_SHOW_GEMMA_LICENSES =
  setOf("Gemma-3n-E2B-it", "Gemma-3n-E4B-it", "Gemma3-1B-IT")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgreementAckSheet(
  modelUrl: String,
  sheetState: SheetState,
  onDismiss: () -> Unit,
  onLaunchAgreement: (Intent) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.wrapContentHeight(),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(horizontal = 16.dp),
    ) {
      Text("Acknowledge user agreement", style = MaterialTheme.typography.titleLarge)
      Text(
        "This is a gated model. Please click the button below to view and agree to the user agreement. After accepting, simply close that tab to proceed with the model download.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 16.dp),
      )
      Button(
        onClick = {
          val index = modelUrl.indexOf("/resolve/")
          if (index >= 0) {
            val agreementUrl = modelUrl.substring(0, index)
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.setData(agreementUrl.toUri())
            onLaunchAgreement(customTabsIntent.intent)
          }
          onDismiss()
        }
      ) {
        Text("Open user agreement")
      }
    }
  }
}

@Composable
internal fun DownloadProgressRow(
  downloadStatus: ModelDownloadStatus,
  checkingToken: Boolean,
  compact: Boolean,
  task: Task?,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var curDownloadProgress: Float =
    downloadStatus.receivedBytes.toFloat() / downloadStatus.totalBytes.toFloat()
  if (curDownloadProgress.isNaN()) {
    curDownloadProgress = 0f
  }
  val animatedProgress = remember { Animatable(0f) }

  var downloadProgressModifier: Modifier = modifier
  if (!compact) {
    downloadProgressModifier = downloadProgressModifier.fillMaxWidth()
  }
  downloadProgressModifier =
    downloadProgressModifier
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(horizontal = 8.dp)
      .height(42.dp)
  Row(modifier = downloadProgressModifier, verticalAlignment = Alignment.CenterVertically) {
    if (checkingToken) {
      Text(
        stringResource(R.string.checking_access),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = if (!compact) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 4.dp),
      )
    } else {
      Text(
        "${(curDownloadProgress * 100).toInt()}%",
        style =
          MaterialTheme.typography.bodyMedium.copy(
            fontFeatureSettings = "tnum"
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp).width(if (compact) 32.dp else 44.dp),
      )
      if (!compact) {
        val color =
          if (task != null) getTaskBgGradientColors(task = task)[1]
          else MaterialTheme.colorScheme.primary
        LinearProgressIndicator(
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
          progress = { animatedProgress.value },
          color = color,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }
      val cbStop = stringResource(R.string.cd_stop_icon)
      IconButton(
        onClick = onCancel,
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
          ),
        modifier = Modifier.semantics { contentDescription = cbStop },
      ) {
        Icon(
          Icons.Outlined.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
  LaunchedEffect(curDownloadProgress) {
    animatedProgress.animateTo(curDownloadProgress, animationSpec = tween(150))
  }
}

@Composable
internal fun DownloadErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
  AlertDialog(
    icon = {
      Icon(
        Icons.Rounded.Error,
        contentDescription = stringResource(R.string.cd_error),
        tint = MaterialTheme.colorScheme.error,
      )
    },
    title = { Text(title) },
    text = { Text(message) },
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
