package selfgemma.talk.feature.roleplay.roles

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleInteropState
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

internal fun String.toTagList(): List<String> {
  return split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()
}

internal fun String.toCommaSeparatedList(): List<String> {
  return split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
}

internal fun String.toLineList(): List<String> {
  return lines().map { it.trim() }.filter { it.isNotBlank() }
}

internal fun String?.toJsonObjectOrNull(): JsonObject? {
  if (this.isNullOrBlank()) {
    return null
  }
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}

internal fun emptyEditorStCard(systemPrompt: String = ""): StCharacterCard {
  return StCharacterCard(
    spec = "chara_card_v2",
    spec_version = "2.0",
    data = StCharacterCardData(system_prompt = systemPrompt),
  )
}

internal fun emptyEditorRoleUiState(systemPrompt: String): RoleEditorUiState {
  return RoleEditorUiState(
    loading = false,
    roleId = null,
    isNewRole = true,
    stCard = emptyEditorStCard(systemPrompt),
    systemPrompt = systemPrompt,
  )
}

internal fun RoleEditorUiState.toHistorySnapshot(): RoleEditorUiState {
  return copy(
    selectedTab = RoleEditorTab.CARD,
    statusMessage = null,
    errorMessage = null,
    canUndo = false,
    canRedo = false,
  )
}

internal fun StCharacterBook?.toEditorState(): RoleEditorCharacterBookState {
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

internal fun StCharacterBookEntry.toEditorState(): RoleEditorCharacterBookEntryState {
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
