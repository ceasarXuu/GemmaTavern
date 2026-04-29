package selfgemma.talk.ui.common.chat


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import selfgemma.talk.R
import selfgemma.talk.common.AudioClip
import selfgemma.talk.common.calculatePeakAmplitude
import selfgemma.talk.common.decodeSampledBitmapFromUri
import selfgemma.talk.common.rotateBitmap
import selfgemma.talk.data.MAX_AUDIO_CLIP_DURATION_SEC
import selfgemma.talk.data.MAX_AUDIO_CLIP_COUNT
import selfgemma.talk.data.MAX_IMAGE_COUNT
import selfgemma.talk.data.RuntimeType
import selfgemma.talk.data.SAMPLE_RATE
import selfgemma.talk.data.Task
import selfgemma.talk.ui.theme.customColors
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGMessageInputTextHelpers"

@Composable
internal fun MediaPanelCloseButton(onClicked: () -> Unit) {
  Box(
    modifier =
      Modifier.offset(x = 10.dp, y = (-10).dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surface)
        .border((1.5).dp, MaterialTheme.colorScheme.outline, CircleShape)
        .clickable { onClicked() }
  ) {
    Icon(
      Icons.Rounded.Close,
      contentDescription = stringResource(R.string.cd_delete_icon),
      modifier = Modifier.padding(3.dp).size(16.dp),
    )
  }
}

internal fun handleImagesSelected(
  context: Context,
  uris: List<Uri>,
  onImagesSelected: (List<Bitmap>) -> Unit,
) {
  val images: MutableList<Bitmap> = mutableListOf()
  for (uri in uris) {
    val bitmap: Bitmap? =
      try {
        val inputStream =
          if (uri.scheme == null || uri.scheme == "file") {
            FileInputStream(uri.path ?: "")
          } else {
            context.contentResolver.openInputStream(uri)
          }
        if (inputStream != null) {
          // Read the EXIF metadata from the picture and rotate it correctly.
          val exif = ExifInterface(inputStream)
          val orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          // You MUST close the first input stream before opening another one on the same URI.
          inputStream.close()

          // The let block will now return the rotated bitmap
          decodeSampledBitmapFromUri(context, uri, 1024, 1024)?.let { originalBitmap ->
            rotateBitmap(bitmap = originalBitmap, orientation = orientation)
          }
        } else {
          null
        }
      } catch (e: Exception) {
        e.printStackTrace()
        null
      }
    if (bitmap != null) {
      images.add(bitmap)
    }
  }
  if (images.isNotEmpty()) {
    onImagesSelected(images)
  }
}

/**
 * Resizes a given Bitmap to fit within a square of a specified size, while maintaining its original
 * aspect ratio.
 */
internal fun resizeBitmap(originalBitmap: Bitmap, size: Int = 1024): Bitmap {
  val originalWidth = originalBitmap.width
  val originalHeight = originalBitmap.height

  // Return the original bitmap if it's already within the specified size.
  if (originalWidth <= size && originalHeight <= size) {
    return originalBitmap
  }

  val aspectRatio: Float = originalWidth.toFloat() / originalHeight.toFloat()
  val newWidth: Int
  val newHeight: Int

  if (aspectRatio > 1) {
    // Landscape or square orientation
    newWidth = size
    newHeight = (size / aspectRatio).toInt()
  } else {
    // Portrait orientation
    newHeight = size
    newWidth = (size * aspectRatio).toInt()
  }

  Log.d(TAG, "Resizing image from $originalWidth x $originalHeight to $newWidth x $newHeight")

  // Create a new scaled bitmap using the calculated dimensions
  return originalBitmap.scale(newWidth, newHeight)
}

internal fun checkFrontCamera(context: Context, callback: (Boolean) -> Unit) {
  val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
  cameraProviderFuture.addListener(
    {
      val cameraProvider = cameraProviderFuture.get()
      try {
        // Attempt to select the default front camera
        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        callback(hasFront)
      } catch (e: Exception) {
        e.printStackTrace()
        callback(false)
      }
    },
    ContextCompat.getMainExecutor(context),
  )
}

internal fun createMessagesToSend(
  pickedImages: List<Bitmap>,
  audioClips: List<AudioClip>,
  text: String,
): List<ChatMessage> {
  val messages: MutableList<ChatMessage> = mutableListOf()

  // Add image message.
  if (pickedImages.isNotEmpty()) {
    // Cap the number of image messages.
    var curPickedImages = pickedImages.toList()
    if (curPickedImages.size > MAX_IMAGE_COUNT) {
      curPickedImages = curPickedImages.subList(fromIndex = 0, toIndex = MAX_IMAGE_COUNT)
    }
    messages.add(
      ChatMessageImage(
        bitmaps = curPickedImages,
        imageBitMaps = curPickedImages.map { it.asImageBitmap() },
        side = ChatSide.USER,
      )
    )
  }

  // Add audio messages.
  var audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
  if (audioClips.isNotEmpty()) {
    for (audioClip in audioClips) {
      audioMessages.add(
        ChatMessageAudioClip(
          audioData = audioClip.audioData,
          sampleRate = audioClip.sampleRate,
          side = ChatSide.USER,
        )
      )
    }
  }
  // Cap the number of audio messages.
  if (audioMessages.size > MAX_AUDIO_CLIP_COUNT) {
    audioMessages = audioMessages.subList(fromIndex = 0, toIndex = MAX_AUDIO_CLIP_COUNT)
  }
  messages.addAll(audioMessages)

  if (text.isNotEmpty()) {
    messages.add(ChatMessageText(content = text, side = ChatSide.USER))
  }

  return messages
}

/**
 * A private class that acts as a LifecycleObserver to monitor sensor events for a device's
 * orientation, specifically using the accelerometer.
 *
 * This observer registers for accelerometer events in `onResume` and unregisters in `onPause` to
 * conserve battery and resources. It calculates the device's rotation (0, 90, 180, -90) by checking
 * if the acceleration on the X or Y axis exceeds a threshold of 7.0 m/s^2, which corresponds to
 * gravity's pull when the device is held in a cardinal direction. A 'dead zone' is used to prevent
 * the rotation from "chattering" when the device is held at an angle between the cardinal
 * directions.
 */
internal class SensorObserver(context: Context) : DefaultLifecycleObserver, SensorEventListener {
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  var currentRotation = 0

  override fun onResume(owner: LifecycleOwner) {
    super.onResume(owner)
    accelerometer?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun onPause(owner: LifecycleOwner) {
    super.onPause(owner)
    sensorManager.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
      val x = event.values[0]
      val y = event.values[1]

      // When the phone is on its side, gravity acts primarily along the x-axis.
      // When the phone is upright, gravity acts primarily along the y-axis.
      val newOrientation =
        when {
          x < -7.0 -> 90
          x > 7.0 -> -90
          y < -7.0 -> 180
          y > 7.0 -> 0
          else -> currentRotation // Keep the last known orientation
        }

      if (newOrientation != currentRotation) {
        currentRotation = newOrientation
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
