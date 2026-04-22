package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.CharacterKernel
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.name
import selfgemma.talk.domain.roleplay.model.personaDescriptionInPrompt
import selfgemma.talk.domain.roleplay.model.personaDescription
import selfgemma.talk.domain.roleplay.model.summary
import selfgemma.talk.domain.roleplay.model.systemPrompt
import selfgemma.talk.domain.roleplay.model.userPersonaDescription
import selfgemma.talk.domain.roleplay.model.worldSettings
import selfgemma.talk.domain.roleplay.model.freshness

private const val FULL_RECENT_DIALOGUE_TOKEN_BUDGET = 1800
private const val COMPACT_RECENT_DIALOGUE_TOKEN_BUDGET = 640
private const val MINIMAL_RECENT_DIALOGUE_TOKEN_BUDGET = 320
private const val MAX_DIALOGUE_LINE_LENGTH = 280

internal class PromptMaterialBuilder @Inject constructor(private val tokenEstimator: TokenEstimator) {
  fun build(
    runtimeRole: StChatRuntimeRole,
    runtimeProfile: RoleRuntimeProfile?,
    summary: SessionSummary?,
    memories: List<MemoryItem>,
    runtimeStateSnapshot: RuntimeStateSnapshot?,
    openThreads: List<OpenThread>,
    memoryAtoms: List<MemoryAtom>,
    recentMessages: List<Message>,
    externalFacts: List<RoleplayExternalFact>,
    hasRuntimeTools: Boolean,
    macroContext: StMacroContext,
    resolvedCharacterBook: StResolvedPromptRuntime,
    postHistoryBlock: String,
    depthPromptBlock: String,
    combinedExampleDialogue: String,
  ): PromptMaterial {
    val characterKernel = runtimeProfile?.characterKernel
    val recentConversationVariants = buildRecentConversationVariants(runtimeRole = runtimeRole, recentMessages = recentMessages)
    val externalFactVariants = buildExternalFactVariants(externalFacts)
    val runtimeStateVariants = buildRuntimeStateVariants(runtimeStateSnapshot)
    val openThreadVariants = buildOpenThreadVariants(openThreads)
    val memoryAtomVariants = buildMemoryAtomVariants(memoryAtoms)
    val memoryVariants = buildMemoryVariants(memories)
    val sections =
      buildList {
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.CORE_CHARACTER,
            title = "Core Character",
            fullBody = macroContext.substitute(runtimeRole.systemPrompt()).trim(),
            compactBody = characterKernel?.renderCoreCharacterPrompt() ?: runtimeProfile?.compiledCorePrompt,
            minimalBody = characterKernel?.renderMinimalCoreCharacterPrompt() ?: runtimeProfile?.compiledCorePrompt?.take(320),
            priority = PromptSectionPriority.REQUIRED,
            required = true,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.LOREBOOK_BEFORE,
            title = "Lorebook",
            fullBody = resolvedCharacterBook.beforePrompt,
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.CHARACTER_SUMMARY,
            title = "Character Summary",
            fullBody = macroContext.substitute(runtimeRole.summary()).trim(),
            compactBody =
              characterKernel?.renderIdentitySummaryPrompt()
                ?: runtimeProfile?.summary?.takeIf { it.isNotBlank() }
                ?: macroContext.substitute(runtimeRole.summary()).take(280),
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.CHARACTER_PERSONALITY,
            title = "Personality",
            fullBody =
              mergePromptFragments(
                macroContext.substitute(runtimeRole.personaDescription()).trim(),
                runtimeProfile?.styleRepairPrompt,
              ),
            compactBody =
              mergePromptFragments(
                characterKernel?.renderSpeechStylePrompt() ?: runtimeProfile?.compiledPersonaPrompt,
                if (characterKernel == null) runtimeProfile?.compiledStylePrompt else null,
                runtimeProfile?.styleRepairPrompt,
              ),
            minimalBody =
              mergePromptFragments(
                characterKernel?.renderMinimalSpeechStylePrompt() ?: runtimeProfile?.compiledPersonaPrompt?.take(220),
                if (characterKernel == null) runtimeProfile?.compiledStylePrompt?.take(160) else null,
                runtimeProfile?.styleRepairPrompt?.take(160),
              ),
            priority = PromptSectionPriority.MEDIUM,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.PERSONA,
            title = "Persona",
            fullBody = macroContext.substitute(runtimeRole.userProfile.personaDescriptionInPrompt()).trim(),
            compactBody = macroContext.substitute(runtimeRole.userProfile.personaDescriptionInPrompt()).trim().take(220),
            minimalBody = macroContext.substitute(runtimeRole.userProfile.personaDescriptionInPrompt()).trim().take(160),
            priority = PromptSectionPriority.MEDIUM,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.WORLD,
            title = "World",
            fullBody = macroContext.substitute(runtimeRole.worldSettings()).trim(),
            compactBody = characterKernel?.renderWorldviewPrompt() ?: runtimeProfile?.compiledWorldPrompt,
            minimalBody = characterKernel?.renderWorldviewPrompt()?.take(220) ?: runtimeProfile?.compiledWorldPrompt?.take(220),
            priority = PromptSectionPriority.MEDIUM,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.SAFETY,
            title = "Safety",
            fullBody = runtimeRole.safetyPolicy,
            priority = PromptSectionPriority.REQUIRED,
            required = true,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.EXTERNAL_FACTS,
            title = "External Evidence",
            fullBody = externalFactVariants.full,
            compactBody = externalFactVariants.compact,
            minimalBody = externalFactVariants.minimal,
            priority = PromptSectionPriority.HIGH,
            required = externalFacts.isNotEmpty(),
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.RUNTIME_STATE,
            title = "Runtime State",
            fullBody = runtimeStateVariants.full,
            compactBody = runtimeStateVariants.compact,
            minimalBody = runtimeStateVariants.minimal,
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.OPEN_THREADS,
            title = "Open Threads",
            fullBody = openThreadVariants.full,
            compactBody = openThreadVariants.compact,
            minimalBody = openThreadVariants.minimal,
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.SEMANTIC_MEMORY,
            title = "Semantic Memory",
            fullBody = memoryAtomVariants.full,
            compactBody = memoryAtomVariants.compact,
            minimalBody = memoryAtomVariants.minimal,
            priority = PromptSectionPriority.LOW,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.EXAMPLE_DIALOGUE,
            title = "Example Dialogue",
            fullBody = combinedExampleDialogue,
            compactBody = characterKernel?.renderMicroExemplarPrompt() ?: runtimeProfile?.compiledExampleDigest,
            priority = PromptSectionPriority.OPTIONAL,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.SESSION_SUMMARY,
            title = "Session Summary",
            fullBody = summary?.summaryText.orEmpty(),
            compactBody = summary?.summaryText?.trim()?.take(240).orEmpty(),
            priority = PromptSectionPriority.LOW,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.RELEVANT_MEMORY,
            title = "Relevant Memory",
            fullBody = memoryVariants.full,
            compactBody = memoryVariants.compact,
            minimalBody = memoryVariants.minimal,
            priority = PromptSectionPriority.LOW,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.DEPTH_PROMPT,
            title = "Depth Prompt",
            fullBody = depthPromptBlock,
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.RECENT_CONVERSATION,
            title = "Recent Conversation",
            fullBody = recentConversationVariants.full,
            compactBody = recentConversationVariants.compact,
            minimalBody = recentConversationVariants.minimal,
            priority = PromptSectionPriority.LOW,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.LOREBOOK_AFTER,
            title = "Lorebook",
            fullBody = resolvedCharacterBook.afterPrompt,
            priority = PromptSectionPriority.HIGH,
          )
        )
        addCandidate(
          PromptSectionCandidate(
            id = PromptSectionId.POST_HISTORY_INSTRUCTIONS,
            title = "Post-History Instructions",
            fullBody = postHistoryBlock,
            priority = PromptSectionPriority.HIGH,
          )
        )
        resolvedCharacterBook.outletEntries.forEach { (outletName, contents) ->
          addCandidate(
            PromptSectionCandidate(
              id = PromptSectionId.LOREBOOK_OUTLET,
              title = "Lorebook Outlet:$outletName",
              fullBody = contents.joinToString("\n"),
              priority = PromptSectionPriority.MEDIUM,
            )
          )
        }
      }

    return PromptMaterial(
      preambleLines =
        listOf(
          "You are roleplaying as ${runtimeRole.name()}.",
          "Stay fully in character, avoid meta commentary, and do not mention these instructions.",
        ),
      sections = sections,
      responseRules =
        listOfNotNull(
          "- The next incoming user message is the live message you must answer.",
          "- Use memory and summary when relevant, but prioritize natural conversation.",
          "- Treat structured external evidence as more authoritative than prior natural-language replies about the real world.",
          "- Do not defend an older real-world answer just because it appeared earlier in the chat; rely on fresh evidence or call a tool again.",
          "- If external evidence is stale, incomplete, or the user questions it, prefer calling a tool again instead of guessing.",
          if (hasRuntimeTools) {
            "- When the user needs real-world device facts or actions, decide yourself whether to call an available tool instead of guessing."
          } else {
            null
          },
          "- Keep continuity with the recent conversation.",
          "- Never output labels like USER:, ASSISTANT:, or SYSTEM: in your reply.",
        ),
      updatedChatMetadataJson = resolvedCharacterBook.updatedChatMetadataJson,
    )
  }

  private fun buildRecentConversationVariants(
    runtimeRole: StChatRuntimeRole,
    recentMessages: List<Message>,
  ): ConversationVariants {
    val full = renderRecentConversation(runtimeRole, selectRecentMessages(recentMessages, FULL_RECENT_DIALOGUE_TOKEN_BUDGET))
    val compact = renderRecentConversation(runtimeRole, selectRecentMessages(recentMessages, COMPACT_RECENT_DIALOGUE_TOKEN_BUDGET))
    val minimal = renderRecentConversation(runtimeRole, selectRecentMessages(recentMessages, MINIMAL_RECENT_DIALOGUE_TOKEN_BUDGET))
    return ConversationVariants(full = full, compact = compact, minimal = minimal)
  }

  private fun renderRecentConversation(runtimeRole: StChatRuntimeRole, messages: List<Message>): String {
    return messages.joinToString("\n") { message ->
      "${message.side.toSpeakerLabel(runtimeRole)}: ${message.toPromptRenderableContent().toPromptLine(MAX_DIALOGUE_LINE_LENGTH)}"
    }
  }

  private fun buildRuntimeStateVariants(snapshot: RuntimeStateSnapshot?): RuntimeStateVariants {
    if (snapshot == null) {
      return RuntimeStateVariants()
    }

    val sceneJson = snapshot.sceneJson.toJsonObjectOrNull()
    val relationshipJson = snapshot.relationshipJson.toJsonObjectOrNull()
    val fullLines =
      buildList {
        addAll(renderNamedFields("Scene", snapshot.sceneJson, SCENE_FIELD_ORDER))
        addAll(renderNamedFields("Relationship", snapshot.relationshipJson, RELATIONSHIP_FIELD_ORDER))
        renderEntitySummary(snapshot.activeEntitiesJson)?.let(::add)
      }

    val compactLines =
      buildList {
        renderSummaryLine(
          sectionLabel = "Scene",
          jsonObject = sceneJson,
          orderedKeys = listOf("location", "time|timeOfDay", "currentGoal|goal", "dangerLevel|hazards", "activeTopic"),
        )?.let(::add)
        renderEntitySummary(snapshot.activeEntitiesJson)?.let(::add)
        renderSummaryLine(
          sectionLabel = "Relationship",
          jsonObject = relationshipJson,
          orderedKeys = listOf("currentMood", "trust", "tension", "intimacy", "respect", "fear"),
        )?.let(::add)
      }.ifEmpty { fullLines.take(3) }
    val minimalLines =
      buildList {
        renderSummaryLine(
          sectionLabel = "Scene",
          jsonObject = sceneJson,
          orderedKeys = listOf("location", "currentGoal|goal", "dangerLevel|hazards"),
        )?.let(::add)
        renderSummaryLine(
          sectionLabel = "Relationship",
          jsonObject = relationshipJson,
          orderedKeys = listOf("currentMood", "trust", "tension"),
        )?.let(::add)
      }
        .ifEmpty { fullLines.take(2) }

    return RuntimeStateVariants(
      full = fullLines.joinToString("\n"),
      compact = compactLines.joinToString("\n"),
      minimal = minimalLines.joinToString("\n"),
    )
  }

  private fun buildOpenThreadVariants(openThreads: List<OpenThread>): MemoryVariants {
    val rendered =
      openThreads.map { thread ->
        "- [${thread.type.name.lowercase()}/${thread.owner.name.lowercase()}/p${thread.priority}] ${thread.content.normalizeWhitespace().take(MAX_THREAD_LINE_LENGTH)}"
      }
    return MemoryVariants(
      full = rendered.joinToString("\n"),
      compact = rendered.take(2).joinToString("\n"),
      minimal = rendered.take(1).joinToString("\n"),
    )
  }

  private fun buildExternalFactVariants(externalFacts: List<RoleplayExternalFact>): MemoryVariants {
    val rendered =
      externalFacts.map { fact ->
        "- [${fact.freshnessLabel()}/${fact.sourceToolName.normalizeWhitespace()}] ${fact.title.normalizeWhitespace()}: ${fact.content.normalizeWhitespace().take(MAX_EXTERNAL_FACT_LINE_LENGTH)}"
      }
    return MemoryVariants(
      full = rendered.joinToString("\n"),
      compact = rendered.take(2).joinToString("\n"),
      minimal = rendered.take(1).joinToString("\n"),
    )
  }

  private fun buildMemoryAtomVariants(memoryAtoms: List<MemoryAtom>): MemoryVariants {
    val rendered =
      memoryAtoms.map { atom ->
        "- ${atom.subject.normalizeWhitespace()} ${atom.predicate.normalizeWhitespace()}: ${atom.objectValue.normalizeWhitespace().take(MAX_MEMORY_LINE_LENGTH)}"
      }
    return MemoryVariants(
      full = rendered.joinToString("\n"),
      compact = rendered.take(2).joinToString("\n"),
      minimal = rendered.take(1).joinToString("\n"),
    )
  }

  private fun buildMemoryVariants(memories: List<MemoryItem>): MemoryVariants {
    val rendered =
      memories.map { memory ->
        "- ${memory.category.name.lowercase()}: ${memory.content.trim()}"
      }
    return MemoryVariants(
      full = rendered.joinToString("\n"),
      compact = rendered.take(2).joinToString("\n"),
      minimal = rendered.take(1).joinToString("\n"),
    )
  }

  private fun MutableList<PromptSectionCandidate>.addCandidate(candidate: PromptSectionCandidate) {
    if (
      candidate.fullBody.isBlank() &&
        candidate.compactBody.isNullOrBlank() &&
        candidate.minimalBody.isNullOrBlank()
    ) {
      return
    }
    add(candidate)
  }

  private fun selectRecentMessages(messages: List<Message>, tokenBudget: Int): List<Message> {
    return selectPromptWindowMessages(
      messages = messages,
      tokenBudget = tokenBudget,
      tokenEstimator = tokenEstimator,
    )
  }

  private fun String.toPromptLine(maxLength: Int): String {
    return trim().replace(WHITESPACE_REGEX, " ").take(maxLength)
  }

  private fun MessageSide.toSpeakerLabel(runtimeRole: StChatRuntimeRole): String {
    return when (this) {
      MessageSide.USER -> runtimeRole.userName
      MessageSide.ASSISTANT -> runtimeRole.name()
      MessageSide.SYSTEM -> "System"
    }
  }

  private fun renderNamedFields(sectionLabel: String, json: String, orderedKeys: List<String>): List<String> {
    val jsonObject = json.toJsonObjectOrNull() ?: return emptyList()
    return orderedKeys.mapNotNull { keyAliases ->
      val resolvedField = jsonObject.resolveRuntimeField(keyAliases) ?: return@mapNotNull null
      "$sectionLabel ${resolvedField.label}: ${resolvedField.value.take(MAX_RUNTIME_STATE_VALUE_LENGTH)}"
    }
  }

  private fun renderEntitySummary(json: String): String? {
    val jsonObject = json.toJsonObjectOrNull() ?: return null
    val present = jsonObject.get("present")?.toReadableValue()
    val focus = jsonObject.get("focus")?.toReadableValue()
    return when {
      !present.isNullOrBlank() && !focus.isNullOrBlank() && present != focus ->
        "Active entities: ${present.take(MAX_RUNTIME_STATE_VALUE_LENGTH)} | Focus: ${focus.take(MAX_RUNTIME_STATE_VALUE_LENGTH)}"
      !focus.isNullOrBlank() -> "Active entities: ${focus.take(MAX_RUNTIME_STATE_VALUE_LENGTH)}"
      !present.isNullOrBlank() -> "Active entities: ${present.take(MAX_RUNTIME_STATE_VALUE_LENGTH)}"
      else -> null
    }
  }

  private fun renderSummaryLine(sectionLabel: String, jsonObject: JsonObject?, orderedKeys: List<String>): String? {
    val fields =
      orderedKeys.mapNotNull { keyAliases ->
        jsonObject?.resolveRuntimeField(keyAliases)?.let { resolvedField -> "${resolvedField.label}=${resolvedField.value}" }
      }
    if (fields.isEmpty()) {
      return null
    }
    return "$sectionLabel: ${fields.joinToString(", ").take(MAX_RUNTIME_STATE_VALUE_LENGTH)}"
  }

  private fun String?.toJsonObjectOrNull(): JsonObject? {
    if (this.isNullOrBlank()) {
      return null
    }
    return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
  }

  private fun com.google.gson.JsonElement.toReadableValue(): String? {
    return when {
      isJsonNull -> null
      isJsonPrimitive -> asString.normalizeWhitespace().ifBlank { null }
      isJsonArray -> {
        asJsonArray
          .asSequence()
          .mapNotNull { element -> element.toReadableValue() }
          .filter(String::isNotBlank)
          .joinToString(", ")
          .ifBlank { null }
      }
      isJsonObject -> {
        asJsonObject
          .entrySet()
          .mapNotNull { (key, value) ->
            value.toReadableValue()?.let { renderedValue -> "${key.toDisplayLabel()}=$renderedValue" }
          }
          .joinToString(", ")
          .ifBlank { null }
      }
      else -> null
    }
  }

  private fun String.toDisplayLabel(): String {
    return replace(CAMEL_CASE_REGEX, "$1 $2").lowercase()
  }

  private fun JsonObject.resolveRuntimeField(keyAliases: String): ResolvedRuntimeField? {
    val aliases = keyAliases.split("|")
    aliases.forEach { key ->
      val renderedValue = get(key)?.toReadableValue() ?: return@forEach
      return ResolvedRuntimeField(
        label = aliases.first().toDisplayLabel(),
        value = renderedValue,
      )
    }
    return null
  }

  private fun String.normalizeWhitespace(): String {
    return trim().replace(WHITESPACE_REGEX, " ")
  }

  private data class ConversationVariants(
    val full: String,
    val compact: String,
    val minimal: String,
  )

  private data class RuntimeStateVariants(
    val full: String = "",
    val compact: String = "",
    val minimal: String = "",
  )

  private data class ResolvedRuntimeField(
    val label: String,
    val value: String,
  )

  private data class MemoryVariants(
    val full: String,
    val compact: String,
    val minimal: String,
  )

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")
    private val SCENE_FIELD_ORDER =
      listOf(
        "location",
        "time|timeOfDay",
        "currentGoal|goal",
        "dangerLevel|hazards",
        "importantItems|inventory",
        "activeTopic",
        "recentAction",
      )
    private val RELATIONSHIP_FIELD_ORDER =
      listOf("trust", "intimacy", "tension", "dependence", "initiative|dominance", "respect", "fear", "currentMood", "lastShiftReason")
    private const val MAX_THREAD_LINE_LENGTH = 180
    private const val MAX_MEMORY_LINE_LENGTH = 180
    private const val MAX_EXTERNAL_FACT_LINE_LENGTH = 220
    private const val MAX_RUNTIME_STATE_VALUE_LENGTH = 180
  }

  private fun RoleplayExternalFact.freshnessLabel(): String {
    return when (freshness()) {
      RoleplayExternalFactFreshness.FRESH -> "fresh"
      RoleplayExternalFactFreshness.STALE -> "stale"
      RoleplayExternalFactFreshness.STABLE -> "stable"
    }
  }
}

