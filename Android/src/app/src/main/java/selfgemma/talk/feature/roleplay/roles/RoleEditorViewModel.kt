package selfgemma.talk.feature.roleplay.roles

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaExportPolicy
import selfgemma.talk.domain.roleplay.model.RoleMediaImportState
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleMediaUsage
import selfgemma.talk.domain.roleplay.model.RoleSpriteAsset
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.cardDataOrEmpty
import selfgemma.talk.domain.roleplay.model.coverImageUri
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.domain.roleplay.model.resolvedMessageExample
import selfgemma.talk.domain.roleplay.model.resolvedOpeningLine
import selfgemma.talk.domain.roleplay.model.resolvedPersonaDescription
import selfgemma.talk.domain.roleplay.model.resolvedSummary
import selfgemma.talk.domain.roleplay.model.resolvedSystemPrompt
import selfgemma.talk.domain.roleplay.model.resolvedTags
import selfgemma.talk.domain.roleplay.model.resolvedWorldSettings
import selfgemma.talk.domain.roleplay.usecase.CompileRuntimeRoleProfileUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportStRoleCardToUriUseCase
import selfgemma.talk.domain.roleplay.usecase.ImportStRoleCardFromUriUseCase
import selfgemma.talk.domain.roleplay.repository.RoleRepository

private const val TAG = "RoleEditorViewModel"

