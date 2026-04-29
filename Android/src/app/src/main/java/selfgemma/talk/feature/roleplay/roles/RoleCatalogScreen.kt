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