private fun CharacterKernel.renderCoreCharacterPrompt(): String {
  val identity = identityJson.toJsonObjectOrNull() ?: return ""
  val invariants = invariantsJson.toJsonObjectOrNull()?.getAsJsonArray("rules").toStringList()
  return buildList {
    add(identity.entrySet().joinToString(separator = "\n") { (key, value) ->
      "${key.replace('_', ' ').replaceFirstChar { char -> char.uppercase() }}: ${value.asString}"
    })
    if (invariants.isNotEmpty()) {
      add(
        buildString {
          appendLine("Invariants:")
          invariants.take(4).forEach { invariant -> appendLine("- $invariant") }
        }.trim()
      )
    }
  }.joinToString(separator = "\n").trim()
}

private fun CharacterKernel.renderMinimalCoreCharacterPrompt(): String {
  val identity = identityJson.toJsonObjectOrNull() ?: return renderCoreCharacterPrompt()
  val name = identity.get("name")?.asString.orEmpty()
  val role = identity.get("role")?.asString.orEmpty()
  val motive = identity.get("core_motive")?.asString.orEmpty()
  return buildList {
    if (name.isNotBlank()) add("Name: $name")
    if (role.isNotBlank()) add("Role: $role")
    if (motive.isNotBlank()) add("Core motive: $motive")
  }.joinToString(separator = "\n")
}

