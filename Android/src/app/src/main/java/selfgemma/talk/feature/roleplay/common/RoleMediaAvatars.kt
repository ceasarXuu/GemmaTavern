package selfgemma.talk.feature.roleplay.common

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.common.decodeSampledBitmapFromUri

private object RoleAvatarBitmapCache {
  private val cache = LruCache<String, Bitmap>(48)

  fun get(key: String): Bitmap? = cache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    cache.put(key, bitmap)
  }
}

@Composable
fun RoleAvatar(
  name: String,
  avatarUri: String?,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val avatarModifier =
    modifier
      .clip(CircleShape)
      .let { currentModifier ->
        if (onClick != null) {
          currentModifier.clickable(onClick = onClick)
        } else {
          currentModifier
        }
      }
  val bitmapState =
    produceState<Bitmap?>(initialValue = null, avatarUri) {
      val cacheKey = avatarUri?.takeIf { it.isNotBlank() }
      if (cacheKey == null) {
        value = null
        return@produceState
      }

      RoleAvatarBitmapCache.get(cacheKey)?.let { cachedBitmap ->
        value = cachedBitmap
        return@produceState
      }

      value =
        withContext(Dispatchers.IO) {
          runCatching {
            decodeSampledBitmapFromUri(
              context = context,
              uri = Uri.parse(cacheKey),
              reqWidth = 128,
              reqHeight = 128,
            )
          }.getOrNull()
        }?.also { bitmap ->
          RoleAvatarBitmapCache.put(cacheKey, bitmap)
        }
    }

  if (bitmapState.value != null) {
    Image(
      bitmap = checkNotNull(bitmapState.value).asImageBitmap(),
      contentDescription = null,
      modifier = avatarModifier,
      contentScale = ContentScale.Crop,
    )
    return
  }

  Box(
    modifier =
      avatarModifier
        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = name.trim().firstOrNull()?.uppercase() ?: "?",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}
