package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry

internal data class StWorldScanContext(
  val roleName: String,
  val roleTags: List<String>,
  val generationTrigger: String,
  val recentMessagesNewestFirst: List<String>,
  val userPersonaDescription: String,
  val characterDescription: String,
  val characterPersonality: String,
  val characterDepthPrompt: String,
  val scenario: String,
  val creatorNotes: String,
  val sessionSummary: String,
  val memories: List<String>,
)

internal data class StRuntimeDepthPromptInsertion(
  val prompts: List<String>,
  val depth: Int,
  val role: String,
)

internal data class PromptAssemblyResult(
  val prompt: String,
  val updatedChatMetadataJson: String? = null,
  val budgetReport: PromptBudgetReport? = null,
  val sections: List<PlannedPromptSection> = emptyList(),
  val dialogueWindow: List<Message> = emptyList(),
)

internal data class StResolvedPromptRuntime(
  val beforePrompt: String = "",
  val afterPrompt: String = "",
  val authorNoteBefore: List<String> = emptyList(),
  val authorNoteAfter: List<String> = emptyList(),
  val exampleBefore: List<String> = emptyList(),
  val exampleAfter: List<String> = emptyList(),
  val depthPrompts: List<StRuntimeDepthPromptInsertion> = emptyList(),
  val outletEntries: Map<String, List<String>> = emptyMap(),
  val updatedChatMetadataJson: String? = null,
)

internal data class StBookEntryRuntimeExtensions(
  val position: Int? = null,
  val depth: Int? = null,
  val role: Int? = null,
  val selectiveLogic: StSelectiveLogic = StSelectiveLogic.AND_ANY,
  val scanDepth: Int? = null,
  val caseSensitive: Boolean? = null,
  val matchWholeWords: Boolean? = null,
  val matchPersonaDescription: Boolean = false,
  val matchCharacterDescription: Boolean = false,
  val matchCharacterPersonality: Boolean = false,
  val matchCharacterDepthPrompt: Boolean = false,
  val matchScenario: Boolean = false,
  val matchCreatorNotes: Boolean = false,
  val useRegex: Boolean = false,
  val preventRecursion: Boolean = false,
  val excludeRecursion: Boolean = false,
  val delayUntilRecursion: Int = 0,
  val probability: Int = 100,
  val useProbability: Boolean = true,
  val useGroupScoring: Boolean? = null,
  val outletName: String = "",
  val group: String = "",
  val groupOverride: Boolean = false,
  val groupWeight: Int = 100,
  val sticky: Int? = null,
  val cooldown: Int? = null,
  val delay: Int? = null,
  val ignoreBudget: Boolean = false,
  val triggers: List<String> = emptyList(),
)

private data class RuntimeEntry(
  val entry: StCharacterBookEntry,
  val order: Int,
  val extensions: StBookEntryRuntimeExtensions,
  val stableKey: String,
  val decorators: Set<String>,
  val characterFilter: StCharacterFilter?,
  val normalizedContent: String,
)

private data class RuntimeCandidate(
  val entry: RuntimeEntry,
  val score: Int,
  val stickyActive: Boolean,
)

private data class StWorldRuntimeSettings(
  val defaultScanDepth: Int = 2,
  val minActivations: Int = 0,
  val minActivationsDepthMax: Int = 0,
  val maxRecursionSteps: Int = 0,
  val caseSensitive: Boolean = false,
  val matchWholeWords: Boolean = false,
  val useGroupScoring: Boolean = false,
)

private data class StCharacterFilter(
  val names: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val isExclude: Boolean = false,
)

private enum class StScanPhase {
  INITIAL,
  RECURSION,
  MIN_ACTIVATIONS,
}

internal enum class StSelectiveLogic {
  AND_ANY,
  NOT_ALL,
  NOT_ANY,
  AND_ALL,
}

internal enum class StWorldInfoPosition {
  BEFORE,
  AFTER,
  AUTHOR_NOTE_BEFORE,
  AUTHOR_NOTE_AFTER,
  AT_DEPTH,
  EXAMPLE_BEFORE,
  EXAMPLE_AFTER,
  OUTLET,
}

