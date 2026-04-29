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
package selfgemma.talk.customtasks.tinygarden

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import com.google.common.io.BaseEncoding
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatSide
import selfgemma.talk.ui.theme.customColors

private const val TG_TAG = "AGTinyGarden"

@SuppressLint("SetJavaScriptEnabled")
internal fun createTinyGardenWebView(
  context: Context,
  viewModel: TinyGardenViewModel,
  scope: CoroutineScope,
  onWebViewReady: (WebView) -> Unit,
): WebView {
  val assetLoader =
    WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
      .build()

  return WebView(context).apply {
    onWebViewReady(this)

    layoutParams =
      ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )

    settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = true
      mediaPlaybackRequiresUserGesture = false
    }

    webViewClient =
      object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView?,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          return assetLoader.shouldInterceptRequest(request.url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          Log.d(TG_TAG, "webview finished loading")

          if (!viewModel.dataStoreRepository.getHasRunTinyGarden()) {
            Log.d(TG_TAG, "First time running Tiny Garden. Showing help screen...")
            viewModel.dataStoreRepository.setHasRunTinyGarden(true)
            scope.launch {
              delay(1000)
              view
                ?.runCatching { evaluateJavascript("tinyGarden.showHelp()", null) }
                ?.onFailure { e -> Log.e(TG_TAG, "$e") }
            }
          }
        }

        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?,
        ): Boolean {
          if (request == null) {
            return false
          }

          val url = request.url.toString()

          if (url.startsWith(TINY_GARDEN_ASSETS_BASE_URL)) {
            return false
          }

          try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            view?.context?.startActivity(intent)
          } catch (e: Exception) {
            Log.e(TG_TAG, "Could not open external URL: $url", e)
          }

          return true
        }
      }

    webChromeClient =
      object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
          Log.d(
            TG_TAG,
            "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
          )
          return super.onConsoleMessage(consoleMessage)
        }
      }

    var url = "$TINY_GARDEN_ASSETS_BASE_URL/assets/tinygarden/index.html"
    if (!viewModel.dataStoreRepository.getHasRunTinyGarden()) {
      Log.d(TG_TAG, "First time running Tiny Garden. Showing tutorial screen...")
      viewModel.dataStoreRepository.setHasRunTinyGarden(true)
      url = "$url?tutorial=1"
    }
    loadUrl(url)
  }
}

internal const val TINY_GARDEN_ASSETS_BASE_URL = "https://appassets.androidplatform.net"

@Composable
internal fun TinyGardenErrorDialog(
  errorContent: String,
  taskColor: Color,
  onDismiss: () -> Unit,
  onReset: () -> Unit,
) {
  AlertDialog(
    title = { Text(stringResource(R.string.error)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(errorContent, style = MaterialTheme.typography.bodyMedium)
        Text(
          stringResource(R.string.reset_note),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.customColors.warningTextColor,
        )
      }
    },
    onDismissRequest = onDismiss,
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
    confirmButton = {
      Button(
        onClick = onReset,
        colors = ButtonDefaults.buttonColors(containerColor = taskColor),
      ) {
        Text(stringResource(R.string.reset), color = Color.White)
      }
    },
  )
}

