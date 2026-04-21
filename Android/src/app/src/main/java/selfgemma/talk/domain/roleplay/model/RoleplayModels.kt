package selfgemma.talk.domain.roleplay.model

enum class MessageSide {
  USER,
  ASSISTANT,
  SYSTEM,
}

enum class MessageKind {
  TEXT,
  EVENT,
  IMAGE,
  AUDIO,
  WEBVIEW,
}

enum class MessageStatus {
  PENDING,
  STREAMING,
  COMPLETED,
  FAILED,
  INTERRUPTED,
}

enum class MemoryCategory {
  PREFERENCE,
  RELATION,
  WORLD,
  PLOT,
  TODO,
  RULE,
}

enum class SessionEventType {
  MODEL_SWITCH,
  SUMMARY_UPDATE,
  MEMORY_UPSERT,
  MEMORY_PLANNER_TRIGGERED,
  MEMORY_QUERY_EXECUTED,
  MEMORY_PACK_COMPILED,
  MEMORY_RUNTIME_STATE_UPDATED,
  MEMORY_THREAD_UPSERTED,
  MEMORY_ATOM_UPSERTED,
  MEMORY_ATOM_PROMOTED,
  MEMORY_OP_REJECTED,
  MEMORY_CORRECTION_APPLIED,
  MEMORY_COMPACTION_UPSERTED,
  ROLE_DRIFT_DETECTED,
  ROLE_STYLE_REPAIR_APPLIED,
  CONTINUITY_ROLLBACK_APPLIED,
  CONTINUITY_REGENERATE_TRIGGERED,
  CONTINUITY_EDIT_TRIGGERED,
  MEMORY_REBUILD_COMPLETED,
  TOOL_CALL_STARTED,
  TOOL_CALL_COMPLETED,
  TOOL_CALL_FAILED,
  TOOL_CHAIN_ABORTED,
  TOOL_RESULT_APPLIED,
  RESET,
  EXPORT,
  CONTEXT_BUDGET_APPLIED,
  CONTEXT_OVERFLOW_RECOVERED,
}

data class RoleCard(
  val id: String,
  val stCard: StCharacterCard,
  val avatarUri: String? = null,
  val coverUri: String? = null,
  val safetyPolicy: String = "",
  val defaultModelId: String? = null,
  val defaultTemperature: Float? = null,
  val defaultTopP: Float? = null,
  val defaultTopK: Int? = null,
  val enableThinking: Boolean = false,
  val summaryTurnThreshold: Int = 6,
  val memoryEnabled: Boolean = true,
  val memoryMaxItems: Int = 32,
  val runtimeProfile: RoleRuntimeProfile? = null,
  val mediaProfile: RoleMediaProfile? = null,
  val interopState: RoleInteropState? = null,
  val builtIn: Boolean = false,
  val archived: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
) {
  constructor(
    id: String,
    name: String,
    avatarUri: String? = null,
    coverUri: String? = null,
    summary: String = "",
    systemPrompt: String,
    personaDescription: String = "",
    worldSettings: String = "",
    openingLine: String = "",
    exampleDialogues: List<String> = emptyList(),
    safetyPolicy: String = "",
    defaultModelId: String? = null,
    defaultTemperature: Float? = null,
    defaultTopP: Float? = null,
    defaultTopK: Int? = null,
    enableThinking: Boolean = false,
    summaryTurnThreshold: Int = 6,
    memoryEnabled: Boolean = true,
    memoryMaxItems: Int = 32,
    tags: List<String> = emptyList(),
    cardCore: StCharacterCard? = null,
    runtimeProfile: RoleRuntimeProfile? = null,
    mediaProfile: RoleMediaProfile? = null,
    interopState: RoleInteropState? = null,
    builtIn: Boolean = false,
    archived: Boolean = false,
    createdAt: Long,
    updatedAt: Long,
  ) : this(
    id = id,
    stCard =
      (cardCore ?: legacyFieldsToStCard(
        name = name,
        summary = summary,
        systemPrompt = systemPrompt,
        personaDescription = personaDescription,
        worldSettings = worldSettings,
        openingLine = openingLine,
        exampleDialogues = exampleDialogues,
        tags = tags,
      )).mergedWithFallbackFields(
        fallbackName = name,
        fallbackSummary = summary,
        fallbackSystemPrompt = systemPrompt,
        fallbackPersonaDescription = personaDescription,
        fallbackWorldSettings = worldSettings,
        fallbackOpeningLine = openingLine,
        fallbackExampleDialogues = exampleDialogues,
        fallbackTags = tags,
      ),
    avatarUri = avatarUri,
    coverUri = coverUri,
    safetyPolicy = safetyPolicy,
    defaultModelId = defaultModelId,
    defaultTemperature = defaultTemperature,
    defaultTopP = defaultTopP,
    defaultTopK = defaultTopK,
    enableThinking = enableThinking,
    summaryTurnThreshold = summaryTurnThreshold,
    memoryEnabled = memoryEnabled,
    memoryMaxItems = memoryMaxItems,
    runtimeProfile = runtimeProfile,
    mediaProfile = mediaProfile,
    interopState = interopState,
    builtIn = builtIn,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )

  val name: String
    get() = stCard.resolvedName()

  val summary: String
    get() = stCard.resolvedDescription()

  val systemPrompt: String
    get() = stCard.cardDataOrEmpty().system_prompt.orEmpty()

  val personaDescription: String
    get() = stCard.resolvedPersonality()

  val worldSettings: String
    get() = stCard.resolvedScenario()

  val openingLine: String
    get() = stCard.resolvedFirstMessage()

  val exampleDialogues: List<String>
    get() = stCard.resolvedMessageExample().toExampleDialogues()

  val tags: List<String>
    get() = stCard.cardDataOrEmpty().tags ?: stCard.tags.orEmpty()

  val cardCore: StCharacterCard
    get() = stCard
}