internal class StCharacterBookRuntime(private val tokenEstimator: TokenEstimator) {
  fun resolve(
    book: StCharacterBook?,
    context: StWorldScanContext,
    macroContext: StMacroContext,
    chatMetadataJson: String?,
    chatLength: Int,
  ): StResolvedPromptRuntime {
    if (book == null) {
      return StResolvedPromptRuntime(updatedChatMetadataJson = chatMetadataJson)
    }

    val metadata = parseChatMetadata(chatMetadataJson)
    val runtimeSettings = book.toRuntimeSettings()
    val entries =
      book.entries
        .orEmpty()
        .filter { (it.enabled ?: true) && !it.content.isNullOrBlank() }
        .mapIndexed { index, entry ->
          val parsedContent = parseDecorators(entry.content.orEmpty())
          RuntimeEntry(
            entry = entry,
            order = entry.insertion_order ?: index,
            extensions = entry.toRuntimeExtensions(runtimeSettings),
            stableKey = entry.stableKey(index, parsedContent.contentWithoutDecorators),
            decorators = parsedContent.decorators,
            characterFilter = entry.character_filter.toCharacterFilter(),
            normalizedContent = parsedContent.contentWithoutDecorators,
          )
        }
    if (entries.isEmpty()) {
      return StResolvedPromptRuntime(updatedChatMetadataJson = serializeChatMetadata(metadata))
    }

    val activated = linkedMapOf<String, RuntimeEntry>()
    val recursionBuffer = mutableListOf<String>()
    val availableRecursionDelayLevels =
      entries
        .map { it.extensions.delayUntilRecursion }
        .filter { it > 0 }
        .distinct()
        .sorted()
        .toMutableList()
    var currentDelayLevel = availableRecursionDelayLevels.firstOrNull() ?: 0
    if (availableRecursionDelayLevels.isNotEmpty()) {
      availableRecursionDelayLevels.removeAt(0)
    }
    val budget = book.token_budget?.takeIf { it > 0 } ?: Int.MAX_VALUE
    var budgetOverflowed = false
    var scanPhase = StScanPhase.INITIAL
    var scanDepthSkew = 0

    var loopCount = 0
    while (true) {
      loopCount += 1
      if (runtimeSettings.maxRecursionSteps > 0 && loopCount > runtimeSettings.maxRecursionSteps) {
        break
      }
      val candidates =
        entries.mapNotNull { runtimeEntry ->
          if (activated.containsKey(runtimeEntry.stableKey)) {
            return@mapNotNull null
          }

          if (runtimeEntry.isFilteredOut(context)) {
            return@mapNotNull null
          }

          if (
            runtimeEntry.extensions.triggers.isNotEmpty() &&
              runtimeEntry.extensions.triggers.none { trigger ->
                trigger.equals(context.generationTrigger, ignoreCase = true)
              }
          ) {
            return@mapNotNull null
          }

          val stickyActive = metadata.isTimedEffectActive("sticky", runtimeEntry, entries, chatLength)
          val cooldownActive = metadata.isTimedEffectActive("cooldown", runtimeEntry, entries, chatLength)
          val delayActive = runtimeEntry.extensions.delay?.let { chatLength < it } ?: false

          if (delayActive) {
            return@mapNotNull null
          }
          if (cooldownActive && !stickyActive) {
            return@mapNotNull null
          }
          if (scanPhase != StScanPhase.RECURSION && runtimeEntry.extensions.delayUntilRecursion > 0 && !stickyActive) {
            return@mapNotNull null
          }
          if (scanPhase == StScanPhase.RECURSION && runtimeEntry.extensions.delayUntilRecursion > currentDelayLevel && !stickyActive) {
            return@mapNotNull null
          }
          if (scanPhase == StScanPhase.RECURSION && (book.recursive_scanning == true) && runtimeEntry.extensions.excludeRecursion && !stickyActive) {
            return@mapNotNull null
          }
          if (runtimeEntry.decorators.contains("@@activate")) {
            return@mapNotNull RuntimeCandidate(entry = runtimeEntry, score = Int.MAX_VALUE, stickyActive = stickyActive)
          }
          if (runtimeEntry.decorators.contains("@@dont_activate")) {
            return@mapNotNull null
          }
          if (runtimeEntry.entry.constant == true || stickyActive) {
            return@mapNotNull RuntimeCandidate(
              entry = runtimeEntry,
              score = Int.MAX_VALUE - 1,
              stickyActive = stickyActive,
            )
          }

          val textToScan =
            context.toScanText(
              extensions = runtimeEntry.extensions,
              runtimeSettings = runtimeSettings,
              defaultScanDepth = book.scan_depth,
              scanDepthSkew = scanDepthSkew,
              includeRecursionBuffer = scanPhase != StScanPhase.MIN_ACTIVATIONS,
              recursionBuffer = recursionBuffer,
            )
          val score = runtimeEntry.matchScore(textToScan, macroContext) ?: return@mapNotNull null
          RuntimeCandidate(entry = runtimeEntry, score = score, stickyActive = stickyActive)
        }
      if (candidates.isEmpty()) {
        if (availableRecursionDelayLevels.isNotEmpty()) {
          currentDelayLevel = availableRecursionDelayLevels.removeAt(0)
          scanPhase = StScanPhase.RECURSION
          continue
        }
        val minActivationsNotSatisfied =
          runtimeSettings.minActivations > 0 && activated.size < runtimeSettings.minActivations
        val maxMinActivationDepth =
          when {
            runtimeSettings.minActivationsDepthMax > 0 -> runtimeSettings.minActivationsDepthMax
            else -> context.recentMessagesNewestFirst.size
          }
        val currentScanDepth = (book.scan_depth ?: runtimeSettings.defaultScanDepth) + scanDepthSkew
        if (!budgetOverflowed && minActivationsNotSatisfied && currentScanDepth < maxMinActivationDepth) {
          scanDepthSkew += 1
          scanPhase = StScanPhase.MIN_ACTIVATIONS
          continue
        }
        break
      }

      val grouped = filterGroupedCandidates(candidates, activated, runtimeSettings)
      val newlyActivated = mutableListOf<RuntimeEntry>()
      var currentBudgetUsage =
        activated.values
          .filterNot { it.extensions.ignoreBudget }
          .sumOf { tokenEstimator.estimate(macroContext.substitute(it.entry.content).trim()) }

      grouped.forEach { runtimeEntry ->
        if (!runtimeEntry.passesProbability(metadata, entries, chatLength)) {
          return@forEach
        }

        val renderedContent = macroContext.substitute(runtimeEntry.normalizedContent).trim()
        if (renderedContent.isBlank()) {
          return@forEach
        }
        val contentTokens = tokenEstimator.estimate(renderedContent)
        if (!runtimeEntry.extensions.ignoreBudget && currentBudgetUsage + contentTokens >= budget) {
          budgetOverflowed = true
          return@forEach
        }

        activated[runtimeEntry.stableKey] = runtimeEntry
        newlyActivated += runtimeEntry
        if (!runtimeEntry.extensions.ignoreBudget) {
          currentBudgetUsage += contentTokens
        }
      }

      metadata.setTimedEffects(newlyActivated, chatLength)

      var nextScanPhase: StScanPhase? = null
      if (!budgetOverflowed && book.recursive_scanning == true) {
        val recursionText =
          newlyActivated
            .filterNot { it.extensions.preventRecursion }
            .joinToString("\n") { macroContext.substitute(it.normalizedContent).trim() }
            .trim()
        if (recursionText.isNotBlank()) {
          recursionBuffer += recursionText
          nextScanPhase = StScanPhase.RECURSION
        } else if (availableRecursionDelayLevels.isNotEmpty()) {
          currentDelayLevel = availableRecursionDelayLevels.removeAt(0)
          nextScanPhase = StScanPhase.RECURSION
        }
      } else if (!budgetOverflowed && availableRecursionDelayLevels.isNotEmpty()) {
        currentDelayLevel = availableRecursionDelayLevels.removeAt(0)
        nextScanPhase = StScanPhase.RECURSION
      }

      if (
        nextScanPhase == null &&
          !budgetOverflowed &&
          runtimeSettings.minActivations > 0 &&
          activated.size < runtimeSettings.minActivations
      ) {
        val maxMinActivationDepth =
          when {
            runtimeSettings.minActivationsDepthMax > 0 -> runtimeSettings.minActivationsDepthMax
            else -> context.recentMessagesNewestFirst.size
          }
        val currentScanDepth = (book.scan_depth ?: runtimeSettings.defaultScanDepth) + scanDepthSkew
        if (currentScanDepth < maxMinActivationDepth) {
          scanDepthSkew += 1
          nextScanPhase = StScanPhase.MIN_ACTIVATIONS
        }
      }

      if (
        nextScanPhase == null &&
          !budgetOverflowed &&
          book.recursive_scanning == true &&
          scanPhase == StScanPhase.MIN_ACTIVATIONS &&
          recursionBuffer.isNotEmpty()
      ) {
        nextScanPhase = StScanPhase.RECURSION
      }

      if (nextScanPhase == null) {
        break
      }
      scanPhase = nextScanPhase
    }

    val beforePrompt = mutableListOf<String>()
    val afterPrompt = mutableListOf<String>()
    val authorNoteBefore = mutableListOf<String>()
    val authorNoteAfter = mutableListOf<String>()
    val exampleBefore = mutableListOf<String>()
    val exampleAfter = mutableListOf<String>()
    val depthPrompts = linkedMapOf<Pair<Int, String>, MutableList<String>>()
    val outletEntries = linkedMapOf<String, MutableList<String>>()

    activated.values.sortedBy { it.order }.forEach { runtimeEntry ->
      val content = macroContext.substitute(runtimeEntry.normalizedContent).trim()
      if (content.isBlank()) {
        return@forEach
      }
      when (runtimeEntry.resolvePromptPosition()) {
        StWorldInfoPosition.BEFORE -> beforePrompt += content
        StWorldInfoPosition.AFTER -> afterPrompt += content
        StWorldInfoPosition.AUTHOR_NOTE_BEFORE -> authorNoteBefore += content
        StWorldInfoPosition.AUTHOR_NOTE_AFTER -> authorNoteAfter += content
        StWorldInfoPosition.EXAMPLE_BEFORE -> exampleBefore += content
        StWorldInfoPosition.EXAMPLE_AFTER -> exampleAfter += content
        StWorldInfoPosition.AT_DEPTH ->
          depthPrompts
            .getOrPut(
              (runtimeEntry.extensions.depth ?: 4) to runtimeEntry.extensions.role.toPromptRoleName()
            ) { mutableListOf() }
            .add(content)
        StWorldInfoPosition.OUTLET -> {
          val outletName = runtimeEntry.extensions.outletName.trim()
          if (outletName.isBlank()) {
            return@forEach
          }
          outletEntries.getOrPut(outletName) { mutableListOf() }.add(content)
        }
      }
    }

    return StResolvedPromptRuntime(
      beforePrompt = beforePrompt.joinToString("\n").trim(),
      afterPrompt = afterPrompt.joinToString("\n").trim(),
      authorNoteBefore = authorNoteBefore,
      authorNoteAfter = authorNoteAfter,
      exampleBefore = exampleBefore,
      exampleAfter = exampleAfter,
      depthPrompts =
        depthPrompts.entries
          .map { (key, prompts) ->
            StRuntimeDepthPromptInsertion(
              prompts = prompts.toList(),
              depth = key.first,
              role = key.second,
            )
          }
          .sortedWith(compareBy<StRuntimeDepthPromptInsertion> { it.depth }.thenBy { it.role }),
      outletEntries = outletEntries,
      updatedChatMetadataJson = serializeChatMetadata(metadata),
    )
  }

