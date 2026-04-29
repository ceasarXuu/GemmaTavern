package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import kotlin.math.roundToInt
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry

/**
 * Internal types and extension helpers for [StCharacterBookRuntime] (zero behavior change).
 * These were previously file-private inside StCharacterBookRuntime.kt; moving them out keeps the
 * runtime class focused on activation/scoring orchestration.
 */

internal data class RuntimeEntry(
  val entry: StCharacterBookEntry,
  val order: Int,
  val extensions: StBookEntryRuntimeExtensions,
  val stableKey: String,
  val decorators: Set<String>,
  val characterFilter: StCharacterFilter?,
  val normalizedContent: String,
)

internal data class RuntimeCandidate(
  val entry: RuntimeEntry,
  val score: Int,
  val stickyActive: Boolean,
)

internal data class StWorldRuntimeSettings(
  val defaultScanDepth: Int = 2,
  val minActivations: Int = 0,
  val minActivationsDepthMax: Int = 0,
  val maxRecursionSteps: Int = 0,
  val caseSensitive: Boolean = false,
  val matchWholeWords: Boolean = false,
  val useGroupScoring: Boolean = false,
)

internal data class StCharacterFilter(
  val names: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val isExclude: Boolean = false,
)

internal enum class StScanPhase {
  INITIAL,
  RECURSION,
  MIN_ACTIVATIONS,
}

internal data class ParsedDecorators(
  val decorators: Set<String>,
  val contentWithoutDecorators: String,
)

internal fun Int.toWorldInfoPosition(): StWorldInfoPosition {
  return when (this) {
    0 -> StWorldInfoPosition.BEFORE
    1 -> StWorldInfoPosition.AFTER
    2 -> StWorldInfoPosition.AUTHOR_NOTE_BEFORE
    3 -> StWorldInfoPosition.AUTHOR_NOTE_AFTER
    4 -> StWorldInfoPosition.AT_DEPTH
    5 -> StWorldInfoPosition.EXAMPLE_BEFORE
    6 -> StWorldInfoPosition.EXAMPLE_AFTER
    7 -> StWorldInfoPosition.OUTLET
    else -> StWorldInfoPosition.AFTER
  }
}

internal fun StCharacterBookEntry.stableKey(index: Int): String {
  return stableKey(index, content.orEmpty())
}

internal fun StCharacterBookEntry.stableKey(index: Int, normalizedContent: String): String {
  val base = buildString {
    append(id ?: index)
    append(':')
    append(comment.orEmpty())
    append(':')
    append(normalizedContent)
  }
  return UUID.nameUUIDFromBytes(base.toByteArray()).toString()
}

internal fun StCharacterBookEntry.toRuntimeExtensions(
  runtimeSettings: StWorldRuntimeSettings,
): StBookEntryRuntimeExtensions {
  val extensions = extensions ?: JsonObject()
  return StBookEntryRuntimeExtensions(
    position = extensions.scbrIntOrNull("position"),
    depth = extensions.scbrIntOrNull("depth"),
    role = extensions.scbrIntOrNull("role"),
    selectiveLogic =
      when (extensions.scbrIntOrNull("selectiveLogic")) {
        1 -> StSelectiveLogic.NOT_ALL
        2 -> StSelectiveLogic.NOT_ANY
        3 -> StSelectiveLogic.AND_ALL
        else -> StSelectiveLogic.AND_ANY
      },
    scanDepth = extensions.scbrIntOrNull("scan_depth"),
    caseSensitive = extensions.scbrBooleanOrNull("case_sensitive") ?: runtimeSettings.caseSensitive,
    matchWholeWords = extensions.scbrBooleanOrNull("match_whole_words") ?: runtimeSettings.matchWholeWords,
    matchPersonaDescription = extensions.scbrBooleanOrNull("match_persona_description") ?: false,
    matchCharacterDescription = extensions.scbrBooleanOrNull("match_character_description") ?: false,
    matchCharacterPersonality = extensions.scbrBooleanOrNull("match_character_personality") ?: false,
    matchCharacterDepthPrompt = extensions.scbrBooleanOrNull("match_character_depth_prompt") ?: false,
    matchScenario = extensions.scbrBooleanOrNull("match_scenario") ?: false,
    matchCreatorNotes = extensions.scbrBooleanOrNull("match_creator_notes") ?: false,
    useRegex = use_regex ?: false,
    preventRecursion = extensions.scbrBooleanOrNull("prevent_recursion") ?: false,
    excludeRecursion = extensions.scbrBooleanOrNull("exclude_recursion") ?: false,
    delayUntilRecursion = extensions.scbrIntOrNull("delay_until_recursion") ?: 0,
    probability = (extensions.scbrDoubleOrNull("probability") ?: 100.0).roundToInt().coerceIn(0, 100),
    useProbability = extensions.scbrBooleanOrNull("useProbability") ?: true,
    useGroupScoring = extensions.scbrBooleanOrNull("use_group_scoring") ?: runtimeSettings.useGroupScoring,
    outletName = extensions.scbrStringOrNull("outlet_name").orEmpty(),
    group = extensions.scbrStringOrNull("group").orEmpty(),
    groupOverride = extensions.scbrBooleanOrNull("group_override") ?: false,
    groupWeight = extensions.scbrIntOrNull("group_weight") ?: 100,
    sticky = extensions.scbrIntOrNull("sticky"),
    cooldown = extensions.scbrIntOrNull("cooldown"),
    delay = extensions.scbrIntOrNull("delay"),
    ignoreBudget = extensions.scbrBooleanOrNull("ignore_budget") ?: false,
    triggers = extensions.scbrStringListOrEmpty("triggers"),
  )
}