@HiltViewModel
class RoleEditorViewModel
@Inject
constructor(
  savedStateHandle: SavedStateHandle,
  @ApplicationContext internal val appContext: Context,
  internal val roleRepository: RoleRepository,
  private val importStRoleCardFromUriUseCase: ImportStRoleCardFromUriUseCase,
  private val compileRuntimeRoleProfileUseCase: CompileRuntimeRoleProfileUseCase,
  private val exportStRoleCardToUriUseCase: ExportStRoleCardToUriUseCase,
) : ViewModel() {
  private val undoHistory = ArrayDeque<RoleEditorUiState>()
  private val redoHistory = ArrayDeque<RoleEditorUiState>()
  internal val editingRoleIdInternal: String? = savedStateHandle.get<String?>("roleId")?.takeIf { it.isNotBlank() }
  private val editingRoleId: String? get() = editingRoleIdInternal
  internal val mutableUiState = MutableStateFlow(RoleEditorUiState())
  private val _uiState get() = mutableUiState
  val uiState: StateFlow<RoleEditorUiState> = mutableUiState.asStateFlow()
  internal var loadedRoleSnapshot: RoleCard? = null
  private var loadedRole: RoleCard?
    get() = loadedRoleSnapshot
    set(value) { loadedRoleSnapshot = value }

  init {
    loadRole()
  }

  fun selectTab(tab: RoleEditorTab) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  fun undo() {
    val currentSnapshot = _uiState.value.toHistorySnapshot()
    val previousSnapshot = undoHistory.pollLast() ?: return
    redoHistory.addLast(currentSnapshot)
    applyHistorySnapshot(previousSnapshot)
    Log.d(TAG, "Role editor undo applied roleId=${_uiState.value.roleId}")
  }

  fun redo() {
    val currentSnapshot = _uiState.value.toHistorySnapshot()
    val nextSnapshot = redoHistory.pollLast() ?: return
    undoHistory.addLast(currentSnapshot)
    applyHistorySnapshot(nextSnapshot)
    Log.d(TAG, "Role editor redo applied roleId=${_uiState.value.roleId}")
  }

  fun showErrorMessage(message: String) {
    _uiState.update { current ->
      current.copy(
        errorMessage = message,
        statusMessage = null,
      )
    }
  }

  fun showStatusMessage(message: String) {
    _uiState.update { current ->
      current.copy(
        statusMessage = message,
        errorMessage = null,
      )
    }
  }

  fun updateName(value: String) = updateDraft { it.copy(name = value) }

  fun updateDescription(value: String) = updateDraft { it.copy(description = value) }

  fun updatePersonality(value: String) = updateDraft { it.copy(personality = value) }

  fun updateScenario(value: String) = updateDraft { it.copy(scenario = value) }

  fun updateFirstMessage(value: String) = updateDraft { it.copy(firstMessage = value) }

  fun updateMessageExample(value: String) = updateDraft { it.copy(messageExample = value) }

  fun updateSystemPrompt(value: String) = updateDraft { it.copy(systemPrompt = value) }

  fun updatePostHistoryInstructions(value: String) =
    updateDraft { it.copy(postHistoryInstructions = value) }

  fun updateAlternateGreetingsText(value: String) =
    updateDraft { it.copy(alternateGreetingsText = value) }

  fun updateCreatorNotes(value: String) = updateDraft { it.copy(creatorNotes = value) }

  fun updateCreator(value: String) = updateDraft { it.copy(creator = value) }

  fun updateCharacterVersion(value: String) = updateDraft { it.copy(characterVersion = value) }

  fun updateTagsText(value: String) = updateDraft { it.copy(tagsText = value) }

  fun updateTalkativenessText(value: String) = updateDraft { it.copy(talkativenessText = value) }

  fun updateFav(value: Boolean) = updateDraft { it.copy(fav = value) }

  fun updateSafetyPolicy(value: String) = updateDraft { it.copy(safetyPolicy = value) }

  fun updateDefaultModelId(value: String?) = updateDraft { it.copy(defaultModelId = value) }

  fun updateCharacterBookName(value: String) =
    updateDraft { it.copy(characterBook = it.characterBook.copy(name = value)) }

  fun updateCharacterBookDescription(value: String) =
    updateDraft { it.copy(characterBook = it.characterBook.copy(description = value)) }

  fun updateCharacterBookScanDepth(value: String) =
    updateDraft { it.copy(characterBook = it.characterBook.copy(scanDepthText = value)) }

  fun updateCharacterBookTokenBudget(value: String) =
    updateDraft { it.copy(characterBook = it.characterBook.copy(tokenBudgetText = value)) }

  fun updateCharacterBookRecursiveScanning(value: Boolean) =
    updateDraft { it.copy(characterBook = it.characterBook.copy(recursiveScanning = value)) }

  fun addCharacterBookEntry() {
    updateDraft {
      it.copy(
        characterBook =
          it.characterBook.copy(
            entries =
              it.characterBook.entries +
                RoleEditorCharacterBookEntryState(insertionOrderText = it.characterBook.entries.size.toString()),
          ),
      )
    }
  }

  fun removeCharacterBookEntry(editorId: String) {
    updateDraft {
      it.copy(
        characterBook =
          it.characterBook.copy(
            entries = it.characterBook.entries.filterNot { entry -> entry.editorId == editorId },
          ),
      )
    }
  }

  fun updateCharacterBookEntryId(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(idText = value) }

  fun updateCharacterBookEntryKeys(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(keysText = value) }

  fun updateCharacterBookEntrySecondaryKeys(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(secondaryKeysText = value) }

  fun updateCharacterBookEntryComment(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(comment = value) }

  fun updateCharacterBookEntryContent(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(content = value) }

  fun updateCharacterBookEntryConstant(editorId: String, value: Boolean) =
    updateCharacterBookEntry(editorId) { it.copy(constant = value) }

  fun updateCharacterBookEntrySelective(editorId: String, value: Boolean) =
    updateCharacterBookEntry(editorId) { it.copy(selective = value) }

  fun updateCharacterBookEntryInsertionOrder(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(insertionOrderText = value) }

  fun updateCharacterBookEntryEnabled(editorId: String, value: Boolean) =
    updateCharacterBookEntry(editorId) { it.copy(enabled = value) }

  fun updateCharacterBookEntryPosition(editorId: String, value: String) =
    updateCharacterBookEntry(editorId) { it.copy(position = value) }

  fun updateCharacterBookEntryUseRegex(editorId: String, value: Boolean) =
    updateCharacterBookEntry(editorId) { it.copy(useRegex = value) }

  fun updateAvatarUri(value: String?) = updateAvatarUriAction(value)

  fun updateCoverUri(value: String?) = updateCoverUriAction(value)

  fun addGalleryAssets(uris: List<String>) = addGalleryAssetsAction(uris)

  fun removeGalleryAsset(assetId: String) = removeGalleryAssetAction(assetId)

  fun updateGalleryAssetName(assetId: String, value: String) = updateGalleryAssetNameAction(assetId, value)

  fun updateGalleryAssetUsage(assetId: String, usage: RoleMediaUsage) = updateGalleryAssetUsageAction(assetId, usage)

  fun setGalleryAssetAsAvatar(assetId: String) = setGalleryAssetAsAvatarAction(assetId)

  fun setGalleryAssetAsCover(assetId: String) = setGalleryAssetAsCoverAction(assetId)

  fun addSpriteAssets(uris: List<String>) = addSpriteAssetsAction(uris)

  fun removeSpriteAsset(assetId: String) = removeSpriteAssetAction(assetId)

  fun updateSpriteAssetName(assetId: String, value: String) = updateSpriteAssetNameAction(assetId, value)

  fun updateSpriteStateTag(assetId: String, value: String) = updateSpriteStateTagAction(assetId, value)

  fun importStCardFromUri(uri: String) {
    viewModelScope.launch {
      runCatching {
        val existingRole = editingRoleId?.let { roleId -> roleRepository.getRole(roleId) }
        importStRoleCardFromUriUseCase.importFromUri(
          uri = uri,
          existingRole = existingRole,
        )
      }
        .onSuccess { importedRole ->
          loadedRole = importedRole
          Log.i(
            TAG,
            "Imported ST role card roleId=${importedRole.id} source=${importedRole.interopState?.sourceFormat} loreEntries=${importedRole.stCard.cardDataOrEmpty().character_book?.entries?.size ?: 0}",
          )
          _uiState.value =
            importedRole.toEditorUiState(
              isNewRole = editingRoleId == null,
              statusMessage = appContext.getString(R.string.role_editor_status_st_imported),
            )
          resetHistory(_uiState.value)
        }
        .onFailure { error ->
          _uiState.update {
            it.copy(
              errorMessage = error.message ?: appContext.getString(R.string.role_editor_error_st_import_failed),
              statusMessage = null,
            )
          }
        }
    }
  }

  fun exportStCardToUri(uri: String) {
    val snapshot = buildRoleSnapshotForSave() ?: return
    viewModelScope.launch {
      runCatching {
        exportStRoleCardToUriUseCase.exportToUri(
          uri = uri,
          role = snapshot,
        )
      }
        .onSuccess {
          _uiState.update {
            it.copy(
              statusMessage = appContext.getString(R.string.role_editor_status_st_exported),
              errorMessage = null,
            )
          }
        }
        .onFailure { error ->
          _uiState.update {
            it.copy(
              errorMessage = error.message ?: appContext.getString(R.string.role_editor_error_st_export_failed),
              statusMessage = null,
            )
          }
        }
    }
  }

  fun saveRole(onSaved: (String) -> Unit) {
    val role = buildRoleSnapshotForSave() ?: return

    viewModelScope.launch {
      Log.i(
        TAG,
        "Saving role editor draft roleId=${role.id} source=${role.interopState?.sourceFormat} loreEntries=${role.stCard.cardDataOrEmpty().character_book?.entries?.size ?: 0} tags=${role.stCard.cardDataOrEmpty().tags?.size ?: 0}",
      )
      val compiledRole = compileRuntimeRoleProfileUseCase(role)
      roleRepository.saveRole(compiledRole)
      onSaved(compiledRole.id)
    }
  }

  private fun loadRole() {
    viewModelScope.launch {
      val role: RoleCard? = editingRoleId?.let { roleId -> roleRepository.getRole(roleId) }
      if (role == null) {
        loadedRole = null
        _uiState.value =
          emptyEditorRoleUiState(
            systemPrompt = appContext.getString(R.string.role_editor_default_system_prompt),
          )
        resetHistory(_uiState.value)
        return@launch
      }

      loadedRole = role
      Log.d(
        TAG,
        "Loaded role editor roleId=${role.id} source=${role.interopState?.sourceFormat} loreEntries=${role.stCard.cardDataOrEmpty().character_book?.entries?.size ?: 0}",
      )
      _uiState.value = role.toEditorUiState(isNewRole = false)
      resetHistory(_uiState.value)
    }
  }

  private fun updateCharacterBookEntry(
    editorId: String,
    transformer: (RoleEditorCharacterBookEntryState) -> RoleEditorCharacterBookEntryState,
  ) {
    updateDraft {
      it.copy(
        characterBook =
          it.characterBook.copy(
            entries =
              it.characterBook.entries.map { entry ->
                if (entry.editorId == editorId) {
                  transformer(entry)
                } else {
                  entry
                }
              },
          ),
      )
    }
  }

  private fun updateGalleryAsset(assetId: String, transformer: (RoleMediaAsset) -> RoleMediaAsset) {
    mutateEditorState {
      it.copy(
        galleryAssets =
          it.galleryAssets.map { asset ->
            if (asset.id == assetId) {
              transformer(asset)
            } else {
              asset
            }
          },
        errorMessage = null,
      )
    }
  }

  private fun updateDraft(transformer: (RoleEditorUiState) -> RoleEditorUiState) {
    mutateEditorState { current ->
      transformer(current).copy(errorMessage = null, statusMessage = null)
    }
  }

  internal fun mutateEditorState(
    recordHistory: Boolean = true,
    transformer: (RoleEditorUiState) -> RoleEditorUiState,
  ) {
    _uiState.update { current ->
      val currentSnapshot = current.toHistorySnapshot()
      val updated = transformer(current)
      val updatedSnapshot = updated.toHistorySnapshot()
      if (recordHistory && currentSnapshot != updatedSnapshot) {
        undoHistory.addLast(currentSnapshot)
        trimHistory(undoHistory)
        redoHistory.clear()
      }
      updated.copy(canUndo = undoHistory.isNotEmpty(), canRedo = redoHistory.isNotEmpty())
    }
  }

  private fun applyHistorySnapshot(snapshot: RoleEditorUiState) {
    val currentTab = _uiState.value.selectedTab
    _uiState.value =
      snapshot.copy(
        selectedTab = currentTab,
        statusMessage = null,
        errorMessage = null,
        canUndo = undoHistory.isNotEmpty(),
        canRedo = redoHistory.isNotEmpty(),
      )
  }

  private fun resetHistory(state: RoleEditorUiState) {
    undoHistory.clear()
    redoHistory.clear()
    _uiState.value = state.copy(canUndo = false, canRedo = false)
  }

  private fun trimHistory(history: ArrayDeque<RoleEditorUiState>) {
    while (history.size > 100) {
      history.removeFirst()
    }
  }

  internal fun syncPrimaryAvatarAsset(uri: String?, source: RoleMediaSource, now: Long) {
    val existingProfile = loadedRole?.mediaProfile
    loadedRole =
      loadedRole?.copy(
        mediaProfile =
          (existingProfile ?: RoleMediaProfile()).copy(
            primaryAvatar =
              uri?.let {
                RoleMediaAsset(
                  id = existingProfile?.primaryAvatar?.id ?: UUID.randomUUID().toString(),
                  kind = RoleMediaKind.PRIMARY_AVATAR,
                  uri = it,
                  source = source,
                  createdAt = existingProfile?.primaryAvatar?.createdAt ?: now,
                  updatedAt = now,
                )
              },
            importState =
              (existingProfile?.importState ?: RoleMediaImportState()).copy(
                importedFromStPng = source == RoleMediaSource.ST_PNG_IMPORT,
                lastImportedPrimaryAvatarSource = if (source == RoleMediaSource.ST_PNG_IMPORT) uri else existingProfile?.importState?.lastImportedPrimaryAvatarSource,
                lastImportHadEmbeddedImage = source == RoleMediaSource.ST_PNG_IMPORT,
              ),
          ),
      )
  }

  internal fun syncCoverImageAsset(
    uri: String?,
    now: Long,
    source: RoleMediaSource = RoleMediaSource.LOCAL_PICKER,
  ) {
    val existingProfile = loadedRole?.mediaProfile
    loadedRole =
      loadedRole?.copy(
        mediaProfile =
          (existingProfile ?: RoleMediaProfile()).copy(
            coverImage =
              uri?.let {
                RoleMediaAsset(
                  id = existingProfile?.coverImage?.id ?: UUID.randomUUID().toString(),
                  kind = RoleMediaKind.COVER,
                  uri = it,
                  source = source,
                  createdAt = existingProfile?.coverImage?.createdAt ?: now,
                  updatedAt = now,
                )
              },
          ),
      )
  }
}
