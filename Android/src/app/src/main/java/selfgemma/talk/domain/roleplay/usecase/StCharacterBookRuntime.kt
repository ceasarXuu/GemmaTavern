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