  private fun RuntimeEntry.matchScore(textToScan: String, macroContext: StMacroContext): Int? {
    val primaryMatches =
      entry.keys
      .orEmpty()
      .filter(String::isNotBlank)
      .count { key ->
        textToScan.matchesKeyword(
          keyword = macroContext.substitute(key).trim(),
          extensions = extensions,
        )
      }
    if (primaryMatches == 0) {
      return null
    }
    val keys = entry.secondary_keys.orEmpty().filter(String::isNotBlank)
    if (entry.selective != true || keys.isEmpty()) {
      return primaryMatches
    }
    val matches =
      keys.map { key ->
        textToScan.matchesKeyword(
          keyword = macroContext.substitute(key).trim(),
          extensions = extensions,
          )
        }
    val secondaryMatches = matches.count { it }
    return when (extensions.selectiveLogic) {
      StSelectiveLogic.AND_ANY -> secondaryMatches.takeIf { it > 0 }?.let { primaryMatches + it }
      StSelectiveLogic.NOT_ALL -> (!matches.all { it }).takeIf { it }?.let { primaryMatches }
      StSelectiveLogic.NOT_ANY -> matches.none { it }.takeIf { it }?.let { primaryMatches }
      StSelectiveLogic.AND_ALL -> matches.all { it }.takeIf { it }?.let { primaryMatches + secondaryMatches }
    }
  }

