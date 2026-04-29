package selfgemma.talk.domain.roleplay.usecase

import kotlin.math.min
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.freshness

/**
 * Pure ranking, scoring, rendering and budget-resolution helpers extracted from
 * [CompileRoleplayMemoryContextUseCase]. All functions are stateless and depend only
 * on their parameters — no instance state of the use case is referenced.
 */

private val MCR_NON_QUERY_CHAR_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val MCR_WHITESPACE_REGEX = Regex("\\s+")

internal data class MemoryPackCategoryBudget(
  val externalFactTokens: Int,
  val openThreadTokens: Int,
  val memoryAtomTokens: Int,
  val fallbackSummaryTokens: Int,
  val fallbackMemoryTokens: Int,
)

internal fun scoreOpenThread(
  thread: OpenThread,
  queryTerms: List<String>,
  entityTerms: List<String>,
  retrievalIntent: RoleplayMemoryRetrievalIntent,
): Float {
  val normalizedContent = normalizeTerm(thread.content)
  val lexicalOverlap = overlapCount(normalizedContent, queryTerms)
  val entityOverlap = overlapCount(normalizedContent, entityTerms)
  val typeWeight =
    when (thread.type) {
      OpenThreadType.PROMISE -> 18f
      OpenThreadType.QUESTION -> 0f
      OpenThreadType.TASK -> 14f
      OpenThreadType.MYSTERY -> 12f
      OpenThreadType.EMOTIONAL -> 10f
    }
  val timeScopeWeight =
    when (retrievalIntent.timeScope) {
      RoleplayMemoryTimeScope.IMMEDIATE -> 6f
      RoleplayMemoryTimeScope.RECENT_RELATED_PAST -> 10f
      RoleplayMemoryTimeScope.LONG_TERM -> 4f
    }
  return thread.priority + typeWeight + lexicalOverlap * 20f + entityOverlap * 14f + timeScopeWeight
}

internal fun scoreMemoryAtom(
  atom: MemoryAtom,
  queryTerms: List<String>,
  entityTerms: List<String>,
  retrievalIntent: RoleplayMemoryRetrievalIntent,
): Float {
  val normalizedSubject = normalizeTerm(atom.subject)
  val normalizedObject = normalizeTerm(atom.objectValue)
  val namespaceWeight =
    when (atom.namespace) {
      selfgemma.talk.domain.roleplay.model.MemoryNamespace.PROMISE -> 20f
      selfgemma.talk.domain.roleplay.model.MemoryNamespace.SEMANTIC -> 16f
      selfgemma.talk.domain.roleplay.model.MemoryNamespace.WORLD -> 15f
      selfgemma.talk.domain.roleplay.model.MemoryNamespace.EPISODIC -> 11f
    }
  val stabilityWeight =
    when (atom.stability) {
      selfgemma.talk.domain.roleplay.model.MemoryStability.LOCKED -> 18f
      selfgemma.talk.domain.roleplay.model.MemoryStability.STABLE -> 14f
      selfgemma.talk.domain.roleplay.model.MemoryStability.CANDIDATE -> 8f
      selfgemma.talk.domain.roleplay.model.MemoryStability.TRANSIENT -> 4f
    }
  val entityOverlap = overlapCount("$normalizedSubject $normalizedObject", entityTerms)
  val lexicalOverlap = overlapCount("$normalizedSubject $normalizedObject", queryTerms)
  val episodicPenalty =
    if (
      atom.namespace == selfgemma.talk.domain.roleplay.model.MemoryNamespace.EPISODIC &&
        retrievalIntent.timeScope == RoleplayMemoryTimeScope.IMMEDIATE
    ) {
      8f
    } else {
      0f
    }
  return namespaceWeight +
    stabilityWeight +
    lexicalOverlap * 12f +
    entityOverlap * 14f +
    atom.salience * 18f +
    atom.confidence * 12f -
    episodicPenalty
}

