package selfgemma.talk.feature.roleplay.roles

import androidx.annotation.StringRes
import java.util.UUID
import kotlinx.coroutines.flow.update
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaExportPolicy
import selfgemma.talk.domain.roleplay.model.RoleMediaImportState
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry
import selfgemma.talk.domain.roleplay.model.cardDataOrEmpty
import selfgemma.talk.domain.roleplay.model.coverImageUri
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri

internal fun RoleEditorViewModel.buildRoleSnapshotForSave(): RoleCard? {
  val snapshot = uiState.value
  val roleName = snapshot.name.trim()
  if (roleName.isBlank()) {
    mutableUiState.update {
      it.copy(errorMessage = appContext.getString(R.string.role_editor_error_required_fields))
    }
    return null
  }

  val talkativeness = parseOptionalDoubleField(snapshot.talkativenessText, R.string.role_editor_talkativeness_label)
  if (!talkativeness.valid) {
    return null
  }
  val scanDepth = parseOptionalIntField(snapshot.characterBook.scanDepthText, R.string.role_editor_lorebook_scan_depth_label)
  if (!scanDepth.valid) {
    return null
  }
  val tokenBudget = parseOptionalIntField(snapshot.characterBook.tokenBudgetText, R.string.role_editor_lorebook_token_budget_label)
  if (!tokenBudget.valid) {
    return null
  }
  val characterBookEntries =
    snapshot.characterBook.entries.mapNotNull { entry ->
      buildCharacterBookEntry(entry) ?: return null
    }

  val alternateGreetings = snapshot.alternateGreetingsText.toLineList()
  val tags = snapshot.tagsText.toTagList()
  val existingRole = loadedRoleSnapshot
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
  val roleId = editingRoleIdInternal ?: UUID.randomUUID().toString()
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

internal fun RoleEditorViewModel.buildCharacterBookEntry(
  entry: RoleEditorCharacterBookEntryState,
): StCharacterBookEntry? {
  val entryId = parseOptionalIntField(entry.idText, R.string.role_editor_lorebook_entry_id_label)
  if (!entryId.valid) {
    return null
  }
  val insertionOrder = parseOptionalIntField(entry.insertionOrderText, R.string.role_editor_lorebook_entry_order_label)
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

internal fun RoleEditorViewModel.parseOptionalIntField(value: String, @StringRes labelRes: Int): ParseResult<Int> {
  val trimmed = value.trim()
  if (trimmed.isBlank()) {
    return ParseResult(value = null, valid = true)
  }
  val parsed = trimmed.toIntOrNull()
  if (parsed == null) {
    mutableUiState.update {
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

internal fun RoleEditorViewModel.parseOptionalDoubleField(value: String, @StringRes labelRes: Int): ParseResult<Double> {
  val trimmed = value.trim()
  if (trimmed.isBlank()) {
    return ParseResult(value = null, valid = true)
  }
  val parsed = trimmed.toDoubleOrNull()
  if (parsed == null) {
    mutableUiState.update {
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