data class Session(
  val id: String,
  val roleId: String,
  val title: String,
  val activeModelId: String,
  val pinned: Boolean = false,
  val archived: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val lastMessageAt: Long,
  val lastSummary: String? = null,
  val lastUserMessageExcerpt: String? = null,
  val lastAssistantMessageExcerpt: String? = null,
  val turnCount: Int = 0,
  val summaryVersion: Int = 0,
  val draftInput: String = "",
  val interopChatMetadataJson: String? = null,
  val sessionUserProfile: StUserProfile? = null,
)

data class Message(
  val id: String,
  val sessionId: String,
  val seq: Int,
  val branchId: String = "main",
  val side: MessageSide,
  val kind: MessageKind = MessageKind.TEXT,
  val status: MessageStatus = MessageStatus.PENDING,
  val accepted: Boolean = true,
  val isCanonical: Boolean = true,
  val content: String = "",
  val isMarkdown: Boolean = false,
  val errorMessage: String? = null,
  val latencyMs: Double? = null,
  val accelerator: String? = null,
  val parentMessageId: String? = null,
  val regenerateGroupId: String? = null,
  val editedFromMessageId: String? = null,
  val supersededMessageId: String? = null,
  val metadataJson: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

data class SessionSummary(
  val sessionId: String,
  val version: Int,
  val coveredUntilSeq: Int,
  val summaryText: String,
  val tokenEstimate: Int,
  val updatedAt: Long,
)

data class MemoryItem(
  val id: String,
  val roleId: String,
  val sessionId: String? = null,
  val category: MemoryCategory,
  val content: String,
  val normalizedHash: String,
  val confidence: Float = 0f,
  val pinned: Boolean = false,
  val active: Boolean = true,
  val sourceMessageIds: List<String> = emptyList(),
  val createdAt: Long,
  val updatedAt: Long,
  val lastUsedAt: Long? = null,
)

data class SessionEvent(
  val id: String,
  val sessionId: String,
  val eventType: SessionEventType,
  val payloadJson: String = "{}",
  val createdAt: Long,
)

private fun legacyFieldsToStCard(
  name: String,
  summary: String,
  systemPrompt: String,
  personaDescription: String,
  worldSettings: String,
  openingLine: String,
  exampleDialogues: List<String>,
  tags: List<String>,
): StCharacterCard {
  val mesExample = exampleDialogues.map(String::trim).filter(String::isNotBlank).joinToString("\n\n")
  val data =
    StCharacterCardData(
      name = name,
      description = summary,
      personality = personaDescription,
      scenario = worldSettings,
      first_mes = openingLine,
      mes_example = mesExample,
      system_prompt = systemPrompt,
      tags = tags,
    )
  return StCharacterCard(
    spec = "chara_card_v2",
    spec_version = "2.0",
    name = name,
    description = summary,
    personality = personaDescription,
    scenario = worldSettings,
    first_mes = openingLine,
    mes_example = mesExample,
    tags = tags,
    data = data,
  )
}

private fun StCharacterCard.mergedWithFallbackFields(
  fallbackName: String,
  fallbackSummary: String,
  fallbackSystemPrompt: String,
  fallbackPersonaDescription: String,
  fallbackWorldSettings: String,
  fallbackOpeningLine: String,
  fallbackExampleDialogues: List<String>,
  fallbackTags: List<String>,
): StCharacterCard {
  val fallbackMesExample =
    fallbackExampleDialogues
      .map(String::trim)
      .filter(String::isNotBlank)
      .joinToString("\n\n")
  val data = cardDataOrEmpty()
  val mergedName = resolvedName().ifBlank { fallbackName }
  val mergedSummary = resolvedDescription().ifBlank { fallbackSummary }
  val mergedPersonality = resolvedPersonality().ifBlank { fallbackPersonaDescription }
  val mergedScenario = resolvedScenario().ifBlank { fallbackWorldSettings }
  val mergedFirstMessage = resolvedFirstMessage().ifBlank { fallbackOpeningLine }
  val mergedMesExample = resolvedMessageExample().ifBlank { fallbackMesExample }
  val mergedSystemPrompt = resolvedSystemPrompt().ifBlank { fallbackSystemPrompt }
  val mergedTags = resolvedTags().ifEmpty { fallbackTags }
  return copy(
    spec = spec ?: "chara_card_v2",
    spec_version = spec_version ?: "2.0",
    name = mergedName,
    description = mergedSummary,
    personality = mergedPersonality,
    scenario = mergedScenario,
    first_mes = mergedFirstMessage,
    mes_example = mergedMesExample,
    tags = mergedTags,
    data =
      data.copy(
        name = data.name ?: mergedName,
        description = data.description ?: mergedSummary,
        personality = data.personality ?: mergedPersonality,
        scenario = data.scenario ?: mergedScenario,
        first_mes = data.first_mes ?: mergedFirstMessage,
        mes_example = data.mes_example ?: mergedMesExample,
        system_prompt = data.system_prompt ?: mergedSystemPrompt,
        tags = data.tags ?: mergedTags,
      ),
  )
}

private fun String.toExampleDialogues(): List<String> {
  return split("\n\n").map(String::trim).filter(String::isNotBlank)
}

fun RoleCard.resolvedName(): String = stCard.resolvedName()

fun RoleCard.resolvedSummary(): String = stCard.resolvedDescription()

fun RoleCard.resolvedSystemPrompt(): String = stCard.resolvedSystemPrompt()

fun RoleCard.resolvedPersonaDescription(): String = stCard.resolvedPersonality()

fun RoleCard.resolvedWorldSettings(): String = stCard.resolvedScenario()

fun RoleCard.resolvedOpeningLine(): String = stCard.resolvedFirstMessage()

fun RoleCard.resolvedExampleDialogues(): List<String> = exampleDialogues

fun RoleCard.resolvedTags(): List<String> = stCard.resolvedTags()

fun Session.resolveUserProfile(fallback: StUserProfile = StUserProfile()): StUserProfile {
  return sessionUserProfile?.ensureDefaults() ?: fallback.ensureDefaults()
}