internal fun scoreLegacyMemory(
  memory: MemoryItem,
  queryTerms: List<String>,
  entityTerms: List<String>,
): Float {
  val normalizedContent = normalizeTerm(memory.content)
  val categoryWeight =
    when (memory.category) {
      selfgemma.talk.domain.roleplay.model.MemoryCategory.TODO -> 18f
      selfgemma.talk.domain.roleplay.model.MemoryCategory.RELATION -> 16f
      selfgemma.talk.domain.roleplay.model.MemoryCategory.PREFERENCE -> 15f
      selfgemma.talk.domain.roleplay.model.MemoryCategory.WORLD -> 13f
      selfgemma.talk.domain.roleplay.model.MemoryCategory.RULE -> 14f
      selfgemma.talk.domain.roleplay.model.MemoryCategory.PLOT -> 10f
    }
  val lexicalOverlap = overlapCount(normalizedContent, queryTerms)
  val entityOverlap = overlapCount(normalizedContent, entityTerms)
  return categoryWeight + lexicalOverlap * 10f + entityOverlap * 12f + memory.confidence * 10f + if (memory.pinned) 16f else 0f
}

internal fun scoreCompactionEntry(
  entry: CompactionCacheEntry,
  queryTerms: List<String>,
  entityTerms: List<String>,
  retrievalIntent: RoleplayMemoryRetrievalIntent,
): Float {
  val normalizedText = normalizeTerm(entry.compactText)
  val lexicalOverlap = overlapCount(normalizedText, queryTerms)
  val entityOverlap = overlapCount(normalizedText, entityTerms)
  val typeWeight =
    when (entry.summaryType) {
      selfgemma.talk.domain.roleplay.model.CompactionSummaryType.SCENE -> 18f
      selfgemma.talk.domain.roleplay.model.CompactionSummaryType.CHAPTER -> 14f
      selfgemma.talk.domain.roleplay.model.CompactionSummaryType.ARC -> 10f
    }
  val timeScopeWeight =
    when (retrievalIntent.timeScope) {
      RoleplayMemoryTimeScope.IMMEDIATE -> 2f
      RoleplayMemoryTimeScope.RECENT_RELATED_PAST -> 10f
      RoleplayMemoryTimeScope.LONG_TERM -> 8f
    }
  return typeWeight + lexicalOverlap * 12f + entityOverlap * 14f + timeScopeWeight
}

internal fun resolveFetchLimit(baseLimit: Int, budgetMode: PromptBudgetMode, maxLimit: Int): Int {
  val multiplier =
    when (budgetMode) {
      PromptBudgetMode.FULL -> 2
      PromptBudgetMode.COMPACT -> 2
      PromptBudgetMode.AGGRESSIVE -> 3
    }
  return (baseLimit.coerceAtLeast(1) * multiplier).coerceAtMost(maxLimit)
}

internal fun resolveMemoryPackTargetTokens(
  contextProfile: ModelContextProfile,
  budgetMode: PromptBudgetMode,
): Int {
  val usableTokens = contextProfile.usableInputTokens
  return when (budgetMode) {
    PromptBudgetMode.FULL -> min((usableTokens * 0.30f).toInt(), 900).coerceAtLeast(220)
    PromptBudgetMode.COMPACT -> min((usableTokens * 0.22f).toInt(), 620).coerceAtLeast(180)
    PromptBudgetMode.AGGRESSIVE -> min((usableTokens * 0.16f).toInt(), 320).coerceAtLeast(120)
  }
}