internal fun StCharacterBook.toRuntimeSettings(): StWorldRuntimeSettings {
  val runtimeExtensions = extensions ?: JsonObject()
  return StWorldRuntimeSettings(
    defaultScanDepth = scan_depth ?: 2,
    minActivations =
      runtimeExtensions.scbrIntOrNull("min_activations")
        ?: runtimeExtensions.scbrIntOrNull("world_info_min_activations")
        ?: 0,
    minActivationsDepthMax =
      runtimeExtensions.scbrIntOrNull("min_activations_depth_max")
        ?: runtimeExtensions.scbrIntOrNull("world_info_min_activations_depth_max")
        ?: 0,
    maxRecursionSteps =
      runtimeExtensions.scbrIntOrNull("max_recursion_steps")
        ?: runtimeExtensions.scbrIntOrNull("world_info_max_recursion_steps")
        ?: 0,
    caseSensitive = runtimeExtensions.scbrBooleanOrNull("case_sensitive") ?: false,
    matchWholeWords = runtimeExtensions.scbrBooleanOrNull("match_whole_words") ?: false,
    useGroupScoring = runtimeExtensions.scbrBooleanOrNull("use_group_scoring") ?: false,
  )
}

internal fun RuntimeEntry.resolvePromptPosition(): StWorldInfoPosition {
  return extensions.position?.toWorldInfoPosition()
    ?: if (entry.position.equals("before_char", ignoreCase = true)) {
      StWorldInfoPosition.BEFORE
    } else {
      StWorldInfoPosition.AFTER
    }
}

internal fun StWorldScanContext.toScanText(
  extensions: StBookEntryRuntimeExtensions,
  runtimeSettings: StWorldRuntimeSettings,
  defaultScanDepth: Int?,
  scanDepthSkew: Int,
  includeRecursionBuffer: Boolean,
  recursionBuffer: List<String>,
): String {
  val scanDepth = (extensions.scanDepth ?: defaultScanDepth ?: runtimeSettings.defaultScanDepth).coerceAtLeast(0) + scanDepthSkew
  val recentChat =
    recentMessagesNewestFirst
      .take(scanDepth)
      .joinToString("\n")
  val selectedGlobalFields =
    buildList {
        if (extensions.matchPersonaDescription) add(userPersonaDescription)
        if (extensions.matchCharacterDescription) add(characterDescription)
        if (extensions.matchCharacterPersonality) add(characterPersonality)
        if (extensions.matchCharacterDepthPrompt) add(characterDepthPrompt)
        if (extensions.matchScenario) add(scenario)
        if (extensions.matchCreatorNotes) add(creatorNotes)
      }
      .filter(String::isNotBlank)
  return buildList {
      if (includeRecursionBuffer) {
        recursionBuffer.filter(String::isNotBlank).forEach(::add)
      }
      recentChat.takeIf(String::isNotBlank)?.let(::add)
      addAll(selectedGlobalFields)
    }
    .joinToString("\n")
}

