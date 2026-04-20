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
private const val PERSONA_NAME_MAX_CHARS = 120
private const val PERSONA_DESCRIPTION_MAX_CHARS = 600
private const val PERSONA_DEPTH_MAX_CHARS = 4

private object PersonaCardImageCache {
  private val cache = LruCache<String, Bitmap>(24)

  fun get(key: String): Bitmap? = cache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    cache.put(key, bitmap)
  }
}

private data class PersonaTextFieldSpec(
  val maxChars: Int? = null,
)

private enum class PersonaHelpTopic(val titleRes: Int, val bodyRes: Int) {
  AVATAR(R.string.my_profile_avatar_title, R.string.my_profile_help_avatar_body),
  NAME(R.string.my_profile_persona_name_title, R.string.my_profile_help_name_body),
  DESCRIPTION(R.string.my_profile_persona_description_title, R.string.my_profile_help_description_body),
  POSITION(R.string.my_profile_persona_position_title, R.string.my_profile_help_position_body),
  DEPTH(R.string.my_profile_persona_depth_title, R.string.my_profile_help_depth_body),
  ROLE(R.string.my_profile_persona_role_title, R.string.my_profile_help_role_body),
}

private fun personaTextFieldSpec(topic: PersonaHelpTopic?): PersonaTextFieldSpec? =
  when (topic) {
    PersonaHelpTopic.NAME -> PersonaTextFieldSpec(maxChars = PERSONA_NAME_MAX_CHARS)
    PersonaHelpTopic.DESCRIPTION -> PersonaTextFieldSpec(maxChars = PERSONA_DESCRIPTION_MAX_CHARS)
    PersonaHelpTopic.DEPTH -> PersonaTextFieldSpec(maxChars = PERSONA_DEPTH_MAX_CHARS)
    else -> null
  }

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

@Composable
private fun MyProfileListContent(
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
private fun PersonaCardItem(
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
private fun PersonaCardImagePreview(
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

@Composable
private fun ConfirmDeletePersonaDialog(
  personaName: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.my_profile_delete_title)) },
    text = {
      Text(
        stringResource(
          R.string.my_profile_delete_content,
          personaName,
        ),
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(R.string.delete))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun MyProfileEditorContent(
  uiState: MyProfileUiState,
  contentPadding: PaddingValues,
  onPersonaNameChange: (String) -> Unit,
  onPersonaDescriptionChange: (String) -> Unit,
  onAvatarClick: () -> Unit,
  onPersonaPositionChange: (StPersonaDescriptionPosition) -> Unit,
  onPersonaDepthChange: (String) -> Unit,
  onPersonaRoleChange: (Int) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(contentPadding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    PersonaAvatarCard(
      name = uiState.personaName,
      avatarUri = uiState.avatarUri,
      onAvatarClick = onAvatarClick,
      onShowHelp = onShowHelp,
    )
    EditorCard(
      title = stringResource(R.string.my_profile_persona_name_title),
      helpTopic = PersonaHelpTopic.NAME,
      onShowHelp = onShowHelp,
    ) {
      PersonaOutlinedTextField(
        value = uiState.personaName,
        onValueChange = onPersonaNameChange,
        modifier = Modifier.fillMaxWidth(),
        maxLines = 1,
        helpTopic = PersonaHelpTopic.NAME,
      )
    }
    EditorCard(
      title = stringResource(R.string.my_profile_persona_description_title),
      helpTopic = PersonaHelpTopic.DESCRIPTION,
      onShowHelp = onShowHelp,
    ) {
      PersonaOutlinedTextField(
        value = uiState.personaDescription,
        onValueChange = onPersonaDescriptionChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8,
        helpTopic = PersonaHelpTopic.DESCRIPTION,
      )
    }
    PersonaPositionCard(
      selected = uiState.personaPosition,
      onSelected = onPersonaPositionChange,
      onShowHelp = onShowHelp,
    )
    if (uiState.personaPosition == StPersonaDescriptionPosition.AT_DEPTH) {
      EditorCard(
        title = stringResource(R.string.my_profile_persona_depth_title),
        helpTopic = PersonaHelpTopic.DEPTH,
        onShowHelp = onShowHelp,
      ) {
        PersonaOutlinedTextField(
          value = uiState.personaDepth,
          onValueChange = onPersonaDepthChange,
          modifier = Modifier.fillMaxWidth(),
          maxLines = 1,
          helpTopic = PersonaHelpTopic.DEPTH,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      }
      PersonaRoleCard(
        selectedRole = uiState.personaRole,
        onSelected = onPersonaRoleChange,
        onShowHelp = onShowHelp,
      )
    }
  }
}

@Composable
private fun CreatePersonaSlotDialog(
  slotId: String,
  onSlotIdChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onCreate: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("${stringResource(R.string.create)} ${stringResource(R.string.my_profile_avatar_slot_title)}")
    },
    text = {
      AppOutlinedTextField(
        value = slotId,
        onValueChange = onSlotIdChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.my_profile_avatar_slot_new_label)) },
      )
    },
    confirmButton = {
      TextButton(
        enabled = slotId.trim().isNotBlank(),
        onClick = onCreate,
      ) {
        Text(stringResource(R.string.create))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.cancel))
      }
    },
  )
}

