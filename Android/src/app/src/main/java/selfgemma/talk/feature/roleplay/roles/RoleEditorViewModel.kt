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

private data class ParseResult<T>(
  val value: T? = null,
  val valid: Boolean = true,
)

enum class RoleEditorTab {
  CARD,
  PROMPT,
  LOREBOOK,
  METADATA,
  MEDIA,
  INTEROP,
}

data class RoleEditorCharacterBookEntryState(
  val editorId: String = UUID.randomUUID().toString(),
  val idText: String = "",
  val keysText: String = "",
  val secondaryKeysText: String = "",
  val comment: String = "",
  val content: String = "",
  val constant: Boolean = false,
  val selective: Boolean = false,
  val insertionOrderText: String = "",
  val enabled: Boolean = true,
  val position: String = "",
  val useRegex: Boolean = false,
  val preservedCharacterFilterJson: String? = null,
  val preservedExtensionsJson: String? = null,
)

data class RoleEditorCharacterBookState(
  val name: String = "",
  val description: String = "",
  val scanDepthText: String = "",
  val tokenBudgetText: String = "",
  val recursiveScanning: Boolean = false,
  val entries: List<RoleEditorCharacterBookEntryState> = emptyList(),
)

data class RoleEditorUiState(
  val loading: Boolean = true,
  val roleId: String? = null,
  val isNewRole: Boolean = true,
  val builtIn: Boolean = false,
  val selectedTab: RoleEditorTab = RoleEditorTab.CARD,
  val stCard: StCharacterCard = emptyEditorStCard(),
  val name: String = "",
  val description: String = "",
  val personality: String = "",
  val scenario: String = "",
  val firstMessage: String = "",
  val messageExample: String = "",
  val systemPrompt: String = "",
  val postHistoryInstructions: String = "",
  val alternateGreetingsText: String = "",
  val creatorNotes: String = "",
  val creator: String = "",
  val characterVersion: String = "",
  val tagsText: String = "",
  val talkativenessText: String = "",
  val fav: Boolean = false,
  val characterBook: RoleEditorCharacterBookState = RoleEditorCharacterBookState(),
  val safetyPolicy: String = "",
  val defaultModelId: String? = null,
  val avatarUri: String? = null,
  val coverUri: String? = null,
  val avatarSource: RoleMediaSource? = null,
  val coverSource: RoleMediaSource? = null,
  val galleryAssets: List<RoleMediaAsset> = emptyList(),
  val spriteAssets: List<RoleSpriteAsset> = emptyList(),
  val importedFromStPng: Boolean = false,
  val sourceFormat: RoleCardSourceFormat = RoleCardSourceFormat.INTERNAL,
  val sourceSpec: String? = null,
  val sourceSpecVersion: String? = null,
  val compatibilityWarnings: List<String> = emptyList(),
  val statusMessage: String? = null,
  val errorMessage: String? = null,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
)