internal fun String.matchesKeyword(keyword: String, extensions: StBookEntryRuntimeExtensions): Boolean {
  if (isBlank() || keyword.isBlank()) {
    return false
  }
  if (extensions.useRegex || keyword.scbrIsRegexPattern()) {
    val regex = keyword.scbrToRegexOrNull(caseSensitive = extensions.caseSensitive ?: false) ?: return false
    return regex.containsMatchIn(this)
  }

  val caseSensitive = extensions.caseSensitive ?: false
  val haystack = if (caseSensitive) this else lowercase()
  val needle = if (caseSensitive) keyword else keyword.lowercase()
  if (extensions.matchWholeWords == true) {
    val parts = needle.split(Regex("\\s+")).filter(String::isNotBlank)
    if (parts.size > 1) {
      return haystack.contains(needle)
    }
    val regex = Regex("""(?:^|\W)(${Regex.escape(needle)})(?:$|\W)""")
    return regex.containsMatchIn(haystack)
  }
  return haystack.contains(needle)
}

internal fun String.scbrIsRegexPattern(): Boolean = startsWith("/") && lastIndexOf('/') > 0

internal fun String.scbrToRegexOrNull(caseSensitive: Boolean): Regex? {
  return runCatching {
    if (scbrIsRegexPattern()) {
      val lastSlashIndex = lastIndexOf('/')
      val body = substring(1, lastSlashIndex)
      val flags = substring(lastSlashIndex + 1)
      var options = emptySet<RegexOption>()
      if (!caseSensitive && !flags.contains('i')) {
        options = options + RegexOption.IGNORE_CASE
      }
      if (flags.contains('i')) {
        options = options + RegexOption.IGNORE_CASE
      }
      Regex(body, options)
    } else {
      Regex(this, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }
  }.getOrNull()
}

internal fun parseChatMetadata(chatMetadataJson: String?): JsonObject {
  return runCatching {
    JsonParser.parseString(chatMetadataJson ?: "{}").asJsonObject
  }.getOrElse { JsonObject() }
}

internal fun serializeChatMetadata(metadata: JsonObject): String = metadata.toString()

internal fun JsonObject.isTimedEffectActive(
  type: String,
  entry: RuntimeEntry,
  allEntries: List<RuntimeEntry>,
  chatLength: Int,
): Boolean {
  val timedWorldInfo = scbrGetOrCreateObject("timedWorldInfo")
  val bucket = timedWorldInfo.scbrGetOrCreateObject(type)
  val effectKey = entry.timedEffectKey()
  val effect = bucket.getAsJsonObject(effectKey) ?: bucket.getAsJsonObject(entry.stableKey)
  if (effect == null) {
    return false
  }

  val start = effect.scbrIntOrNull("start") ?: 0
  val end = effect.scbrIntOrNull("end") ?: 0
  val protected = effect.scbrBooleanOrNull("protected") ?: false
  val hash = effect.scbrStringOrNull("hash").orEmpty()
  val matchingEntry =
    allEntries.find {
      it.stableKey == hash ||
        it.timedEffectKey() == hash ||
        it.stableKey == entry.stableKey ||
        it.timedEffectKey() == effectKey
    }
  if (chatLength <= start && !protected) {
    bucket.remove(effectKey)
    bucket.remove(entry.stableKey)
    return false
  }
  if (matchingEntry == null) {
    if (chatLength >= end) {
      bucket.remove(effectKey)
      bucket.remove(entry.stableKey)
    }
    return false
  }
  if (chatLength >= end) {
    bucket.remove(effectKey)
    bucket.remove(entry.stableKey)
    if (type == "sticky" && entry.extensions.cooldown != null) {
      val cooldownBucket = timedWorldInfo.scbrGetOrCreateObject("cooldown")
      cooldownBucket.add(
        effectKey,
        JsonObject().apply {
          addProperty("hash", effectKey)
          addProperty("start", chatLength)
          addProperty("end", chatLength + entry.extensions.cooldown)
          addProperty("protected", true)
        },
      )
    }
    return type == "cooldown" && entry.extensions.cooldown != null
  }
  return true
}

internal fun JsonObject.setTimedEffects(entries: List<RuntimeEntry>, chatLength: Int) {
  val timedWorldInfo = scbrGetOrCreateObject("timedWorldInfo")
  val stickyBucket = timedWorldInfo.scbrGetOrCreateObject("sticky")
  val cooldownBucket = timedWorldInfo.scbrGetOrCreateObject("cooldown")
  entries.forEach { entry ->
    val effectKey = entry.timedEffectKey()
    entry.extensions.sticky?.takeIf { it > 0 }?.let { sticky ->
      if (!stickyBucket.has(effectKey) && !stickyBucket.has(entry.stableKey)) {
        stickyBucket.add(
          effectKey,
          JsonObject().apply {
            addProperty("hash", effectKey)
            addProperty("start", chatLength)
            addProperty("end", chatLength + sticky)
            addProperty("protected", false)
          },
        )
      }
    }
    entry.extensions.cooldown?.takeIf { it > 0 }?.let { cooldown ->
      if (!cooldownBucket.has(effectKey) && !cooldownBucket.has(entry.stableKey)) {
        cooldownBucket.add(
          effectKey,
          JsonObject().apply {
            addProperty("hash", effectKey)
            addProperty("start", chatLength)
            addProperty("end", chatLength + cooldown)
            addProperty("protected", false)
          },
        )
      }
    }
  }
}

internal fun JsonObject.scbrGetOrCreateObject(key: String): JsonObject {
  val existing = getAsJsonObject(key)
  if (existing != null) {
    return existing
  }
  return JsonObject().also { add(key, it) }
}

internal fun JsonObject.scbrIntOrNull(key: String): Int? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

internal fun JsonObject.scbrDoubleOrNull(key: String): Double? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()

internal fun JsonObject.scbrBooleanOrNull(key: String): Boolean? {
  val value = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
  return when {
    value.asJsonPrimitive.isBoolean -> value.asBoolean
    value.asJsonPrimitive.isString -> value.asString.toBooleanStrictOrNull()
    else -> null
  }
}

internal fun JsonObject.scbrStringOrNull(key: String): String? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.scbrStringListOrEmpty(key: String): List<String> {
  return getAsJsonArray(key)
    ?.mapNotNull { element ->
      element
        .takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.ifBlank { null }
    }
    .orEmpty()
}

internal fun parseDecorators(content: String): ParsedDecorators {
  val decorators = linkedSetOf<String>()
  val body = mutableListOf<String>()
  var parsingDecorators = true
  content.lineSequence().forEach { line ->
    val trimmed = line.trim()
    if (parsingDecorators && trimmed.startsWith("@@@")) {
      body += line.drop(1)
      parsingDecorators = false
    } else if (parsingDecorators && (trimmed == "@@activate" || trimmed == "@@dont_activate")) {
      decorators += trimmed
    } else {
      body += line
      parsingDecorators = false
    }
  }
  return ParsedDecorators(
    decorators = decorators,
    contentWithoutDecorators = body.joinToString("\n").trim(),
  )
}

internal fun JsonObject?.toCharacterFilter(): StCharacterFilter? {
  if (this == null) {
    return null
  }
  val names =
    getAsJsonArray("names")
      ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null } }
      .orEmpty()
  val tags =
    getAsJsonArray("tags")
      ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null } }
      .orEmpty()
  val isExclude = scbrBooleanOrNull("isExclude") ?: false
  if (names.isEmpty() && tags.isEmpty() && !isExclude) {
    return null
  }
  return StCharacterFilter(names = names, tags = tags, isExclude = isExclude)
}

internal fun RuntimeEntry.isFilteredOut(context: StWorldScanContext): Boolean {
  val filter = characterFilter ?: return false
  if (filter.names.isNotEmpty()) {
    val matched = filter.names.any { it.equals(context.roleName, ignoreCase = true) }
    if (filter.isExclude && matched) {
      return true
    }
    if (!filter.isExclude && !matched) {
      return true
    }
  }
  if (filter.tags.isNotEmpty()) {
    val matched =
      context.roleTags.any { roleTag ->
        filter.tags.any { filterTag -> filterTag.equals(roleTag, ignoreCase = true) }
      }
    if (filter.isExclude && matched) {
      return true
    }
    if (!filter.isExclude && !matched) {
      return true
    }
  }
  return false
}

internal fun RuntimeEntry.timedEffectKey(): String = entry.id?.toString()?.ifBlank { null } ?: stableKey
