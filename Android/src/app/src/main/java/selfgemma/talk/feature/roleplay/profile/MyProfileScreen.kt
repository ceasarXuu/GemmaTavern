package selfgemma.talk.feature.roleplay.profile

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.common.decodeSampledBitmapFromUri
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.feature.roleplay.common.RoleAvatar
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.common.AppEditorCard
import selfgemma.talk.ui.common.AppOutlinedTextField
import selfgemma.talk.ui.common.AppEditorSectionHeader
import selfgemma.talk.ui.common.AppSingleChoiceRow
import selfgemma.talk.ui.common.TopBarOverflowMenuButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert

private const val TAG = "MyProfileScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(
  navigateUp: () -> Unit,
  showNavigateUp: Boolean = false,
  initialSlotId: String? = null,
  startInEditMode: Boolean = false,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  viewModel: MyProfileViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val requestedSlotId = initialSlotId?.trim()?.takeIf { it.isNotBlank() }
  val directEditEntry = showNavigateUp && startInEditMode
  // Keep editor affordances transient so process/task recreation re-enters from persisted profile state.
  var editingSlotId by
    remember(requestedSlotId, startInEditMode) {
      mutableStateOf(if (startInEditMode) requestedSlotId else null)
    }
  var showCreateDialog by remember { mutableStateOf(false) }
  var newSlotId by remember { mutableStateOf("") }
  var showMenu by remember { mutableStateOf(false) }
  var pendingDeleteSlotId by remember { mutableStateOf<String?>(null) }
  var activeHelpTopic by remember { mutableStateOf<PersonaHelpTopic?>(null) }
  var avatarEditorDraft by remember { mutableStateOf<PersonaAvatarEditorDraft?>(null) }
  val isEditing = editingSlotId != null
  val avatarLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri == null) {
        Log.d(TAG, "persona avatar picker cancelled")
        return@rememberLauncherForActivityResult
      }
      takeReadPermission(context = context, uri = uri)
      avatarEditorDraft = PersonaAvatarEditorDraft(sourceUri = uri.toString())
      Log.d(TAG, "persona avatar picked uri=$uri")
    }
  LaunchedEffect(requestedSlotId) {
    if (requestedSlotId != null && uiState.avatarSlotId != requestedSlotId) {
      viewModel.selectAvatarSlot(requestedSlotId)
      Log.d(TAG, "applied externally requested persona slot slotId=$requestedSlotId")
    }
  }
  LaunchedEffect(startInEditMode, requestedSlotId, uiState.avatarSlotId, editingSlotId) {
    if (!startInEditMode || editingSlotId != null) {
      return@LaunchedEffect
    }
    val targetSlotId = requestedSlotId ?: uiState.avatarSlotId.ifBlank { null } ?: return@LaunchedEffect
    if (uiState.avatarSlotId != targetSlotId) {
      viewModel.selectAvatarSlot(targetSlotId)
    }
    editingSlotId = targetSlotId
    Log.d(TAG, "entered persona editor from external route slotId=$targetSlotId")
  }
  val handleNavigateUp: () -> Unit = {
    if (isEditing) {
      if (directEditEntry) {
        Log.d(TAG, "navigate up from direct persona editor entry slotId=$editingSlotId")
        navigateUp()
      } else {
        Log.d(TAG, "return from persona editor to persona list")
        editingSlotId = null
      }
    } else {
      Log.d(TAG, "navigate up from my profile")
      navigateUp()
    }
  }

  BackHandler(enabled = showNavigateUp || isEditing) { handleNavigateUp() }

  Scaffold(
    modifier = modifier,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      AppTopBar(
        title =
          if (isEditing) {
            uiState.personaName.ifBlank { editingSlotId ?: uiState.avatarSlotId.ifBlank { stringResource(R.string.tab_me) } }
          } else {
            stringResource(R.string.tab_me)
          },
        leftAction =
          if (showNavigateUp || isEditing) {
            AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = handleNavigateUp)
          } else {
            null
          },
        rightAction =
          if (isEditing) {
            AppBarAction(
              actionType = AppBarActionType.NAVIGATE_UP,
              actionFn = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                viewModel.saveProfile()
                if (directEditEntry) {
                  Log.d(TAG, "saved persona and closed direct entry slotId=$editingSlotId")
                  navigateUp()
                } else {
                  Log.d(TAG, "saved persona and returned to persona list")
                  editingSlotId = null
                }
              },
              label = stringResource(R.string.save),
            )
          } else {
            null
          },
        rightActionContent =
          if (!isEditing) {
            {
              TopBarOverflowMenuButton(
                expanded = showMenu,
                onExpandedChange = { showMenu = it },
              ) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.create)) },
                  onClick = {
                    showMenu = false
                    newSlotId = ""
                    showCreateDialog = true
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.reset)) },
                  onClick = {
                    showMenu = false
                    editingSlotId = null
                    viewModel.resetProfile()
                  },
                )
              }
            }
          } else {
            null
          },
      )
    },
  ) { innerPadding ->
    val combinedPadding = PaddingValues(
      top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
      bottom = contentPadding.calculateBottomPadding(),
      start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
      end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    )

    if (isEditing) {
      MyProfileEditorContent(
        uiState = uiState,
        contentPadding = combinedPadding,
        onPersonaNameChange = viewModel::updatePersonaName,
        onPersonaDescriptionChange = viewModel::updatePersonaDescription,
        onAvatarClick = {
          val currentAvatarUri = uiState.avatarUri
          if (currentAvatarUri.isNullOrBlank()) {
            Log.d(TAG, "open persona avatar picker for empty avatar slot=${uiState.avatarSlotId}")
            avatarLauncher.launch(arrayOf("image/*"))
          } else {
            val sourceUri = uiState.avatarEditorSourceUri ?: currentAvatarUri
            Log.d(
              TAG,
              "open persona avatar editor slot=${uiState.avatarSlotId} uri=$currentAvatarUri sourceUri=$sourceUri zoom=${uiState.avatarCropZoom}",
            )
            avatarEditorDraft =
              PersonaAvatarEditorDraft(
                sourceUri = sourceUri,
                zoom = uiState.avatarCropZoom,
                offsetX = uiState.avatarCropOffsetX,
                offsetY = uiState.avatarCropOffsetY,
              )
          }
        },
        onPersonaPositionChange = viewModel::updatePersonaPosition,
        onPersonaDepthChange = viewModel::updatePersonaDepth,
        onPersonaRoleChange = viewModel::updatePersonaRole,
        onShowHelp = { topic ->
          Log.d(TAG, "open persona help topic=$topic")
          activeHelpTopic = topic
        },
      )
    } else {
      MyProfileListContent(
        uiState = uiState,
        contentPadding = combinedPadding,
        onEditSlot = { slotId ->
          viewModel.selectAvatarSlot(slotId)
          editingSlotId = slotId
        },
        onDeleteSlot = { slotId -> pendingDeleteSlotId = slotId },
        onDefaultPersonaChange = viewModel::setDefaultPersona,
      )
    }

    if (showCreateDialog) {
      CreatePersonaSlotDialog(
        slotId = newSlotId,
        onSlotIdChange = { newSlotId = it },
        onDismiss = { showCreateDialog = false },
        onCreate = {
          val normalizedSlotId = newSlotId.trim()
          if (normalizedSlotId.isNotBlank()) {
            viewModel.createAvatarSlot(normalizedSlotId)
            editingSlotId = normalizedSlotId
            newSlotId = ""
            showCreateDialog = false
          }
        },
      )
    }

    val personaToDelete = uiState.personaCards.firstOrNull { it.slotId == pendingDeleteSlotId }
    if (personaToDelete != null) {
      ConfirmDeletePersonaDialog(
        personaName = personaToDelete.personaName,
        onDismiss = { pendingDeleteSlotId = null },
        onConfirm = {
          viewModel.deleteAvatarSlot(personaToDelete.slotId)
          pendingDeleteSlotId = null
        },
      )
    }

    val helpTopic = activeHelpTopic
    if (helpTopic != null) {
      PersonaHelpDialog(
        topic = helpTopic,
        onDismiss = { activeHelpTopic = null },
      )
    }

    val avatarDraft = avatarEditorDraft
    if (avatarDraft != null) {
      PersonaAvatarEditorDialog(
        draft = avatarDraft,
        onDismiss = { avatarEditorDraft = null },
        onPickReplacement = { avatarLauncher.launch(arrayOf("image/*")) },
        onClearAvatar = {
          viewModel.updateAvatarEditState(
            avatarUri = null,
            avatarEditorSourceUri = null,
            avatarCropZoom = 1f,
            avatarCropOffsetX = 0f,
            avatarCropOffsetY = 0f,
          )
          avatarEditorDraft = null
        },
        onSave = { bitmap, savedDraft ->
          val savedUri = savePersonaAvatarBitmap(context, uiState.avatarSlotId, bitmap)
          viewModel.updateAvatarEditState(
            avatarUri = savedUri,
            avatarEditorSourceUri = savedDraft.sourceUri,
            avatarCropZoom = savedDraft.zoom,
            avatarCropOffsetX = savedDraft.offsetX,
            avatarCropOffsetY = savedDraft.offsetY,
          )
          avatarEditorDraft = null
        },
      )
    }
  }
}

