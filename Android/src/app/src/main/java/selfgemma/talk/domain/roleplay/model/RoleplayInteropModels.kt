package selfgemma.talk.domain.roleplay.model

import com.google.gson.JsonObject

enum class RoleCardSourceFormat {
  INTERNAL,
  ST_JSON,
  ST_PNG,
  UNKNOWN,
}

enum class RoleCardExportTarget {
  ST_V2_JSON,
  ST_PNG,
}

data class StCharacterCard(
  val spec: String? = null,
  val spec_version: String? = null,
  val name: String? = null,
  val description: String? = null,
  val personality: String? = null,
  val scenario: String? = null,
  val first_mes: String? = null,
  val mes_example: String? = null,
  val creatorcomment: String? = null,
  val avatar: String? = null,
  val chat: String? = null,
  val talkativeness: Double? = null,
  val fav: Boolean? = null,
  val creator: String? = null,
  val tags: List<String>? = null,
  val create_date: String? = null,
  val data: StCharacterCardData? = null,
)

data class StCharacterCardData(
  val name: String? = null,
  val description: String? = null,
  val personality: String? = null,
  val scenario: String? = null,
  val first_mes: String? = null,
  val mes_example: String? = null,
  val creator_notes: String? = null,
  val system_prompt: String? = null,
  val post_history_instructions: String? = null,
  val alternate_greetings: List<String>? = null,
  val tags: List<String>? = null,
  val creator: String? = null,
  val character_version: String? = null,
  val character_book: StCharacterBook? = null,
  val extensions: JsonObject? = null,
)

data class StCharacterBook(
  val name: String? = null,
  val description: String? = null,
  val scan_depth: Int? = null,
  val token_budget: Int? = null,
  val recursive_scanning: Boolean? = null,
  val extensions: JsonObject? = null,
  val entries: List<StCharacterBookEntry>? = null,
)

data class StCharacterBookEntry(
  val id: Int? = null,
  val keys: List<String>? = null,
  val secondary_keys: List<String>? = null,
  val character_filter: JsonObject? = null,
  val comment: String? = null,
  val content: String? = null,
  val constant: Boolean? = null,
  val selective: Boolean? = null,
  val insertion_order: Int? = null,
  val enabled: Boolean? = null,
  val position: String? = null,
  val use_regex: Boolean? = null,
  val extensions: JsonObject? = null,
)

data class RuntimeModelParams(
  val preferredModelId: String? = null,
  val temperature: Float? = null,
  val topP: Float? = null,
  val topK: Int? = null,
  val enableThinking: Boolean = false,
)

data class MemoryPolicy(
  val enabled: Boolean = true,
  val maxItems: Int = 32,
  val summaryTurnThreshold: Int = 6,
)

data class AgentPolicy(
  val enabled: Boolean = false,
  val proactiveEnabled: Boolean = false,
)

data class RuntimeSafetyPolicy(
  val policyText: String = "",
)

data class PromptPolicy(
  val includeCharacterBook: Boolean = true,
  val includeSessionSummary: Boolean = true,
  val includePinnedMemories: Boolean = true,
)

data class CharacterUiHints(
  val preferredEditorTab: String? = null,
)

data class RoleRuntimeProfile(
  val summary: String = "",
  val modelParams: RuntimeModelParams = RuntimeModelParams(),
  val memoryPolicy: MemoryPolicy = MemoryPolicy(),
  val agentPolicy: AgentPolicy = AgentPolicy(),
  val safetyPolicy: RuntimeSafetyPolicy = RuntimeSafetyPolicy(),
  val promptPolicy: PromptPolicy = PromptPolicy(),
  val uiHints: CharacterUiHints = CharacterUiHints(),
  val characterKernel: CharacterKernel? = null,
  val compiledCorePrompt: String = "",
  val compiledPersonaPrompt: String = "",
  val compiledWorldPrompt: String = "",
  val compiledStylePrompt: String = "",
  val styleRepairPrompt: String = "",
  val compiledExampleDigest: String = "",
  val corePromptTokenEstimate: Int = 0,
  val personaPromptTokenEstimate: Int = 0,
  val worldPromptTokenEstimate: Int = 0,
  val stylePromptTokenEstimate: Int = 0,
  val exampleDigestTokenEstimate: Int = 0,
  val compiledTotalTokenEstimate: Int = 0,
  val oversizeWarning: Boolean = false,
  val sourceFingerprint: String = "",
  val compiledAt: Long? = null,
)

data class RoleInteropState(
  val sourceFormat: RoleCardSourceFormat = RoleCardSourceFormat.INTERNAL,
  val sourceSpec: String? = null,
  val sourceSpecVersion: String? = null,
  val importedAt: Long? = null,
  val exportTargetDefault: RoleCardExportTarget = RoleCardExportTarget.ST_V2_JSON,
  val rawCardJson: String? = null,
  val rawUnknownTopLevelJson: String? = null,
  val rawUnknownDataJson: String? = null,
  val rawUnknownExtensionsJson: String? = null,
  val compatibilityWarnings: List<String> = emptyList(),
  val migrationNotes: List<String> = emptyList(),
)

fun StCharacterCard.cardDataOrEmpty(): StCharacterCardData = data ?: StCharacterCardData()

fun StCharacterCard.resolvedName(): String = cardDataOrEmpty().name.orEmpty().ifBlank { name.orEmpty() }

fun StCharacterCard.resolvedDescription(): String =
  cardDataOrEmpty().description.orEmpty().ifBlank { description.orEmpty() }

fun StCharacterCard.resolvedPersonality(): String =
  cardDataOrEmpty().personality.orEmpty().ifBlank { personality.orEmpty() }

fun StCharacterCard.resolvedScenario(): String =
  cardDataOrEmpty().scenario.orEmpty().ifBlank { scenario.orEmpty() }

fun StCharacterCard.resolvedFirstMessage(): String =
  cardDataOrEmpty().first_mes.orEmpty().ifBlank { first_mes.orEmpty() }

fun StCharacterCard.resolvedMessageExample(): String =
  cardDataOrEmpty().mes_example.orEmpty().ifBlank { mes_example.orEmpty() }

fun StCharacterCard.resolvedSystemPrompt(): String = cardDataOrEmpty().system_prompt.orEmpty()

fun StCharacterCard.resolvedTags(): List<String> = cardDataOrEmpty().tags ?: tags.orEmpty()

fun StCharacterCard.withUpdatedCoreFields(
  name: String = resolvedName(),
  description: String = resolvedDescription(),
  personality: String = resolvedPersonality(),
  scenario: String = resolvedScenario(),
  firstMessage: String = resolvedFirstMessage(),
  messageExample: String = resolvedMessageExample(),
  systemPrompt: String = resolvedSystemPrompt(),
  tags: List<String> = resolvedTags(),
): StCharacterCard {
  val data = cardDataOrEmpty()
  return copy(
    spec = spec ?: "chara_card_v2",
    spec_version = spec_version ?: "2.0",
    name = name,
    description = description,
    personality = personality,
    scenario = scenario,
    first_mes = firstMessage,
    mes_example = messageExample,
    tags = tags,
    data =
      data.copy(
        name = name,
        description = description,
        personality = personality,
        scenario = scenario,
        first_mes = firstMessage,
        mes_example = messageExample,
        system_prompt = systemPrompt,
        tags = tags,
      ),
  )
}
