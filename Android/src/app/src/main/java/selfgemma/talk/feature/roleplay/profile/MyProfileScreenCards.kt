package selfgemma.talk.feature.roleplay.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.performance.TrackPerformanceState

@Composable
internal fun MyProfileListContent(
  uiState: MyProfileUiState,
  contentPadding: PaddingValues,
  onEditSlot: (String) -> Unit,
  onDeleteSlot: (String) -> Unit,
  onDefaultPersonaChange: (String, Boolean) -> Unit,
) {
  val gridState = rememberLazyGridState()

  TrackPerformanceState(
    key = "PersonaCatalogGrid",
    value = if (gridState.isScrollInProgress) "scrolling" else null,
  )

  LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    state = gridState,
    modifier = Modifier.fillMaxSize().padding(contentPadding),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(uiState.personaCards, key = { it.slotId }) { persona ->
      PersonaCardItem(
        persona = persona,
        onEdit = { onEditSlot(persona.slotId) },
        onDelete = { onDeleteSlot(persona.slotId) },
        onDefaultPersonaChange = { enabled -> onDefaultPersonaChange(persona.slotId, enabled) },
        deleteEnabled = uiState.personaCards.size > 1,
      )
    }
  }
}

@Composable
internal fun PersonaCardItem(
  persona: PersonaSlotCardUiState,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onDefaultPersonaChange: (Boolean) -> Unit,
  deleteEnabled: Boolean,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val description = persona.personaDescription.ifBlank { persona.personaTitle }.ifBlank { " " }

  ElevatedCard(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onEdit),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
      Box(modifier = Modifier.fillMaxWidth()) {
        PersonaCardImagePreview(
          name = persona.personaName,
          avatarUri = persona.avatarUri,
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )

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
              enabled = deleteEnabled,
              onClick = {
                menuExpanded = false
                onDelete()
              },
            )
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
          text = persona.personaName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          minLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        FilledTonalButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = !persona.isDefault,
          onClick = { onDefaultPersonaChange(true) },
        ) {
          Text(
            text =
              if (persona.isDefault) {
                stringResource(R.string.roles_persona_picker_default_badge)
              } else {
                stringResource(R.string.my_profile_set_default_action)
              },
          )
        }
      }
    }
  }
}

@Composable
internal fun PersonaCardImagePreview(
  name: String,
  avatarUri: String?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
  val bitmapState =
    produceState<Bitmap?>(initialValue = null, avatarUri) {
      val cacheKey = avatarUri?.takeIf { it.isNotBlank() }
      if (cacheKey == null) {
        value = null
        return@produceState
      }

      PersonaCardImageCache.get(cacheKey)?.let { cachedBitmap ->
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
          PersonaCardImageCache.put(cacheKey, bitmap)
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
    RoleAvatar(
      name = name,
      avatarUri = null,
      modifier = Modifier.size(80.dp),
    )
  }
}