@Composable
private fun PersonaPositionCard(
  selected: StPersonaDescriptionPosition,
  onSelected: (StPersonaDescriptionPosition) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth().selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_persona_position_title),
        onShowHelp = { onShowHelp(PersonaHelpTopic.POSITION) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_in_prompt),
        selected = selected == StPersonaDescriptionPosition.IN_PROMPT,
        onClick = { onSelected(StPersonaDescriptionPosition.IN_PROMPT) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_top_an),
        selected = selected == StPersonaDescriptionPosition.TOP_AN,
        onClick = { onSelected(StPersonaDescriptionPosition.TOP_AN) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_bottom_an),
        selected = selected == StPersonaDescriptionPosition.BOTTOM_AN,
        onClick = { onSelected(StPersonaDescriptionPosition.BOTTOM_AN) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_at_depth),
        selected = selected == StPersonaDescriptionPosition.AT_DEPTH,
        onClick = { onSelected(StPersonaDescriptionPosition.AT_DEPTH) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_position_none),
        selected = selected == StPersonaDescriptionPosition.NONE,
        onClick = { onSelected(StPersonaDescriptionPosition.NONE) },
      )
    }
  }
}

@Composable
private fun PersonaRoleCard(
  selectedRole: Int,
  onSelected: (Int) -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth().selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_persona_role_title),
        onShowHelp = { onShowHelp(PersonaHelpTopic.ROLE) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_system),
        selected = selectedRole == 0,
        onClick = { onSelected(0) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_user),
        selected = selectedRole == 1,
        onClick = { onSelected(1) },
      )
      AppSingleChoiceRow(
        title = stringResource(R.string.my_profile_persona_role_assistant),
        selected = selectedRole == 2,
        onClick = { onSelected(2) },
      )
    }
  }
}

@Composable
private fun EditorCard(
  title: String,
  helpTopic: PersonaHelpTopic? = null,
  onShowHelp: ((PersonaHelpTopic) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  AppEditorCard {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      AppEditorSectionHeader(
        title = title,
        onShowHelp =
          if (helpTopic != null && onShowHelp != null) {
            { onShowHelp(helpTopic) }
          } else {
            null
          },
      )
      content()
    }
  }
}

@Composable
private fun PersonaAvatarCard(
  name: String,
  avatarUri: String?,
  onAvatarClick: () -> Unit,
  onShowHelp: (PersonaHelpTopic) -> Unit,
) {
  AppEditorCard {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AppEditorSectionHeader(
        title = stringResource(R.string.my_profile_avatar_title),
        supportingText =
          if (avatarUri.isNullOrBlank()) {
            stringResource(R.string.my_profile_avatar_tap_to_upload)
          } else {
            stringResource(R.string.my_profile_avatar_tap_to_edit)
          },
        onShowHelp = { onShowHelp(PersonaHelpTopic.AVATAR) },
      )
      RoleAvatar(
        name = name,
        avatarUri = avatarUri,
        modifier =
          Modifier
            .size(112.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onAvatarClick),
      )
      FilledTonalButton(onClick = onAvatarClick) {
        Text(
          if (avatarUri.isNullOrBlank()) {
            stringResource(R.string.role_editor_media_add)
          } else {
            stringResource(R.string.role_editor_media_replace)
          },
        )
      }
    }
  }
}

@Composable
private fun PersonaHelpDialog(
  topic: PersonaHelpTopic,
  onDismiss: () -> Unit,
) {
  val paragraphs = stringResource(topic.bodyRes).split("\n\n")
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(topic.titleRes)) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(paragraphs) { paragraph ->
          Text(paragraph, style = MaterialTheme.typography.bodyMedium)
        }
      }
    },
    confirmButton = {
      FilledTonalButton(onClick = onDismiss) {
        Text(stringResource(R.string.ok))
      }
    },
  )
}

@Composable
private fun PersonaOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  minLines: Int = 1,
  maxLines: Int = minLines,
  helpTopic: PersonaHelpTopic? = null,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
  val fieldSpec = personaTextFieldSpec(helpTopic)
  val currentCount = value.length
  val maxChars = fieldSpec?.maxChars
  val isOverLimit = maxChars != null && currentCount > maxChars
  LaunchedEffect(isOverLimit, helpTopic, currentCount, maxChars) {
    if (isOverLimit && helpTopic != null) {
      Log.w(TAG, "persona field exceeds budget topic=$helpTopic count=$currentCount limit=$maxChars")
    }
  }
  AppOutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    minLines = minLines,
    maxLines = maxLines,
    singleLine = maxLines == 1,
    isError = isOverLimit,
    keyboardOptions = keyboardOptions,
    supportingText = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Text(
          text =
            if (maxChars != null) {
              stringResource(R.string.role_editor_character_count_with_limit, currentCount, maxChars)
            } else {
              stringResource(R.string.role_editor_character_count_without_limit, currentCount)
            },
          style = MaterialTheme.typography.labelSmall,
          color =
            if (isOverLimit) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    },
  )
}

private fun takeReadPermission(context: android.content.Context, uri: Uri) {
  runCatching {
    context.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
  }
}