  private fun RuntimeEntry.passesProbability(
    metadata: JsonObject,
    allEntries: List<RuntimeEntry>,
    chatLength: Int,
  ): Boolean {
    if (!extensions.useProbability || extensions.probability >= 100) {
      return true
    }
    if (metadata.isTimedEffectActive("sticky", this, allEntries, chatLength)) {
      return true
    }
    return Random.nextInt(100) < extensions.probability
  }

  private fun filterGroupedCandidates(
    candidates: List<RuntimeCandidate>,
    activated: Map<String, RuntimeEntry>,
    runtimeSettings: StWorldRuntimeSettings,
  ): List<RuntimeEntry> {
    if (candidates.none { it.entry.extensions.group.isNotBlank() }) {
      return candidates
        .sortedWith(compareByDescending<RuntimeCandidate> { it.stickyActive }.thenBy { it.entry.order })
        .map { it.entry }
    }

    val kept = candidates.toMutableList()
    val grouped =
      linkedMapOf<String, MutableList<RuntimeCandidate>>().apply {
        candidates
          .filter { it.entry.extensions.group.isNotBlank() }
          .forEach { runtimeCandidate ->
            runtimeCandidate.entry.extensions.group
              .split(',')
              .map(String::trim)
              .filter(String::isNotBlank)
              .forEach { groupName ->
                getOrPut(groupName) { mutableListOf() }.add(runtimeCandidate)
              }
          }
      }
    grouped.forEach { (groupName, groupEntries) ->
      if (groupEntries.isEmpty()) {
        return@forEach
      }
      if (activated.values.any { it.extensions.group.split(',').map(String::trim).contains(groupName) }) {
        kept.removeAll(groupEntries)
        return@forEach
      }
      val stickyEntries = groupEntries.filter { it.stickyActive }
      if (stickyEntries.isNotEmpty()) {
        kept.removeAll(groupEntries.filterNot { it in stickyEntries })
        return@forEach
      }
      val scoreFiltered = filterGroupByScore(groupEntries, runtimeSettings)
      val overrides = groupEntries.filter { it.entry.extensions.groupOverride }.sortedBy { it.entry.order }
      val winner =
        when {
          overrides.isNotEmpty() -> overrides.first()
          else -> weightedPick(scoreFiltered)
        }
      kept.removeAll(groupEntries.filterNot { it == winner })
    }
    return kept
      .sortedWith(compareByDescending<RuntimeCandidate> { it.stickyActive }.thenBy { it.entry.order })
      .map { it.entry }
  }

