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

// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import androidx.compose.ui.tooling.preview.Preview

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.ui.common.MarkdownText

private val markdownBlockPattern = Regex("""(?m)^\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|```|~~~)""")
private val markdownInlinePattern =
  Regex("""(\[[^]]+]\([^)]+\)|`[^`]+`|\*\*[^*\n]+\*\*|__[^_\n]+__|\*[^*\n]+\*|_[^_\n]+_)""")

/** Composable function to display the text content of a ChatMessageText. */
@Composable
fun MessageBodyText(message: ChatMessageText, inProgress: Boolean = false) {
  val shouldRenderMarkdown = shouldRenderMarkdown(message = message, isStreaming = inProgress)

  SelectionContainer {
    if (message.side == ChatSide.USER) {
      if (shouldRenderMarkdown) {
        MarkdownText(
          text = message.content,
          modifier = Modifier.padding(12.dp),
          textColor = Color.White,
          linkColor = Color.White,
        )
      } else {
        Text(
          text = message.content,
          style = MaterialTheme.typography.bodyLarge,
          color = Color.White,
          modifier = Modifier.padding(12.dp),
        )
      }
    } else if (message.side == ChatSide.AGENT) {
      val cdResponse = stringResource(R.string.cd_model_response_text)
      if (shouldRenderMarkdown) {
        MarkdownText(
          text = message.content,
          modifier =
            Modifier.padding(12.dp).semantics(mergeDescendants = true) {
              contentDescription = cdResponse
              // Only announce when message is complete.
              if (!inProgress) {
                liveRegion = LiveRegionMode.Polite
              }
            },
        )
      } else {
        Text(
          message.content,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier =
            Modifier.padding(12.dp).semantics {
              contentDescription = cdResponse
              // Only announce when message is complete.
              if (!inProgress) {
                liveRegion = LiveRegionMode.Polite
              }
            },
        )
      }
    }
  }
}

private fun shouldRenderMarkdown(message: ChatMessageText, isStreaming: Boolean): Boolean {
  if (!message.isMarkdown || isStreaming) {
    return false
  }

  val text = message.content
  if (text.isBlank()) {
    return false
  }

  return markdownBlockPattern.containsMatchIn(text) || markdownInlinePattern.containsMatchIn(text)
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyTextPreview() {
//   GalleryTheme {
//     Column {
//       Row(modifier = Modifier.padding(16.dp).background(MaterialTheme.colorScheme.primary)) {
//         MessageBodyText(ChatMessageText(content = "Hello world", side = ChatSide.USER))
//       }
//       Row(
//         modifier = Modifier.padding(16.dp).background(MaterialTheme.colorScheme.surfaceContainer)
//       ) {
//         MessageBodyText(ChatMessageText(content = "yes hello world", side = ChatSide.AGENT))
//       }
//     }
//   }
// }
