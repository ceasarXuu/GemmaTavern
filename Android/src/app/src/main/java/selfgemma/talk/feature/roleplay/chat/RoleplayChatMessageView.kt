package selfgemma.talk.feature.roleplay.chat

import android.graphics.BitmapFactory
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import selfgemma.talk.R
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload
import selfgemma.talk.ui.common.MarkdownText
import selfgemma.talk.ui.common.chat.AudioPlaybackPanel

@Composable
internal fun RenderRoleplayMessageBody(
  message: Message,
  isUser: Boolean,
  textColor: Color,
) {
  when (message.kind) {
    MessageKind.IMAGE ->
      RoleplayImageMessageBody(
        message = message,
        imageShape = MaterialTheme.shapes.large,
      )
    MessageKind.AUDIO -> RoleplayAudioMessageBody(message = message)
    else ->
      RenderChatMessageText(
        text = message.displayText(),
        textColor = textColor,
      )
  }
}

@Composable
internal fun RoleplayImageMessageBody(
  message: Message,
  imageShape: Shape,
) {
  val imagePaths =
    remember(message.metadataJson) {
      message
        .roleplayMessageMediaPayload()
        ?.attachments
        ?.filter { it.type == RoleplayMessageAttachmentType.IMAGE }
        ?.map { it.filePath }
        .orEmpty()
    }
  val bitmaps by
    produceState<List<android.graphics.Bitmap>>(initialValue = emptyList(), key1 = imagePaths) {
      value =
        withContext(Dispatchers.IO) {
          imagePaths.mapNotNull(::decodeRoleplayBitmap)
        }
    }
  if (bitmaps.isEmpty()) {
    RenderChatMessageText(
      text = message.displayText(),
      textColor = MaterialTheme.colorScheme.onSurface,
    )
    return
  }

  Row(
    modifier =
      Modifier
        .horizontalScroll(rememberScrollState())
        .padding(all = if (bitmaps.size > 1) 8.dp else 0.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    bitmaps.forEach { bitmap ->
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_image_thumbnail),
        contentScale = ContentScale.Crop,
        modifier =
          roleplayImageModifier(bitmap = bitmap)
            .clip(if (bitmaps.size == 1) imageShape else MaterialTheme.shapes.large),
      )
    }
  }
}

private fun roleplayImageModifier(bitmap: android.graphics.Bitmap): Modifier {
  val imageWidth = bitmap.width.coerceAtLeast(1)
  val imageHeight = bitmap.height.coerceAtLeast(1)
  val aspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
  return if (aspectRatio >= 1f) {
    Modifier.width(236.dp).aspectRatio(aspectRatio)
  } else {
    Modifier.height(280.dp).aspectRatio(aspectRatio)
  }
}

private fun decodeRoleplayBitmap(filePath: String): android.graphics.Bitmap? {
  return runCatching {
      val bounds =
        BitmapFactory.Options().apply {
          inJustDecodeBounds = true
        }
      BitmapFactory.decodeFile(filePath, bounds)
      val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
      val sampleSize =
        when {
          maxDimension > 4096 -> 8
          maxDimension > 2048 -> 4
          maxDimension > 1024 -> 2
          else -> 1
        }
      BitmapFactory.decodeFile(
        filePath,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        },
      )
    }
    .getOrNull()
}

@Composable
private fun RoleplayAudioMessageBody(message: Message) {
  val audioAttachments =
    remember(message.metadataJson) {
      message
        .roleplayMessageMediaPayload()
        ?.attachments
        ?.filter { it.type == RoleplayMessageAttachmentType.AUDIO }
        .orEmpty()
    }
  val audioPayloads by
    produceState<List<Pair<ByteArray, Int>>>(initialValue = emptyList(), key1 = audioAttachments) {
      value =
        withContext(Dispatchers.IO) {
          audioAttachments.mapNotNull { attachment ->
            val sampleRate = attachment.sampleRate ?: return@mapNotNull null
            val audioData = runCatching { java.io.File(attachment.filePath).readBytes() }.getOrNull()
            audioData?.let { it to sampleRate }
          }
        }
    }
  if (audioPayloads.isEmpty()) {
    RenderChatMessageText(
      text = message.displayText(),
      textColor = MaterialTheme.colorScheme.onSurface,
    )
    return
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    audioPayloads.forEach { (audioData, sampleRate) ->
      AudioPlaybackPanel(
        audioData = audioData,
        sampleRate = sampleRate,
        isRecording = false,
      )
    }
  }
}