  private fun filterGroupByScore(
    entries: List<RuntimeCandidate>,
    runtimeSettings: StWorldRuntimeSettings,
  ): List<RuntimeCandidate> {
    val shouldScore = runtimeSettings.useGroupScoring || entries.any { it.entry.extensions.useGroupScoring == true }
    if (!shouldScore) {
      return entries
    }
    val maxScore = entries.maxOfOrNull { it.score } ?: return entries
    return entries.filter { entry ->
      val scored = entry.entry.extensions.useGroupScoring ?: runtimeSettings.useGroupScoring
      !scored || entry.score == maxScore
    }
  }

  private fun weightedPick(entries: List<RuntimeCandidate>): RuntimeCandidate {
    val total = entries.sumOf { it.entry.extensions.groupWeight.coerceAtLeast(1) }
    var roll = Random.nextInt(total.coerceAtLeast(1))
    entries.forEach { entry ->
      roll -= entry.entry.extensions.groupWeight.coerceAtLeast(1)
      if (roll < 0) {
        return entry
      }
    }
    return entries.first()
  }
}

private fun StCharacterBookEntry.stableKey(index: Int): String {
  return stableKey(index, content.orEmpty())
}

private fun StCharacterBookEntry.stableKey(index: Int, normalizedContent: String): String {
  val base = buildString {
    append(id ?: index)
    append(':')
    append(comment.orEmpty())
    append(':')
    append(normalizedContent)
  }
  return UUID.nameUUIDFromBytes(base.toByteArray()).toString()
}