internal fun resolveCategoryBudget(targetTokens: Int, budgetMode: PromptBudgetMode): MemoryPackCategoryBudget {
  if (targetTokens == Int.MAX_VALUE) {
    return MemoryPackCategoryBudget(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
  }
  return when (budgetMode) {
    PromptBudgetMode.FULL ->
      MemoryPackCategoryBudget(
        externalFactTokens = min((targetTokens * 0.16f).toInt(), 120).coerceAtLeast(48),
        openThreadTokens = min((targetTokens * 0.22f).toInt(), 120).coerceAtLeast(64),
        memoryAtomTokens = min((targetTokens * 0.34f).toInt(), 180).coerceAtLeast(80),
        fallbackSummaryTokens = min((targetTokens * 0.30f).toInt(), 220).coerceAtLeast(100),
        fallbackMemoryTokens = min((targetTokens * 0.18f).toInt(), 120).coerceAtLeast(48),
      )
    PromptBudgetMode.COMPACT ->
      MemoryPackCategoryBudget(
        externalFactTokens = min((targetTokens * 0.14f).toInt(), 84).coerceAtLeast(40),
        openThreadTokens = min((targetTokens * 0.20f).toInt(), 84).coerceAtLeast(48),
        memoryAtomTokens = min((targetTokens * 0.28f).toInt(), 120).coerceAtLeast(64),
        fallbackSummaryTokens = min((targetTokens * 0.26f).toInt(), 140).coerceAtLeast(80),
        fallbackMemoryTokens = min((targetTokens * 0.16f).toInt(), 72).coerceAtLeast(32),
      )
    PromptBudgetMode.AGGRESSIVE ->
      MemoryPackCategoryBudget(
        externalFactTokens = min((targetTokens * 0.12f).toInt(), 56).coerceAtLeast(28),
        openThreadTokens = min((targetTokens * 0.18f).toInt(), 56).coerceAtLeast(32),
        memoryAtomTokens = min((targetTokens * 0.22f).toInt(), 72).coerceAtLeast(40),
        fallbackSummaryTokens = min((targetTokens * 0.24f).toInt(), 96).coerceAtLeast(48),
        fallbackMemoryTokens = min((targetTokens * 0.14f).toInt(), 48).coerceAtLeast(24),
      )
  }
}

internal fun <T> selectItemsWithinBudget(
  rankedItems: List<T>,
  itemLimit: Int,
  tokenBudget: Int,
  guaranteedCount: Int,
  tokenEstimate: (T) -> Int,
): List<T> {
  if (itemLimit <= 0 || rankedItems.isEmpty()) {
    return emptyList()
  }

  val selected = mutableListOf<T>()
  var usedTokens = 0
  rankedItems.take(itemLimit).forEach { item ->
    val estimatedTokens = tokenEstimate(item)
    val shouldKeep =
      when {
        selected.size < guaranteedCount -> true
        selected.isEmpty() -> true
        tokenBudget == Int.MAX_VALUE -> true
        usedTokens + estimatedTokens <= tokenBudget -> true
        else -> false
      }
    if (shouldKeep) {
      selected += item
      usedTokens += estimatedTokens
    }
  }
  return selected
}

internal fun canFitOptionalSection(sectionTokens: Int, tokenBudget: Int): Boolean {
  return tokenBudget == Int.MAX_VALUE || tokenBudget >= sectionTokens || tokenBudget >= (sectionTokens / 2)
}

internal fun consumeRemainingTokens(remainingTokens: Int, consumedTokens: Int): Int {
  return if (remainingTokens == Int.MAX_VALUE) Int.MAX_VALUE else (remainingTokens - consumedTokens).coerceAtLeast(0)
}

internal fun renderOpenThread(thread: OpenThread): String {
  return "[${thread.type.name.lowercase()}/${thread.owner.name.lowercase()}/p${thread.priority}] ${thread.content.normalizeWhitespace()}"
}

internal fun renderMemoryAtom(atom: MemoryAtom): String {
  return "${atom.subject.normalizeWhitespace()} ${atom.predicate.normalizeWhitespace()}: ${atom.objectValue.normalizeWhitespace()}"
}

internal fun renderLegacyMemory(memory: MemoryItem): String {
  return "${memory.category.name.lowercase()}: ${memory.content.normalizeWhitespace()}"
}

internal fun renderExternalFact(fact: RoleplayExternalFact): String {
  val freshness =
    when (fact.freshness()) {
      selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.FRESH -> "fresh"
      selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.STALE -> "stale"
      selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.STABLE -> "stable"
    }
  return "[$freshness/${fact.sourceToolName}] ${fact.title.normalizeWhitespace()}: ${fact.content.normalizeWhitespace()}"
}

internal fun renderRuntimeState(snapshot: RuntimeStateSnapshot?): String {
  if (snapshot == null) {
    return ""
  }

  return listOf(snapshot.sceneJson, snapshot.relationshipJson, snapshot.activeEntitiesJson)
    .joinToString(separator = "\n")
    .normalizeWhitespace()
}

internal fun normalizeTerm(value: String): String {
  return value.lowercase().replace(MCR_NON_QUERY_CHAR_REGEX, " ").replace(MCR_WHITESPACE_REGEX, " ").trim()
}

internal fun overlapCount(text: String, terms: List<String>): Int {
  if (text.isBlank() || terms.isEmpty()) {
    return 0
  }
  return terms.count { term -> term.isNotBlank() && text.contains(term) }
}