@Composable
private fun RenderChatMessageText(
  text: String,
  textColor: Color,
) {
  when {
    text.looksLikeHtml() -> HtmlText(text = text, textColor = textColor)
    text.looksLikeMarkdown() -> MarkdownText(text = text, textColor = textColor, linkColor = textColor)
    else ->
      Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 22.sp,
        color = textColor,
      )
  }
}

@Composable
private fun HtmlText(
  text: String,
  textColor: Color,
) {
  val context = LocalContext.current
  val textSize = MaterialTheme.typography.bodyLarge.fontSize.value
  AndroidView(
    factory = {
      TextView(context).apply {
        setTextColor(textColor.toArgb())
        setTextSize(textSize)
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
        setLineSpacing(0f, 1.2f)
      }
    },
    update = { textView ->
      textView.setTextColor(textColor.toArgb())
      textView.text = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    },
  )
}

@Composable
internal fun MissingModelBanner(
  downloadedModels: List<Model>,
  onSwitchModel: (String) -> Unit,
  onOpenModelLibrary: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        text = stringResource(R.string.chat_missing_model_title),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(R.string.chat_missing_model_content),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.82f),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        downloadedModels.firstOrNull()?.let { fallbackModel ->
          OutlinedButton(onClick = { onSwitchModel(fallbackModel.name) }) {
            Text(
              stringResource(
                R.string.chat_use_model,
                fallbackModel.displayName.ifEmpty { fallbackModel.name },
              )
            )
          }
        }
        FilledTonalButton(onClick = onOpenModelLibrary) {
          Text(stringResource(R.string.chat_open_model_library))
        }
      }
    }
  }
}

@Composable
internal fun ChatComposer(
  draft: String,
  onDraftChange: (String) -> Unit,
  canSend: Boolean,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TextField(
      modifier = Modifier.weight(1f),
      value = draft,
      onValueChange = onDraftChange,
      minLines = 1,
      maxLines = 4,
      shape = MaterialTheme.shapes.extraLarge,
      placeholder = { Text(stringResource(R.string.chat_message_placeholder)) },
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
        ),
    )

    FilledIconButton(
      onClick = onSend,
      enabled = canSend,
      modifier = Modifier.size(48.dp),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Rounded.Send,
        contentDescription = stringResource(R.string.chat_send_message),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
internal fun Message.displayText(): String {
  if (content.isNotBlank()) {
    return content
  }

  return when (status) {
    MessageStatus.STREAMING -> "..."
    MessageStatus.INTERRUPTED -> stringResource(R.string.chat_response_stopped)
    MessageStatus.FAILED -> errorMessage ?: stringResource(R.string.chat_response_failed)
    else -> stringResource(R.string.chat_empty_message)
  }
}

@Composable
internal fun TypingIndicator() {
  val dots = listOf(0, 1, 2)
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(vertical = 8.dp)
  ) {
    dots.forEach { index ->
      var animating by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) {
        delay(index * 200L)
        animating = true
      }

      Surface(
        modifier = Modifier.size(8.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
      ) {}
    }
  }
}

private fun String.looksLikeHtml(): Boolean {
  return Regex("""<([a-zA-Z][a-zA-Z0-9]*)(\s[^>]*)?>|</[a-zA-Z][a-zA-Z0-9]*>""").containsMatchIn(this)
}

private fun String.looksLikeMarkdown(): Boolean {
  return Regex("""(?m)^\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|```|~~~)|(\[[^]]+]\([^)]+\)|`[^`]+`)""")
    .containsMatchIn(this)
}
