package selfgemma.talk.domain.roleplay.usecase

import kotlin.math.min
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session

/**
 * Planning, query construction and entity extraction helpers extracted from
 * [CompileRoleplayMemoryContextUseCase]. All helpers are pure and depend only on parameters.
 */

private const val MCP_MAX_MEMORY_QUERY_TERMS = 12

private val MCP_NON_QUERY_CHAR_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val MCP_TITLE_CASE_ENTITY_REGEX = Regex("\\b[A-Z][A-Za-z0-9'\\-]{2,}\\b")
private val MCP_QUOTED_ENTITY_REGEX = Regex("[\"\u201C\u201D'\u2018\u2019]([^\"\u201C\u201D'\u2018\u2019]{2,40})[\"\u201C\u201D'\u2018\u2019]")
private val MCP_WHITESPACE_REGEX_PLANNER = Regex("\\s+")

private val MCP_THREAD_TRIGGER_PATTERNS =
  listOf("still", "remember", "promise", "plan", "need to", "have to", "what happened", "why")
private val MCP_HIGH_RECALL_PATTERNS =
  listOf("remember", "again", "last time", "before", "promised", "who", "where", "when")
private val MCP_LONG_TERM_TRIGGER_PATTERNS =
  listOf("always", "usually", "prefer", "favorite", "used to", "normally")
private val MCP_QUERY_STOP_WORDS =
  setOf(
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "have",
    "from",
    "were",
    "your",
    "about",
    "would",
    "could",
    "should",
    "just",
  )

internal fun planMemoryRetrieval(
  session: Session,
  role: RoleCard,
  recentMessages: List<Message>,
  pendingUserInput: String,
  budgetMode: PromptBudgetMode,
): RoleplayMemoryRetrievalIntent {
  val normalizedInput = pendingUserInput.normalizeWhitespace()
  val recentUserText = buildRecentUserContext(recentMessages)
  val query = buildPlannerQuery(normalizedInput, recentUserText)
  val entities = extractPlannerEntities(pendingUserInput = pendingUserInput, recentMessages = recentMessages, query = query)
  val includeOpenThreads = normalizedInput.containsAnyPattern(MCP_THREAD_TRIGGER_PATTERNS)
  val includeSemanticRecall = role.memoryEnabled && query.isNotBlank()
  val memoryAtomLimit =
    min(
      role.memoryMaxItems.coerceIn(1, 4),
      when {
        budgetMode == PromptBudgetMode.AGGRESSIVE ->
          if (normalizedInput.containsAnyPattern(MCP_HIGH_RECALL_PATTERNS)) 2 else 1
        budgetMode == PromptBudgetMode.COMPACT -> 2
        normalizedInput.containsAnyPattern(MCP_HIGH_RECALL_PATTERNS) -> 4
        normalizedInput.isBlank() -> 2
        else -> 3
      },
    )
  val fallbackMemoryLimit =
    min(
      role.memoryMaxItems.coerceIn(1, 3),
      when (budgetMode) {
        PromptBudgetMode.FULL -> 2
        PromptBudgetMode.COMPACT -> 1
        PromptBudgetMode.AGGRESSIVE -> 1
      },
    )
  val reason =
    when {
      session.turnCount <= 2 -> "bootstrap"
      normalizedInput.contains("?") -> "user_question"
      normalizedInput.containsAnyPattern(MCP_HIGH_RECALL_PATTERNS) -> "explicit_recall"
      includeOpenThreads -> "continuity_check"
      else -> "default_sparse"
    }
  val timeScope =
    when {
      normalizedInput.isBlank() -> RoleplayMemoryTimeScope.IMMEDIATE
      normalizedInput.containsAnyPattern(MCP_LONG_TERM_TRIGGER_PATTERNS) -> RoleplayMemoryTimeScope.LONG_TERM
      normalizedInput.containsAnyPattern(MCP_HIGH_RECALL_PATTERNS) || includeOpenThreads || normalizedInput.contains("?") ->
        RoleplayMemoryTimeScope.RECENT_RELATED_PAST
      else -> RoleplayMemoryTimeScope.IMMEDIATE
    }
  val needs =
    buildList {
      add(RoleplayMemoryNeed.SCENE_STATE)
      add(RoleplayMemoryNeed.RELATIONSHIP_STATE)
      if (includeOpenThreads) {
        add(RoleplayMemoryNeed.OPEN_THREADS)
      }
      if (includeSemanticRecall) {
        add(RoleplayMemoryNeed.SEMANTIC_FACTS)
      }
      if (normalizedInput.containsAnyPattern(MCP_HIGH_RECALL_PATTERNS) || includeOpenThreads || session.turnCount >= 10) {
        add(RoleplayMemoryNeed.EPISODIC_EVENTS)
      }
    }

  return RoleplayMemoryRetrievalIntent(
    query = query,
    needs = needs.distinct(),
    entities = entities,
    timeScope = timeScope,
    fallbackVerbatim = normalizedInput.contains("?") || session.turnCount <= 4,
    includeRuntimeState = true,
    includeOpenThreads = includeOpenThreads,
    includeSemanticRecall = includeSemanticRecall,
    openThreadLimit =
      if (includeOpenThreads) {
        when (budgetMode) {
          PromptBudgetMode.FULL -> 3
          PromptBudgetMode.COMPACT -> 2
          PromptBudgetMode.AGGRESSIVE -> 1
        }
      } else {
        0
      },
    memoryAtomLimit = memoryAtomLimit,
    fallbackMemoryLimit = fallbackMemoryLimit,
    reason = reason,
  )
}

