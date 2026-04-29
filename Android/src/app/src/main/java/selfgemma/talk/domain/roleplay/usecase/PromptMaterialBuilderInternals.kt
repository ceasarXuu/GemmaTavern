package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import selfgemma.talk.domain.roleplay.model.CharacterKernel

/**
 * Helpers extracted from [PromptMaterialBuilder] (zero behavior change). They render compact
 * sub-sections of a [CharacterKernel] for prompt assembly. Visibility is `internal` so the
 * builder in the same package can reference them without re-declaration.
 */

internal fun CharacterKernel.renderCoreCharacterPrompt(): String {
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

internal fun CharacterKernel.renderMinimalCoreCharacterPrompt(): String {
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

internal fun CharacterKernel.renderIdentitySummaryPrompt(): String {
  val identity = identityJson.toJsonObjectOrNull() ?: return ""
  return buildList {
    identity.get("role")?.asString?.takeIf(String::isNotBlank)?.let(::add)
    identity.get("core_motive")?.asString?.takeIf(String::isNotBlank)?.let { add("Motive: $it") }
    identity.get("worldview")?.asString?.takeIf(String::isNotBlank)?.let { add("Worldview: $it") }
  }.joinToString(separator = " | ")
}

internal fun CharacterKernel.renderSpeechStylePrompt(): String {
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

internal fun CharacterKernel.renderMinimalSpeechStylePrompt(): String {
  val speechStyle = speechStyleJson.toJsonObjectOrNull() ?: return renderSpeechStylePrompt()
  return buildList {
    speechStyle.get("tone")?.asString?.takeIf(String::isNotBlank)?.let { add("Tone: $it") }
    speechStyle.get("directness")?.asString?.takeIf(String::isNotBlank)?.let { add("Directness: $it") }
  }.joinToString(separator = "\n")
}

internal fun CharacterKernel.renderWorldviewPrompt(): String {
  return identityJson.toJsonObjectOrNull()?.get("worldview")?.asString?.trim().orEmpty()
}

internal fun CharacterKernel.renderMicroExemplarPrompt(): String {
  return microExemplar.trim().takeIf(String::isNotBlank)?.let { "Micro exemplar: $it" }.orEmpty()
}

internal fun mergePromptFragments(vararg fragments: String?): String {
  return fragments.map(String?::orEmpty).map(String::trim).filter(String::isNotBlank).joinToString(separator = "\n")
}

internal data class ConversationVariants(
  val full: String,
  val compact: String,
  val minimal: String,
)

internal data class RuntimeStateVariants(
  val full: String = "",
  val compact: String = "",
  val minimal: String = "",
)

internal data class ResolvedRuntimeField(
  val label: String,
  val value: String,
)

internal data class MemoryVariants(
  val full: String,
  val compact: String,
  val minimal: String,
)

internal val PMB_CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")

internal val PMB_SCENE_FIELD_ORDER =
  listOf(
    "location",
    "time|timeOfDay",
    "currentGoal|goal",
    "dangerLevel|hazards",
    "importantItems|inventory",
    "activeTopic",
    "recentAction",
  )

internal val PMB_RELATIONSHIP_FIELD_ORDER =
  listOf("trust", "intimacy", "tension", "dependence", "initiative|dominance", "respect", "fear", "currentMood", "lastShiftReason")

internal const val PMB_MAX_THREAD_LINE_LENGTH = 180
internal const val PMB_MAX_MEMORY_LINE_LENGTH = 180
internal const val PMB_MAX_EXTERNAL_FACT_LINE_LENGTH = 220
