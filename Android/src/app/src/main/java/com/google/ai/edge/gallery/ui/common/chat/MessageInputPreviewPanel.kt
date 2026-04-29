package selfgemma.talk.ui.common.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import selfgemma.talk.R
import selfgemma.talk.common.AudioClip

@Composable
internal fun MessageInputPreviewPanel(
  pickedImages: List<Bitmap>,
  pickedAudioClips: List<AudioClip>,
  onRemoveImage: (Bitmap) -> Unit,
  onRemoveAudioClip: (Int) -> Unit,
) {
  if (pickedImages.isEmpty() && pickedAudioClips.isEmpty()) return
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Spacer(modifier = Modifier.width(16.dp))

    for (image in pickedImages) {
      Box(contentAlignment = Alignment.TopEnd) {
        Surface(
          shape = MaterialTheme.shapes.medium,
          tonalElevation = 1.dp,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_image_thumbnail),
            modifier = Modifier.height(80.dp),
          )
        }
        MediaPanelCloseButton { onRemoveImage(image) }
      }
    }

    for ((index, audioClip) in pickedAudioClips.withIndex()) {
      Box(contentAlignment = Alignment.TopEnd) {
        Surface(
          shape = MaterialTheme.shapes.medium,
          tonalElevation = 1.dp,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
          AudioPlaybackPanel(
            audioData = audioClip.audioData,
            sampleRate = audioClip.sampleRate,
            isRecording = false,
            modifier = Modifier.padding(end = 16.dp),
          )
        }
        MediaPanelCloseButton { onRemoveAudioClip(index) }
      }
    }

    Spacer(modifier = Modifier.width(16.dp))
  }
}