private fun StCharacterBookEntry.toRuntimeExtensions(
  runtimeSettings: StWorldRuntimeSettings,
): StBookEntryRuntimeExtensions {
  val extensions = extensions ?: JsonObject()
  return StBookEntryRuntimeExtensions(
    position = extensions.intOrNull("position"),
    depth = extensions.intOrNull("depth"),
    role = extensions.intOrNull("role"),
    selectiveLogic =
      when (extensions.intOrNull("selectiveLogic")) {
        1 -> StSelectiveLogic.NOT_ALL
        2 -> StSelectiveLogic.NOT_ANY
        3 -> StSelectiveLogic.AND_ALL
        else -> StSelectiveLogic.AND_ANY
      },
    scanDepth = extensions.intOrNull("scan_depth"),
    caseSensitive = extensions.booleanOrNull("case_sensitive") ?: runtimeSettings.caseSensitive,
    matchWholeWords = extensions.booleanOrNull("match_whole_words") ?: runtimeSettings.matchWholeWords,
    matchPersonaDescription = extensions.booleanOrNull("match_persona_description") ?: false,
    matchCharacterDescription = extensions.booleanOrNull("match_character_description") ?: false,
    matchCharacterPersonality = extensions.booleanOrNull("match_character_personality") ?: false,
    matchCharacterDepthPrompt = extensions.booleanOrNull("match_character_depth_prompt") ?: false,
    matchScenario = extensions.booleanOrNull("match_scenario") ?: false,
    matchCreatorNotes = extensions.booleanOrNull("match_creator_notes") ?: false,
    useRegex = use_regex ?: false,
    preventRecursion = extensions.booleanOrNull("prevent_recursion") ?: false,
    excludeRecursion = extensions.booleanOrNull("exclude_recursion") ?: false,
    delayUntilRecursion = extensions.intOrNull("delay_until_recursion") ?: 0,
    probability = (extensions.doubleOrNull("probability") ?: 100.0).roundToInt().coerceIn(0, 100),
    useProbability = extensions.booleanOrNull("useProbability") ?: true,
    useGroupScoring = extensions.booleanOrNull("use_group_scoring") ?: runtimeSettings.useGroupScoring,
    outletName = extensions.stringOrNull("outlet_name").orEmpty(),
    group = extensions.stringOrNull("group").orEmpty(),
    groupOverride = extensions.booleanOrNull("group_override") ?: false,
    groupWeight = extensions.intOrNull("group_weight") ?: 100,
    sticky = extensions.intOrNull("sticky"),
    cooldown = extensions.intOrNull("cooldown"),
    delay = extensions.intOrNull("delay"),
    ignoreBudget = extensions.booleanOrNull("ignore_budget") ?: false,
    triggers = extensions.stringListOrEmpty("triggers"),
  )
}

private fun StCharacterBook.toRuntimeSettings(): StWorldRuntimeSettings {
  val runtimeExtensions = extensions ?: JsonObject()
  return StWorldRuntimeSettings(
    defaultScanDepth = scan_depth ?: 2,
    minActivations =
      runtimeExtensions.intOrNull("min_activations")
        ?: runtimeExtensions.intOrNull("world_info_min_activations")
        ?: 0,
    minActivationsDepthMax =
      runtimeExtensions.intOrNull("min_activations_depth_max")
        ?: runtimeExtensions.intOrNull("world_info_min_activations_depth_max")
        ?: 0,
    maxRecursionSteps =
      runtimeExtensions.intOrNull("max_recursion_steps")
        ?: runtimeExtensions.intOrNull("world_info_max_recursion_steps")
        ?: 0,
    caseSensitive = runtimeExtensions.booleanOrNull("case_sensitive") ?: false,
    matchWholeWords = runtimeExtensions.booleanOrNull("match_whole_words") ?: false,
    useGroupScoring = runtimeExtensions.booleanOrNull("use_group_scoring") ?: false,
  )
}