@HiltViewModel
class RoleEditorViewModel
@Inject
constructor(
  savedStateHandle: SavedStateHandle,
  @ApplicationContext private val appContext: Context,
  private val roleRepository: RoleRepository,
  private val importStRoleCardFromUriUseCase: ImportStRoleCardFromUriUseCase,
  private val compileRuntimeRoleProfileUseCase: CompileRuntimeRoleProfileUseCase,
  private val exportStRoleCardToUriUseCase: ExportStRoleCardToUriUseCase,
) : ViewModel() {
  private val undoHistory = ArrayDeque<RoleEditorUiState>()
  private val redoHistory = ArrayDeque<RoleEditorUiState>()
  private val editingRoleId: String? = savedStateHandle.get<String?>("roleId")?.takeIf { it.isNotBlank() }
  private val _uiState = MutableStateFlow(RoleEditorUiState())
  val uiState: StateFlow<RoleEditorUiState> = _uiState.asStateFlow()
  private var loadedRole: RoleCard? = null

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

  fun updateAvatarUri(value: String?) {
    val now = System.currentTimeMillis()
    mutateEditorState {
      it.copy(
        avatarUri = value,
        avatarSource = if (value.isNullOrBlank()) null else RoleMediaSource.LOCAL_PICKER,
        errorMessage = null,
        statusMessage =
          if (value.isNullOrBlank()) {
            appContext.getString(R.string.role_editor_status_avatar_cleared)
          } else {
            appContext.getString(R.string.role_editor_status_avatar_updated)
          },
        importedFromStPng = false,
      )
    }
    syncPrimaryAvatarAsset(uri = value, source = RoleMediaSource.LOCAL_PICKER, now = now)
  }

  fun updateCoverUri(value: String?) {
    val now = System.currentTimeMillis()
    mutateEditorState {
      it.copy(
        coverUri = value,
        coverSource = if (value.isNullOrBlank()) null else RoleMediaSource.LOCAL_PICKER,
        errorMessage = null,
        statusMessage =
          if (value.isNullOrBlank()) {
            appContext.getString(R.string.role_editor_status_cover_cleared)
          } else {
            appContext.getString(R.string.role_editor_status_cover_updated)
          },
      )
    }
    syncCoverImageAsset(uri = value, now = now)
  }

  fun addGalleryAssets(uris: List<String>) {
    if (uris.isEmpty()) {
      return
    }
    val now = System.currentTimeMillis()
    val newAssets =
      uris.distinct().map { uri ->
        RoleMediaAsset(
          id = UUID.randomUUID().toString(),
          kind = RoleMediaKind.GALLERY,
          uri = uri,
          displayName = uri.substringAfterLast('/').substringBefore('?').ifBlank { null },
          source = RoleMediaSource.LOCAL_PICKER,
          createdAt = now,
          updatedAt = now,
        )
      }
    mutateEditorState {
      it.copy(
        galleryAssets = it.galleryAssets + newAssets,
        statusMessage = appContext.getString(R.string.role_editor_status_gallery_added, newAssets.size),
        errorMessage = null,
      )
    }
  }

  fun removeGalleryAsset(assetId: String) {
    mutateEditorState {
      val removedAsset = it.galleryAssets.firstOrNull { asset -> asset.id == assetId }
      it.copy(
        galleryAssets = it.galleryAssets.filterNot { asset -> asset.id == assetId },
        avatarUri = if (removedAsset?.uri == it.avatarUri) null else it.avatarUri,
        avatarSource = if (removedAsset?.uri == it.avatarUri) null else it.avatarSource,
        coverUri = if (removedAsset?.uri == it.coverUri) null else it.coverUri,
        coverSource = if (removedAsset?.uri == it.coverUri) null else it.coverSource,
        importedFromStPng = if (removedAsset?.uri == it.avatarUri) false else it.importedFromStPng,
        statusMessage = appContext.getString(R.string.role_editor_status_gallery_removed),
        errorMessage = null,
      )
    }
  }

  fun updateGalleryAssetName(assetId: String, value: String) {
    updateGalleryAsset(assetId) { asset ->
      asset.copy(displayName = value.ifBlank { null }, updatedAt = System.currentTimeMillis())
    }
  }

  fun updateGalleryAssetUsage(assetId: String, usage: RoleMediaUsage) {
    updateGalleryAsset(assetId) { asset ->
      asset.copy(usage = usage, updatedAt = System.currentTimeMillis())
    }
  }

  fun setGalleryAssetAsAvatar(assetId: String) {
    val asset = _uiState.value.galleryAssets.firstOrNull { it.id == assetId } ?: return
    val now = System.currentTimeMillis()
    mutateEditorState {
      it.copy(
        avatarUri = asset.uri,
        avatarSource = asset.source,
        statusMessage = appContext.getString(R.string.role_editor_status_gallery_avatar),
        errorMessage = null,
        importedFromStPng = asset.source == RoleMediaSource.ST_PNG_IMPORT,
      )
    }
    syncPrimaryAvatarAsset(uri = asset.uri, source = asset.source, now = now)
  }

  fun setGalleryAssetAsCover(assetId: String) {
    val asset = _uiState.value.galleryAssets.firstOrNull { it.id == assetId } ?: return
    val now = System.currentTimeMillis()
    mutateEditorState {
      it.copy(
        coverUri = asset.uri,
        coverSource = asset.source,
        statusMessage = appContext.getString(R.string.role_editor_status_gallery_cover),
        errorMessage = null,
      )
    }
    syncCoverImageAsset(uri = asset.uri, now = now, source = asset.source)
  }

  fun addSpriteAssets(uris: List<String>) {
    if (uris.isEmpty()) {
      return
    }
    val now = System.currentTimeMillis()
    val newAssets =
      uris.distinct().map { uri ->
        val displayName = uri.substringAfterLast('/').substringBefore('?').ifBlank { null }
        RoleSpriteAsset(
          id = UUID.randomUUID().toString(),
          uri = uri,
          displayName = displayName,
          stateTag = displayName?.substringBeforeLast('.')?.ifBlank { "neutral" } ?: "neutral",
          source = RoleMediaSource.LOCAL_PICKER,
          createdAt = now,
          updatedAt = now,
        )
      }
    mutateEditorState {
      it.copy(
        spriteAssets = it.spriteAssets + newAssets,
        statusMessage = appContext.getString(R.string.role_editor_status_sprite_added, newAssets.size),
        errorMessage = null,
      )
    }
  }

  fun removeSpriteAsset(assetId: String) {
    mutateEditorState {
      it.copy(
        spriteAssets = it.spriteAssets.filterNot { asset -> asset.id == assetId },
        statusMessage = appContext.getString(R.string.role_editor_status_sprite_removed),
        errorMessage = null,
      )
    }
  }

  fun updateSpriteAssetName(assetId: String, value: String) {
    mutateEditorState {
      it.copy(
        spriteAssets =
          it.spriteAssets.map { asset ->
            if (asset.id == assetId) {
              asset.copy(displayName = value.ifBlank { null }, updatedAt = System.currentTimeMillis())
            } else {
              asset
            }
          },
        errorMessage = null,
      )
    }
  }

  fun updateSpriteStateTag(assetId: String, value: String) {
    mutateEditorState {
      it.copy(
        spriteAssets =
          it.spriteAssets.map { asset ->
            if (asset.id == assetId) {
              asset.copy(stateTag = value.ifBlank { "neutral" }, updatedAt = System.currentTimeMillis())
            } else {
              asset
            }
          },
        errorMessage = null,
      )
    }
  }

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
    val snapshot = buildRoleSnapshot() ?: return
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
    val role = buildRoleSnapshot() ?: return

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

  private fun buildRoleSnapshot(): RoleCard? {
    val snapshot = _uiState.value
    val roleName = snapshot.name.trim()
    if (roleName.isBlank()) {
      _uiState.update {
        it.copy(errorMessage = appContext.getString(R.string.role_editor_error_required_fields))
      }
      return null
    }

    val talkativeness = parseOptionalDouble(snapshot.talkativenessText, R.string.role_editor_talkativeness_label)
    if (!talkativeness.valid) {
      return null
    }
    val scanDepth = parseOptionalInt(snapshot.characterBook.scanDepthText, R.string.role_editor_lorebook_scan_depth_label)
    if (!scanDepth.valid) {
      return null
    }
    val tokenBudget = parseOptionalInt(snapshot.characterBook.tokenBudgetText, R.string.role_editor_lorebook_token_budget_label)
    if (!tokenBudget.valid) {
      return null
    }
    val characterBookEntries =
      snapshot.characterBook.entries.mapNotNull { entry ->
        buildCharacterBookEntry(entry) ?: return null
      }

    val alternateGreetings = snapshot.alternateGreetingsText.toLineList()
    val tags = snapshot.tagsText.toTagList()
    val existingRole = loadedRole
    val baseCard = snapshot.stCard
    val baseData = baseCard.cardDataOrEmpty()
    val characterBook =
      if (
        snapshot.characterBook.name.isBlank() &&
          snapshot.characterBook.description.isBlank() &&
          snapshot.characterBook.scanDepthText.isBlank() &&
          snapshot.characterBook.tokenBudgetText.isBlank() &&
          characterBookEntries.isEmpty()
      ) {
        null
      } else {
        (baseData.character_book ?: StCharacterBook()).copy(
          name = snapshot.characterBook.name.trim().ifBlank { null },
          description = snapshot.characterBook.description.trim().ifBlank { null },
          scan_depth = scanDepth.value,
          token_budget = tokenBudget.value,
          recursive_scanning = snapshot.characterBook.recursiveScanning,
          entries = characterBookEntries,
        )
      }

    val data =
      baseData.copy(
        name = roleName,
        description = snapshot.description.trim().ifBlank { null },
        personality = snapshot.personality.trim().ifBlank { null },
        scenario = snapshot.scenario.trim().ifBlank { null },
        first_mes = snapshot.firstMessage.trim().ifBlank { null },
        mes_example = snapshot.messageExample.trim().ifBlank { null },
        creator_notes = snapshot.creatorNotes.trim().ifBlank { null },
        system_prompt = snapshot.systemPrompt.trim().ifBlank { null },
        post_history_instructions = snapshot.postHistoryInstructions.trim().ifBlank { null },
        alternate_greetings = alternateGreetings.ifEmpty { null },
        tags = tags.ifEmpty { null },
        creator = snapshot.creator.trim().ifBlank { null },
        character_version = snapshot.characterVersion.trim().ifBlank { null },
        character_book = characterBook,
      )

    val stCard =
      baseCard.copy(
        spec = baseCard.spec ?: "chara_card_v2",
        spec_version = baseCard.spec_version ?: "2.0",
        name = roleName,
        description = snapshot.description.trim().ifBlank { null },
        personality = snapshot.personality.trim().ifBlank { null },
        scenario = snapshot.scenario.trim().ifBlank { null },
        first_mes = snapshot.firstMessage.trim().ifBlank { null },
        mes_example = snapshot.messageExample.trim().ifBlank { null },
        creatorcomment = snapshot.creatorNotes.trim().ifBlank { null },
        talkativeness = talkativeness.value,
        fav = snapshot.fav,
        creator = snapshot.creator.trim().ifBlank { null },
        tags = tags.ifEmpty { null },
        data = data,
      )

    val now = System.currentTimeMillis()
    val roleId = editingRoleId ?: UUID.randomUUID().toString()
    return RoleCard(
      id = roleId,
      stCard = stCard,
      safetyPolicy = snapshot.safetyPolicy.trim(),
      defaultModelId = snapshot.defaultModelId,
      builtIn = snapshot.builtIn,
      createdAt = existingRole?.createdAt ?: now,
      updatedAt = now,
      defaultTemperature = existingRole?.defaultTemperature,
      defaultTopP = existingRole?.defaultTopP,
      defaultTopK = existingRole?.defaultTopK,
      enableThinking = existingRole?.enableThinking ?: false,
      summaryTurnThreshold = existingRole?.summaryTurnThreshold ?: 6,
      memoryEnabled = existingRole?.memoryEnabled ?: true,
      memoryMaxItems = existingRole?.memoryMaxItems ?: 32,
      avatarUri = snapshot.avatarUri ?: existingRole?.primaryAvatarUri(),
      coverUri = snapshot.coverUri ?: existingRole?.coverImageUri(),
      runtimeProfile = existingRole?.runtimeProfile,
      mediaProfile =
        RoleMediaProfile(
          primaryAvatar =
            snapshot.avatarUri?.let { uri ->
              RoleMediaAsset(
                id = existingRole?.mediaProfile?.primaryAvatar?.id ?: UUID.randomUUID().toString(),
                kind = RoleMediaKind.PRIMARY_AVATAR,
                uri = uri,
                source =
                  snapshot.avatarSource ?: existingRole?.mediaProfile?.primaryAvatar?.source ?: RoleMediaSource.LOCAL_PICKER,
                createdAt = existingRole?.mediaProfile?.primaryAvatar?.createdAt ?: now,
                updatedAt = now,
              )
            },
          coverImage =
            snapshot.coverUri?.let { uri ->
              RoleMediaAsset(
                id = existingRole?.mediaProfile?.coverImage?.id ?: UUID.randomUUID().toString(),
                kind = RoleMediaKind.COVER,
                uri = uri,
                source = snapshot.coverSource ?: existingRole?.mediaProfile?.coverImage?.source ?: RoleMediaSource.LOCAL_PICKER,
                createdAt = existingRole?.mediaProfile?.coverImage?.createdAt ?: now,
                updatedAt = now,
              )
            },
          galleryAssets = snapshot.galleryAssets,
          spriteAssets = snapshot.spriteAssets,
          exportPolicy = existingRole?.mediaProfile?.exportPolicy ?: RoleMediaExportPolicy(),
          importState =
            existingRole?.mediaProfile?.importState
              ?: RoleMediaImportState(importedFromStPng = snapshot.importedFromStPng),
        ),
      interopState = existingRole?.interopState,
      archived = false,
    )
  }

  private fun buildCharacterBookEntry(
    entry: RoleEditorCharacterBookEntryState,
  ): StCharacterBookEntry? {
    val entryId = parseOptionalInt(entry.idText, R.string.role_editor_lorebook_entry_id_label)
    if (!entryId.valid) {
      return null
    }
    val insertionOrder = parseOptionalInt(entry.insertionOrderText, R.string.role_editor_lorebook_entry_order_label)
    if (!insertionOrder.valid) {
      return null
    }
    val keys = entry.keysText.toCommaSeparatedList()
    val secondaryKeys = entry.secondaryKeysText.toCommaSeparatedList()
    val content = entry.content.trim()
    if (keys.isEmpty() && content.isBlank() && entry.comment.isBlank()) {
      return null
    }
    return StCharacterBookEntry(
      id = entryId.value,
      keys = keys.ifEmpty { null },
      secondary_keys = secondaryKeys.ifEmpty { null },
      character_filter = entry.preservedCharacterFilterJson.toJsonObjectOrNull(),
      comment = entry.comment.trim().ifBlank { null },
      content = content.ifBlank { null },
      constant = entry.constant,
      selective = entry.selective,
      insertion_order = insertionOrder.value,
      enabled = entry.enabled,
      position = entry.position.trim().ifBlank { null },
      use_regex = entry.useRegex,
      extensions = entry.preservedExtensionsJson.toJsonObjectOrNull(),
    )
  }

  private fun parseOptionalInt(value: String, labelRes: Int): ParseResult<Int> {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
      return ParseResult(value = null, valid = true)
    }
    val parsed = trimmed.toIntOrNull()
    if (parsed == null) {
      _uiState.update {
        it.copy(
          errorMessage =
            appContext.getString(
              R.string.role_editor_error_invalid_integer,
              appContext.getString(labelRes),
            ),
        )
      }
      return ParseResult(value = null, valid = false)
    }
    return ParseResult(value = parsed, valid = true)
  }

  private fun parseOptionalDouble(value: String, labelRes: Int): ParseResult<Double> {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
      return ParseResult(value = null, valid = true)
    }
    val parsed = trimmed.toDoubleOrNull()
    if (parsed == null) {
      _uiState.update {
        it.copy(
          errorMessage =
            appContext.getString(
              R.string.role_editor_error_invalid_decimal,
              appContext.getString(labelRes),
            ),
        )
      }
      return ParseResult(value = null, valid = false)
    }
    return ParseResult(value = parsed, valid = true)
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

  private fun mutateEditorState(
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

  private fun syncPrimaryAvatarAsset(uri: String?, source: RoleMediaSource, now: Long) {
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

  private fun syncCoverImageAsset(
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

private fun String.toTagList(): List<String> {
  return split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()
}

private fun String.toCommaSeparatedList(): List<String> {
  return split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
}

private fun String.toLineList(): List<String> {
  return lines().map { it.trim() }.filter { it.isNotBlank() }
}

private fun String?.toJsonObjectOrNull(): JsonObject? {
  if (this.isNullOrBlank()) {
    return null
  }
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}

private fun emptyEditorStCard(systemPrompt: String = ""): StCharacterCard {
  return StCharacterCard(
    spec = "chara_card_v2",
    spec_version = "2.0",
    data = StCharacterCardData(system_prompt = systemPrompt),
  )
}

private fun emptyEditorRoleUiState(systemPrompt: String): RoleEditorUiState {
  return RoleEditorUiState(
    loading = false,
    roleId = null,
    isNewRole = true,
    stCard = emptyEditorStCard(systemPrompt),
    systemPrompt = systemPrompt,
  )
}

private fun RoleEditorUiState.toHistorySnapshot(): RoleEditorUiState {
  return copy(
    selectedTab = RoleEditorTab.CARD,
    statusMessage = null,
    errorMessage = null,
    canUndo = false,
    canRedo = false,
  )
}

private fun StCharacterBook?.toEditorState(): RoleEditorCharacterBookState {
  if (this == null) {
    return RoleEditorCharacterBookState()
  }
  return RoleEditorCharacterBookState(
    name = name.orEmpty(),
    description = description.orEmpty(),
    scanDepthText = scan_depth?.toString().orEmpty(),
    tokenBudgetText = token_budget?.toString().orEmpty(),
    recursiveScanning = recursive_scanning ?: false,
    entries = entries.orEmpty().map { it.toEditorState() },
  )
}

private fun StCharacterBookEntry.toEditorState(): RoleEditorCharacterBookEntryState {
  return RoleEditorCharacterBookEntryState(
    idText = id?.toString().orEmpty(),
    keysText = keys.orEmpty().joinToString(", "),
    secondaryKeysText = secondary_keys.orEmpty().joinToString(", "),
    comment = comment.orEmpty(),
    content = content.orEmpty(),
    constant = constant ?: false,
    selective = selective ?: false,
    insertionOrderText = insertion_order?.toString().orEmpty(),
    enabled = enabled ?: true,
    position = position.orEmpty(),
    useRegex = use_regex ?: false,
    preservedCharacterFilterJson = character_filter?.toString(),
    preservedExtensionsJson = extensions?.toString(),
  )
}

internal fun RoleCard.toEditorUiState(
  isNewRole: Boolean,
  statusMessage: String? = null,
): RoleEditorUiState {
  val data = stCard.cardDataOrEmpty()
  val interopState = interopState ?: RoleInteropState()
  return RoleEditorUiState(
    loading = false,
    roleId = id,
    isNewRole = isNewRole,
    builtIn = builtIn,
    stCard = stCard,
    name = name,
    description = resolvedSummary(),
    personality = resolvedPersonaDescription(),
    scenario = resolvedWorldSettings(),
    firstMessage = resolvedOpeningLine(),
    messageExample = stCard.resolvedMessageExample(),
    systemPrompt = resolvedSystemPrompt(),
    postHistoryInstructions = data.post_history_instructions.orEmpty(),
    alternateGreetingsText = data.alternate_greetings.orEmpty().joinToString("\n"),
    creatorNotes = data.creator_notes ?: stCard.creatorcomment.orEmpty(),
    creator = data.creator ?: stCard.creator.orEmpty(),
    characterVersion = data.character_version.orEmpty(),
    tagsText = resolvedTags().joinToString(", "),
    talkativenessText = stCard.talkativeness?.toString().orEmpty(),
    fav = stCard.fav ?: false,
    characterBook = data.character_book.toEditorState(),
    safetyPolicy = safetyPolicy,
    defaultModelId = defaultModelId,
    avatarUri = primaryAvatarUri(),
    coverUri = coverImageUri(),
    avatarSource = mediaProfile?.primaryAvatar?.source,
    coverSource = mediaProfile?.coverImage?.source,
    galleryAssets = mediaProfile?.galleryAssets.orEmpty(),
    spriteAssets = mediaProfile?.spriteAssets.orEmpty(),
    importedFromStPng =
      mediaProfile?.importState?.importedFromStPng
        ?: (interopState.sourceFormat == RoleCardSourceFormat.ST_PNG),
    sourceFormat = interopState.sourceFormat,
    sourceSpec = interopState.sourceSpec ?: stCard.spec,
    sourceSpecVersion = interopState.sourceSpecVersion ?: stCard.spec_version,
    compatibilityWarnings = interopState.compatibilityWarnings,
    statusMessage = statusMessage,
  )
}