internal fun buildPlannerQuery(pendingUserInput: String, recentText: String): String {
  val raw = listOf(pendingUserInput, recentText).joinToString(separator = " ").normalizeWhitespace()
  if (raw.isBlank()) {
    return ""
  }

  val keywords =
    raw
      .lowercase()
      .replace(MCP_NON_QUERY_CHAR_REGEX, " ")
      .split(MCP_WHITESPACE_REGEX_PLANNER)
      .filter { term -> term.length >= 3 && term !in MCP_QUERY_STOP_WORDS }
      .distinct()
      .take(MCP_MAX_MEMORY_QUERY_TERMS)

  return if (keywords.isNotEmpty()) keywords.joinToString(separator = " ") else raw.take(120)
}

internal fun extractPlannerEntities(
  pendingUserInput: String,
  recentMessages: List<Message>,
  query: String,
): List<String> {
  val raw =
    buildString {
      appendLine(pendingUserInput)
      recentMessages
        .asReversed()
        .filter { message -> message.kind == MessageKind.TEXT && message.side == MessageSide.USER }
        .take(3)
        .forEach { appendLine(it.content) }
    }
  val quotedEntities =
    MCP_QUOTED_ENTITY_REGEX
      .findAll(raw)
      .map { match -> match.groupValues[1].normalizeWhitespace() }
      .filter { it.length >= 2 }
      .toList()
  val namedEntities =
    MCP_TITLE_CASE_ENTITY_REGEX
      .findAll(raw)
      .map { it.value.normalizeWhitespace() }
      .filter { it.length >= 3 }
      .toList()
  val queryEntities =
    query
      .split(MCP_WHITESPACE_REGEX_PLANNER)
      .map { it.normalizeWhitespace() }
      .filter { it.length >= 4 }
      .take(3)
  return (quotedEntities + namedEntities + queryEntities)
    .distinctBy(String::lowercase)
    .take(4)
}

internal fun buildRecentUserContext(recentMessages: List<Message>): String {
  return recentMessages
    .asReversed()
    .filter { message -> message.kind == MessageKind.TEXT && message.side == MessageSide.USER }
    .take(3)
    .joinToString(separator = " ") { message -> message.content.normalizeWhitespace() }
}

internal fun String.containsAnyPattern(patterns: List<String>): Boolean {
  return patterns.any { contains(it) }
}
