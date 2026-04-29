package selfgemma.talk.feature.roleplay.roles

import java.util.UUID
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleSpriteAsset
import selfgemma.talk.domain.roleplay.model.StCharacterCard

internal data class ParseResult<T>(
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
