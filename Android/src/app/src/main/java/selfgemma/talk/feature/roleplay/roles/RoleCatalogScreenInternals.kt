package selfgemma.talk.feature.roleplay.roles

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.R
import selfgemma.talk.common.decodeSampledBitmapFromUri
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.coverImageUri
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.feature.roleplay.common.RoleAvatar

internal object RoleCatalogImageCache {
  private val cache = LruCache<String, Bitmap>(24)

  fun get(key: String): Bitmap? = cache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    cache.put(key, bitmap)
  }
}

internal data class PendingPersonaSelectionState(
  val roleId: String,
  val modelId: String,
  val personas: List<SessionPersonaOptionUiState>,
)

@Composable
internal fun RoleCardItem(
  role: RoleCard,
  onStart: (() -> Unit)? = null,
  onOpen: (() -> Unit)? = null,
  onDelete: (() -> Unit)? = null,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val description =
    role.summary
      .ifBlank { role.personaDescription }
      .ifBlank { role.worldSettings }
      .ifBlank { " " }
  val cardModifier =
    Modifier.fillMaxWidth().let { baseModifier ->
      if (onOpen != null) {
        baseModifier.clickable(onClick = onOpen)
      } else {
        baseModifier
      }
    }

  ElevatedCard(modifier = cardModifier) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        RoleCardImagePreview(
          name = role.name,
          imageUri = role.coverImageUri() ?: role.primaryAvatarUri(),
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )

        if (onDelete != null) {
          Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
          ) {
            IconButton(
              onClick = { menuExpanded = true },
              modifier = Modifier.size(36.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.cd_menu),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
              )
            }

            DropdownMenu(
              expanded = menuExpanded,
              onDismissRequest = { menuExpanded = false },
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                  menuExpanded = false
                  onDelete()
                },
              )
            }
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = role.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          minLines = 3,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        FilledTonalButton(
          onClick = { onStart?.invoke() },
          enabled = onStart != null,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.roles_start_session))
        }
      }
    }
  }
}

@Composable
internal fun MissingModelDialog(
  onDismiss: () -> Unit,
  onOpenModelLibrary: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.roles_missing_model_title)) },
    text = { Text(stringResource(R.string.roles_missing_model_content)) },
    confirmButton = {
      FilledTonalButton(onClick = onOpenModelLibrary) {
        Text(stringResource(R.string.roles_missing_model_confirm))
      }
    },
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
internal fun RoleCardImagePreview(
  name: String,
  imageUri: String?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
  val bitmapState =
    produceState<Bitmap?>(initialValue = null, imageUri) {
      val cacheKey = imageUri?.takeIf { it.isNotBlank() }
      if (cacheKey == null) {
        value = null
        return@produceState
      }

      RoleCatalogImageCache.get(cacheKey)?.let { cachedBitmap ->
        value = cachedBitmap
        return@produceState
      }

      value =
        withContext(Dispatchers.IO) {
          runCatching {
            decodeSampledBitmapFromUri(
              context = context,
              uri = Uri.parse(cacheKey),
              reqWidth = 512,
              reqHeight = 512,
            )
          }.getOrNull()
        }?.also { bitmap ->
          RoleCatalogImageCache.put(cacheKey, bitmap)
        }
    }

  if (bitmapState.value != null) {
    Image(
      bitmap = checkNotNull(bitmapState.value).asImageBitmap(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = modifier.clip(shape),
    )
    return
  }

  Box(
    modifier =
      modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RoleAvatar(
        name = name,
        avatarUri = null,
        modifier = Modifier.size(72.dp),
      )
      Text(
        text = stringResource(R.string.role_catalog_no_avatar),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun PersonaSelectionDialog(
  personas: List<SessionPersonaOptionUiState>,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.roles_persona_picker_title)) },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = stringResource(R.string.roles_persona_picker_message),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(
            count = personas.size,
            key = { index -> personas[index].slotId },
          ) { index ->
            val persona = personas[index]
            PersonaSelectionCard(
              persona = persona,
              onClick = { onSelect(persona.slotId) },
            )
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      OutlinedButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
internal fun PersonaSelectionCard(
  persona: SessionPersonaOptionUiState,
  onClick: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (persona.isDefault) {
            MaterialTheme.colorScheme.secondaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerLow
          },
      ),
  ) {
    ListItem(
      leadingContent = {
        RoleAvatar(
          name = persona.name,
          avatarUri = persona.avatarUri,
          modifier = Modifier.size(48.dp),
        )
      },
      headlineContent = {
        Text(
          text = persona.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      supportingContent = {
        if (persona.descriptionPreview.isNotBlank()) {
          Text(
            text = persona.descriptionPreview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      },
      trailingContent = {
        if (persona.isDefault) {
          AssistChip(
            onClick = onClick,
            label = { Text(stringResource(R.string.roles_persona_picker_default_badge)) },
          )
        }
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
  }
}
