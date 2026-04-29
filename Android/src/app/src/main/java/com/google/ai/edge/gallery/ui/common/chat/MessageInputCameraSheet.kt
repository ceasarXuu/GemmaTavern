package selfgemma.talk.ui.common.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import selfgemma.talk.R
import java.util.concurrent.Executors

private const val TAG = "AGMessageInputCameraSheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInputCameraCaptureSheet(
  sheetState: SheetState,
  onDismiss: () -> Unit,
  hasFrontCamera: Boolean,
  scope: CoroutineScope,
  sensorRotationProvider: () -> Int,
  onImagesCaptured: (List<Bitmap>) -> Unit,
) {
  ModalBottomSheet(sheetState = sheetState, onDismissRequest = onDismiss) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewUseCase = remember { androidx.camera.core.Preview.Builder().build() }
    val imageCaptureUseCase = remember {
      val preferredSize = Size(512, 512)
      val resolutionStrategy =
        ResolutionStrategy(
          preferredSize,
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        )
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(resolutionStrategy)
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()

      ImageCapture.Builder().setResolutionSelector(resolutionSelector).build()
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    val localContext = LocalContext.current
    var cameraSide by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    fun rebindCameraProvider() {
      cameraProvider?.let { cameraProvider ->
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraSide).build()
        try {
          cameraProvider.unbindAll()
          val camera =
            cameraProvider.bindToLifecycle(
              lifecycleOwner = lifecycleOwner,
              cameraSelector = cameraSelector,
              previewUseCase,
              imageCaptureUseCase,
            )
          cameraControl = camera.cameraControl
        } catch (e: Exception) {
          Log.d(TAG, "Failed to bind camera", e)
        }
      }
    }

    LaunchedEffect(Unit) {
      cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
      rebindCameraProvider()
    }

    LaunchedEffect(cameraSide) { rebindCameraProvider() }

    DisposableEffect(Unit) {
      onDispose {
        cameraProvider?.unbindAll()
        if (!executor.isShutdown) {
          executor.shutdown()
        }
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          PreviewView(ctx).also {
            previewUseCase.surfaceProvider = it.surfaceProvider
            rebindCameraProvider()
          }
        },
      )

      IconButton(
        onClick = {
          scope.launch {
            sheetState.hide()
            onDismiss()
          }
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
          ),
        modifier = Modifier.offset(x = (-8).dp, y = 8.dp).align(Alignment.TopEnd),
      ) {
        Icon(
          Icons.Rounded.Close,
          contentDescription = stringResource(R.string.cd_close_icon),
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      IconButton(
        colors =
          IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .padding(bottom = 32.dp)
            .size(size = 64.dp)
            .border(width = 2.dp, color = MaterialTheme.colorScheme.onPrimary, CircleShape),
        onClick = {
          val callback =
            object : ImageCapture.OnImageCapturedCallback() {
              override fun onCaptureSuccess(image: ImageProxy) {
                try {
                  var bitmap = image.toBitmap()
                  val rotation = sensorRotationProvider() + image.imageInfo.rotationDegrees
                  bitmap =
                    if (rotation != 0) {
                      val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                      Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else bitmap
                  bitmap = resizeBitmap(originalBitmap = bitmap)
                  onImagesCaptured(listOf(bitmap))
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to process image", e)
                } finally {
                  image.close()
                  scope.launch {
                    sheetState.hide()
                    onDismiss()
                  }
                }
              }
            }
          imageCaptureUseCase.takePicture(executor, callback)
        },
      ) {
        Icon(
          Icons.Rounded.PhotoCamera,
          contentDescription = stringResource(R.string.cd_camera_shutter_icon),
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(36.dp),
        )
      }

      if (hasFrontCamera) {
        IconButton(
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
          modifier =
            Modifier.align(Alignment.BottomEnd).padding(bottom = 40.dp, end = 32.dp).size(48.dp),
          onClick = {
            cameraSide =
              when (cameraSide) {
                CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_FRONT
                else -> CameraSelector.LENS_FACING_BACK
              }
          },
        ) {
          Icon(
            Icons.Rounded.FlipCameraAndroid,
            contentDescription = stringResource(R.string.cd_toggle_front_back_camera_icon),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
  }
}