private fun CharacterKernel.renderIdentitySummaryPrompt(): String {
  val identity = identityJson.toJsonObjectOrNull() ?: return ""
  return buildList {
    identity.get("role")?.asString?.takeIf(String::isNotBlank)?.let(::add)
    identity.get("core_motive")?.asString?.takeIf(String::isNotBlank)?.let { add("Motive: $it") }
    identity.get("worldview")?.asString?.takeIf(String::isNotBlank)?.let { add("Worldview: $it") }
  }.joinToString(separator = " | ")
}

private fun CharacterKernel.renderSpeechStylePrompt(): String {
  val speechStyle = speechStyleJson.toJsonObjectOrNull() ?: return ""
  val tabooWords = speechStyle.getAsJsonArray("taboo_words").toStringList()
  val recurringPatterns = speechStyle.getAsJsonArray("recurring_patterns").toStringList()
  return buildList {
    speechStyle.get("tone")?.asString?.takeIf(String::isNotBlank)?.let { add("Tone: $it") }
    speechStyle.get("sentence_length")?.asString?.takeIf(String::isNotBlank)?.let { add("Sentence length: $it") }
    speechStyle.get("directness")?.asString?.takeIf(String::isNotBlank)?.let { add("Directness: $it") }
    if (tabooWords.isNotEmpty()) {
      add("Avoid: ${tabooWords.joinToString(", ")}")
    }
    if (recurringPatterns.isNotEmpty()) {
      add("Recurring patterns: ${recurringPatterns.joinToString(" | ")}")
    }
  }.joinToString(separator = "\n")
}

private fun CharacterKernel.renderMinimalSpeechStylePrompt(): String {
  val speechStyle = speechStyleJson.toJsonObjectOrNull() ?: return renderSpeechStylePrompt()
  return buildList {
    speechStyle.get("tone")?.asString?.takeIf(String::isNotBlank)?.let { add("Tone: $it") }
    speechStyle.get("directness")?.asString?.takeIf(String::isNotBlank)?.let { add("Directness: $it") }
  }.joinToString(separator = "\n")
}

private fun CharacterKernel.renderWorldviewPrompt(): String {
  return identityJson.toJsonObjectOrNull()?.get("worldview")?.asString?.trim().orEmpty()
}

private fun CharacterKernel.renderMicroExemplarPrompt(): String {
  return microExemplar.trim().takeIf(String::isNotBlank)?.let { "Micro exemplar: $it" }.orEmpty()
}

private fun String.toJsonObjectOrNull(): JsonObject? {
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}

private fun JsonArray?.toStringList(): List<String> {
  return this?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }.orEmpty()
}

private fun mergePromptFragments(vararg fragments: String?): String {
  return fragments.map(String?::orEmpty).map(String::trim).filter(String::isNotBlank).joinToString(separator = "\n")
}