internal fun TinyGardenViewModel.handleTinyGardenCommand(
  command: TinyGardenCommand,
  resources: Resources,
  webView: WebView?,
): TinyGardenCommandSummary {
  val functionName =
    when (command.item) {
      TinyGardenItem.SUNFLOWER.ordinal + 1,
      TinyGardenItem.DAISY.ordinal + 1,
      TinyGardenItem.ROSE.ordinal + 1,
      TinyGardenItem.SPECIAL.ordinal + 1 -> "plantSeed"

      TinyGardenItem.WATERING_CAN.ordinal + 1 -> "waterPlots"
      TinyGardenItem.SCYTHE.ordinal + 1 -> "harvestPlots"
      else -> ""
    }
  val strPlots = "[${command.plots.joinToString(",")}]"
  val functionParameter =
    when (command.item) {
      TinyGardenItem.SUNFLOWER.ordinal + 1 -> "- seed: \"sunflower\"\n- plots: $strPlots"
      TinyGardenItem.DAISY.ordinal + 1 -> "- seed: \"daisy\"\n- plots: $strPlots"
      TinyGardenItem.ROSE.ordinal + 1 -> "- seed: \"rose\"\n- plots: $strPlots"
      TinyGardenItem.SPECIAL.ordinal + 1 -> "- seed: \"special\"\n- plots: $strPlots"
      TinyGardenItem.WATERING_CAN.ordinal + 1 -> "- plots: $strPlots"
      TinyGardenItem.SCYTHE.ordinal + 1 -> "- plots: $strPlots"
      else -> ""
    }
  val numParameters =
    when (command.item) {
      TinyGardenItem.WATERING_CAN.ordinal + 1,
      TinyGardenItem.SCYTHE.ordinal + 1 -> 1
      else -> 2
    }
  val functionNameLabel = resources.getString(R.string.function_name)
  val parametersLabel = resources.getQuantityString(R.plurals.parameter, numParameters)
  addMessage(
    message =
      ChatMessageText(
        content =
          "**$functionNameLabel**:\n- $functionName\n\n**$parametersLabel**:\n$functionParameter",
        side = ChatSide.AGENT,
      )
  )

  val commandJson =
    """[{"item": ${command.item}, "plot":[${command.plots.joinToString(",")}]}]"""
  Log.d(TG_TAG, "commandJson: $commandJson")

  val jsScript = "tinyGarden.runCommands('$commandJson')"
  webView
    ?.runCatching { evaluateJavascript(jsScript, null) }
    ?.onFailure { e -> Log.e(TG_TAG, "$e") }

  val seed =
    when (command.item) {
      TinyGardenItem.SUNFLOWER.ordinal + 1 -> TinyGardenItem.SUNFLOWER.label
      TinyGardenItem.DAISY.ordinal + 1 -> TinyGardenItem.DAISY.label
      TinyGardenItem.ROSE.ordinal + 1 -> TinyGardenItem.ROSE.label
      TinyGardenItem.SPECIAL.ordinal + 1 -> TinyGardenItem.SPECIAL.label
      else -> ""
    }
  val plots = command.plots.joinToString(",")
  val action =
    when (command.item) {
      TinyGardenItem.WATERING_CAN.ordinal + 1 -> TinyGardenItem.WATERING_CAN.label
      TinyGardenItem.SCYTHE.ordinal + 1 -> TinyGardenItem.SCYTHE.label
      else -> ""
    }
  Log.d(TG_TAG, "prevSeed: '$seed', prevPlots: '$plots', prevAction: '$action'")
  return TinyGardenCommandSummary(seed = seed, plots = plots, action = action)
}

internal data class TinyGardenCommandSummary(
  val seed: String,
  val plots: String,
  val action: String,
)

internal fun detectTinyGardenConfigChange(
  model: selfgemma.talk.data.Model,
): Pair<Boolean, Boolean> {
  var same = true
  var nonNumTurnsConfigChanged = false
  for (config in model.configs) {
    val key = config.key.label
    val oldValue =
      if (model.prevConfigValues.containsKey(key)) {
        selfgemma.talk.data.convertValueToTargetType(
          value = model.prevConfigValues.getValue(key),
          valueType = config.valueType,
        )
      } else {
        null
      }
    val newValue =
      selfgemma.talk.data.convertValueToTargetType(
        value = model.configValues.getValue(key),
        valueType = config.valueType,
      )
    if (oldValue != newValue) {
      same = false
      if (config.key != selfgemma.talk.data.ConfigKeys.RESET_CONVERSATION_TURN_COUNT) {
        nonNumTurnsConfigChanged = true
      }
    }
  }
  return same to nonNumTurnsConfigChanged
}

internal fun String.tinyGardenSha256(): String {
  val inputBytes = this.toByteArray()
  return try {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val digest = sha256.digest(inputBytes)
    BaseEncoding.base64().encode(digest)
  } catch (e: Exception) {
    e.printStackTrace()
    ""
  }
}

@Composable
internal fun ResettingEngineOverlay(visible: Boolean) {
  Column {
    AnimatedVisibility(
      visible,
      enter = fadeIn() + scaleIn(initialScale = 0.9f),
      exit = fadeOut() + scaleOut(targetScale = 0.9f),
    ) {
      Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          verticalArrangement = Arrangement.spacedBy(8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          CircularProgressIndicator(
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp,
            modifier = Modifier.size(24.dp),
          )
          Text(
            stringResource(R.string.resetting_engine),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            stringResource(R.string.reinitializing_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
      }
    }
  }
}
