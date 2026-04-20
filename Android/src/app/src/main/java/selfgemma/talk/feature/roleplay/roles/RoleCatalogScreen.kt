package selfgemma.talk.feature.roleplay.roles

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.common.decodeSampledBitmapFromUri
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.coverImageUri
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.common.TopBarOverflowMenuButton
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "RoleCatalogScreen"

private object RoleCatalogImageCache {
  private val cache = LruCache<String, Bitmap>(24)

  fun get(key: String): Bitmap? = cache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    cache.put(key, bitmap)
  }
}

private data class PendingPersonaSelectionState(
  val roleId: String,
  val modelId: String,
  val personas: List<SessionPersonaOptionUiState>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleCatalogScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  onOpenChat: (String) -> Unit,
  onOpenModelLibrary: () -> Unit,
  onCreateRole: () -> Unit,
  onEditRole: (String) -> Unit,
  showNavigateUp: Boolean = false,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  viewModel: RoleCatalogViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val scope = rememberCoroutineScope()
  val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
  val defaultModelId = downloadedModels.firstOrNull()?.name
  val allRoles =
    remember(uiState.builtInRoles, uiState.customRoles) {
      uiState.builtInRoles + uiState.customRoles
    }
  var pendingDeleteRoleId by rememberSaveable { mutableStateOf<String?>(null) }
  val gridState = rememberLazyGridState()
  var showMenu by rememberSaveable { mutableStateOf(false) }
  var pendingPersonaSelection by remember { mutableStateOf<PendingPersonaSelectionState?>(null) }
  var showMissingModelDialog by rememberSaveable { mutableStateOf(false) }

  val importLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      uri?.let { viewModel.importStRoleCard(it.toString()) }
    }

  TrackPerformanceState(
    key = "RoleCatalogGrid",
    value = if (gridState.isScrollInProgress) "scrolling" else null,
  )

  val handleNavigateUp: () -> Unit = {
    if (pendingPersonaSelection != null) {
      pendingPersonaSelection = null
      Log.d(TAG, "dismiss persona picker before navigating up")
    } else if (showMissingModelDialog) {
      showMissingModelDialog = false
      Log.d(TAG, "dismiss missing model dialog before navigating up")
    } else if (pendingDeleteRoleId != null) {
      pendingDeleteRoleId = null
      Log.d(TAG, "dismiss delete dialog before navigating up")
    } else {
      Log.d(TAG, "navigate up from role catalog")
      navigateUp()
    }
  }

  BackHandler(enabled = showNavigateUp) {
    handleNavigateUp()
  }

  val handleStartSession: (String, String) -> Unit = { roleId, modelId ->
    val personaOptions = viewModel.getSessionPersonaOptions()
    if (personaOptions.size <= 1) {
      scope.launch {
        val sessionId =
          viewModel.createSession(
            roleId = roleId,
            modelId = modelId,
            personaSlotId = personaOptions.firstOrNull()?.slotId,
          )
        onOpenChat(sessionId)
      }
    } else {
      pendingPersonaSelection =
        PendingPersonaSelectionState(
          roleId = roleId,
          modelId = modelId,
          personas = personaOptions,
        )
      Log.d(TAG, "prompt persona picker roleId=$roleId modelId=$modelId personaCount=${personaOptions.size}")
    }
  }

  Scaffold(
    modifier = modifier.semantics { testTagsAsResourceId = true },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      AppTopBar(
        title = stringResource(R.string.tab_roles),
        leftAction =
          if (showNavigateUp) {
            AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = handleNavigateUp)
          } else {
            null
          },
        rightActionContent = {
          TopBarOverflowMenuButton(
            expanded = showMenu,
            onExpandedChange = { showMenu = it },
          ) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.roles_menu_create)) },
              onClick = {
                showMenu = false
                onCreateRole()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.roles_menu_import)) },
              onClick = {
                showMenu = false
                importLauncher.launch("*/*")
              },
            )
          }
        },
      )
    },
  ) { innerPadding ->
    val combinedPadding =
      PaddingValues(
        top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
        start = contentPadding.calculateLeftPadding(LayoutDirection.Ltr),
        end = contentPadding.calculateRightPadding(LayoutDirection.Ltr),
      )

    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      state = gridState,
      modifier = Modifier.fillMaxSize().padding(combinedPadding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      uiState.errorMessage?.let { errorMessage ->
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
          Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      uiState.statusMessage?.let { statusMessage ->
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
          Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      items(allRoles, key = { it.id }) { role ->
        RoleCardItem(
          role = role,
          onStart = {
            val modelId = defaultModelId
            if (modelId != null) {
              handleStartSession(role.id, modelId)
            } else {
              showMissingModelDialog = true
              Log.d(TAG, "prompt missing model dialog for roleId=${role.id}")
            }
          },
          onOpen = if (role.builtIn) null else ({ onEditRole(role.id) }),
          onDelete = if (role.builtIn) null else ({ pendingDeleteRoleId = role.id }),
        )
      }
    }

    val roleToDelete = uiState.customRoles.firstOrNull { it.id == pendingDeleteRoleId }
    if (roleToDelete != null) {
      AlertDialog(
        onDismissRequest = { pendingDeleteRoleId = null },
        title = { Text(stringResource(R.string.roles_delete_title)) },
        text = {
          Text(stringResource(R.string.roles_delete_content, roleToDelete.name))
        },
        confirmButton = {
          FilledTonalButton(
            onClick = {
              viewModel.deleteRole(roleToDelete.id)
              pendingDeleteRoleId = null
            },
          ) {
            Text(stringResource(R.string.delete))
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { pendingDeleteRoleId = null }) {
            Text(stringResource(R.string.cancel))
          }
        },
      )
    }

    if (showMissingModelDialog) {
      MissingModelDialog(
        onDismiss = {
          showMissingModelDialog = false
          Log.d(TAG, "dismiss missing model dialog")
        },
        onOpenModelLibrary = {
          showMissingModelDialog = false
          Log.d(TAG, "open model library from missing model dialog")
          onOpenModelLibrary()
        },
      )
    }

    val personaSelection = pendingPersonaSelection
    if (personaSelection != null) {
      PersonaSelectionDialog(
        personas = personaSelection.personas,
        onDismiss = { pendingPersonaSelection = null },
        onSelect = { slotId ->
          pendingPersonaSelection = null
          scope.launch {
            val sessionId =
              viewModel.createSession(
                roleId = personaSelection.roleId,
                modelId = personaSelection.modelId,
                personaSlotId = slotId,
              )
            onOpenChat(sessionId)
          }
        },
      )
    }
  }
}

@Composable
private fun RoleCardItem(
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
private fun MissingModelDialog(
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
private fun RoleCardImagePreview(
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
private fun PersonaSelectionDialog(
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
private fun PersonaSelectionCard(
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