private fun RuntimeEntry.resolvePromptPosition(): StWorldInfoPosition {
  return extensions.position?.toWorldInfoPosition()
    ?: if (entry.position.equals("before_char", ignoreCase = true)) {
      StWorldInfoPosition.BEFORE
    } else {
      StWorldInfoPosition.AFTER
    }
}

private fun StWorldScanContext.toScanText(
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

private fun String.matchesKeyword(keyword: String, extensions: StBookEntryRuntimeExtensions): Boolean {
  if (isBlank() || keyword.isBlank()) {
    return false
  }
  if (extensions.useRegex || keyword.isRegexPattern()) {
    val regex = keyword.toRegexOrNull(caseSensitive = extensions.caseSensitive ?: false) ?: return false
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

private fun String.isRegexPattern(): Boolean = startsWith("/") && lastIndexOf('/') > 0

private fun String.toRegexOrNull(caseSensitive: Boolean): Regex? {
  return runCatching {
    if (isRegexPattern()) {
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

private fun parseChatMetadata(chatMetadataJson: String?): JsonObject {
  return runCatching {
    JsonParser.parseString(chatMetadataJson ?: "{}").asJsonObject
  }.getOrElse { JsonObject() }
}

private fun serializeChatMetadata(metadata: JsonObject): String = metadata.toString()

private fun JsonObject.isTimedEffectActive(
  type: String,
  entry: RuntimeEntry,
  allEntries: List<RuntimeEntry>,
  chatLength: Int,
): Boolean {
  val timedWorldInfo = getOrCreateObject("timedWorldInfo")
  val bucket = timedWorldInfo.getOrCreateObject(type)
  val effectKey = entry.timedEffectKey()
  val effect = bucket.getAsJsonObject(effectKey) ?: bucket.getAsJsonObject(entry.stableKey)
  if (effect == null) {
    return false
  }

  val start = effect.intOrNull("start") ?: 0
  val end = effect.intOrNull("end") ?: 0
  val protected = effect.booleanOrNull("protected") ?: false
  val hash = effect.stringOrNull("hash").orEmpty()
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
      val cooldownBucket = timedWorldInfo.getOrCreateObject("cooldown")
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

private fun JsonObject.setTimedEffects(entries: List<RuntimeEntry>, chatLength: Int) {
  val timedWorldInfo = getOrCreateObject("timedWorldInfo")
  val stickyBucket = timedWorldInfo.getOrCreateObject("sticky")
  val cooldownBucket = timedWorldInfo.getOrCreateObject("cooldown")
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

private fun JsonObject.getOrCreateObject(key: String): JsonObject {
  val existing = getAsJsonObject(key)
  if (existing != null) {
    return existing
  }
  return JsonObject().also { add(key, it) }
}

private fun JsonObject.intOrNull(key: String): Int? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()

private fun JsonObject.doubleOrNull(key: String): Double? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()

private fun JsonObject.booleanOrNull(key: String): Boolean? {
  val value = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
  return when {
    value.asJsonPrimitive.isBoolean -> value.asBoolean
    value.asJsonPrimitive.isString -> value.asString.toBooleanStrictOrNull()
    else -> null
  }
}

private fun JsonObject.stringOrNull(key: String): String? =
  get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.stringListOrEmpty(key: String): List<String> {
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

private fun Int?.toPromptRoleName(): String {
  return when (this) {
    1 -> "user"
    2 -> "assistant"
    else -> "system"
  }
}

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

private data class ParsedDecorators(
  val decorators: Set<String>,
  val contentWithoutDecorators: String,
)

private fun parseDecorators(content: String): ParsedDecorators {
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

private fun JsonObject?.toCharacterFilter(): StCharacterFilter? {
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
  val isExclude = booleanOrNull("isExclude") ?: false
  if (names.isEmpty() && tags.isEmpty() && !isExclude) {
    return null
  }
  return StCharacterFilter(names = names, tags = tags, isExclude = isExclude)
}

private fun RuntimeEntry.isFilteredOut(context: StWorldScanContext): Boolean {
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

private fun RuntimeEntry.timedEffectKey(): String = entry.id?.toString()?.ifBlank { null } ?: stableKey
