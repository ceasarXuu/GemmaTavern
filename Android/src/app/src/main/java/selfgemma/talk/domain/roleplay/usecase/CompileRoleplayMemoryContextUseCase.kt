package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.inject.Inject
import kotlin.math.min
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.freshness
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.ExternalFactRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

private const val MEMORY_CONTEXT_TAG = "RoleplayMemoryContext"
private const val MAX_MEMORY_QUERY_TERMS = 12

enum class RoleplayMemoryNeed {
  SCENE_STATE,
  RELATIONSHIP_STATE,
  OPEN_THREADS,
  SEMANTIC_FACTS,
  EPISODIC_EVENTS,
}

enum class RoleplayMemoryTimeScope {
  IMMEDIATE,
  RECENT_RELATED_PAST,
  LONG_TERM,
}

data class RoleplayMemoryPackBudgetReport(
  val targetTokens: Int,
  val estimatedTokens: Int,
  val mode: PromptBudgetMode,
  val externalFactTokens: Int,
  val runtimeStateTokens: Int,
  val openThreadTokens: Int,
  val memoryAtomTokens: Int,
  val fallbackSummaryTokens: Int,
  val fallbackMemoryTokens: Int,
  val droppedExternalFactCount: Int,
  val droppedOpenThreadCount: Int,
  val droppedMemoryAtomCount: Int,
  val droppedFallbackMemoryCount: Int,
  val droppedFallbackSummary: Boolean,
)

data class RoleplayMemoryRetrievalIntent(
  val query: String,
  val needs: List<RoleplayMemoryNeed>,
  val entities: List<String>,
  val timeScope: RoleplayMemoryTimeScope,
  val fallbackVerbatim: Boolean,
  val includeRuntimeState: Boolean,
  val includeOpenThreads: Boolean,
  val includeSemanticRecall: Boolean,
  val openThreadLimit: Int,
  val memoryAtomLimit: Int,
  val fallbackMemoryLimit: Int,
  val reason: String,
)

data class RoleplayMemoryContextPack(
  val retrievalIntent: RoleplayMemoryRetrievalIntent,
  val externalFacts: List<RoleplayExternalFact>,
  val runtimeState: RuntimeStateSnapshot?,
  val openThreads: List<OpenThread>,
  val memoryAtoms: List<MemoryAtom>,
  val compactionEntries: List<CompactionCacheEntry>,
  val fallbackSummary: SessionSummary?,
  val fallbackMemories: List<MemoryItem>,
  val budgetReport: RoleplayMemoryPackBudgetReport? = null,
)

class CompileRoleplayMemoryContextUseCase @Inject constructor(
  private val conversationRepository: ConversationRepository,
  private val externalFactRepository: ExternalFactRepository,
  private val runtimeStateRepository: RuntimeStateRepository,
  private val openThreadRepository: OpenThreadRepository,
  private val memoryAtomRepository: MemoryAtomRepository,
  private val memoryRepository: MemoryRepository,
  private val compactionCacheRepository: CompactionCacheRepository,
  private val tokenEstimator: TokenEstimator,
) {
  suspend operator fun invoke(
    session: Session,
    role: RoleCard,
    recentMessages: List<Message>,
    pendingUserInput: String,
    contextProfile: ModelContextProfile? = null,
    budgetMode: PromptBudgetMode = PromptBudgetMode.FULL,
  ): RoleplayMemoryContextPack {
    val retrievalIntent =
      plan(
        session = session,
        role = role,
        recentMessages = recentMessages,
        pendingUserInput = pendingUserInput,
        budgetMode = budgetMode,
      )
    appendEvent(
      sessionId = session.id,
      eventType = SessionEventType.MEMORY_PLANNER_TRIGGERED,
      payload =
        JsonObject().apply {
          addProperty("query", retrievalIntent.query)
          add("needs", retrievalIntent.needs.toDebugJsonArray())
          add("entities", retrievalIntent.entities.toStringJsonArray())
          addProperty("timeScope", retrievalIntent.timeScope.name)
          addProperty("fallbackVerbatim", retrievalIntent.fallbackVerbatim)
          addProperty("includeRuntimeState", retrievalIntent.includeRuntimeState)
          addProperty("includeOpenThreads", retrievalIntent.includeOpenThreads)
          addProperty("includeSemanticRecall", retrievalIntent.includeSemanticRecall)
          addProperty("openThreadLimit", retrievalIntent.openThreadLimit)
          addProperty("memoryAtomLimit", retrievalIntent.memoryAtomLimit)
          addProperty("fallbackMemoryLimit", retrievalIntent.fallbackMemoryLimit)
          addProperty("reason", retrievalIntent.reason)
        },
    )

    val runtimeState =
      if (retrievalIntent.includeRuntimeState) {
        runtimeStateRepository.getLatestSnapshot(session.id)
      } else {
        null
      }
    val externalFacts = externalFactRepository.listRecentBySession(sessionId = session.id, limit = 4)
    val openThreads =
      if (retrievalIntent.includeOpenThreads) {
        rankOpenThreads(
          openThreadRepository
            .listByStatus(session.id, OpenThreadStatus.OPEN)
            .filterNot { thread -> thread.type == OpenThreadType.QUESTION },
          retrievalIntent = retrievalIntent,
        ).take(resolveFetchLimit(retrievalIntent.openThreadLimit, budgetMode, maxLimit = 8))
      } else {
        emptyList()
      }
    val memoryAtoms =
      if (role.memoryEnabled && retrievalIntent.includeSemanticRecall && retrievalIntent.query.isNotBlank()) {
        rankMemoryAtoms(
          memoryAtomRepository.searchRelevant(
            sessionId = session.id,
            roleId = role.id,
            query = retrievalIntent.query,
            limit = resolveFetchLimit(retrievalIntent.memoryAtomLimit, budgetMode, maxLimit = 12),
          ),
          retrievalIntent = retrievalIntent,
        )
      } else {
        emptyList()
      }
    val compactionEntries =
      if (shouldIncludeCompactionEntries(session = session, retrievalIntent = retrievalIntent)) {
        rankCompactionEntries(
          compactionCacheRepository.listBySession(session.id),
          retrievalIntent = retrievalIntent,
        ).take(resolveFetchLimit(baseLimit = 2, budgetMode = budgetMode, maxLimit = 3))
      } else {
        emptyList()
      }
    val baseSummary =
      conversationRepository.getSummary(session.id)?.takeIf {
        runtimeState == null || openThreads.isEmpty() || memoryAtoms.isEmpty() || compactionEntries.isNotEmpty()
      }
    val fallbackSummary = mergeSummaryWithCompactions(session.id, baseSummary, compactionEntries)
    val fallbackMemories =
      if (role.memoryEnabled && retrievalIntent.query.isNotBlank() && memoryAtoms.isEmpty()) {
        rankLegacyMemories(
          memoryRepository.searchRelevant(
            roleId = role.id,
            sessionId = session.id,
            query = retrievalIntent.query,
            limit = resolveFetchLimit(retrievalIntent.fallbackMemoryLimit, budgetMode, maxLimit = 8),
          ),
          retrievalIntent = retrievalIntent,
        )
      } else {
        emptyList()
      }

    appendEvent(
      sessionId = session.id,
      eventType = SessionEventType.MEMORY_QUERY_EXECUTED,
      payload =
        JsonObject().apply {
          addProperty("query", retrievalIntent.query)
          addProperty("reason", retrievalIntent.reason)
          add("needs", retrievalIntent.needs.toDebugJsonArray())
          add("entities", retrievalIntent.entities.toStringJsonArray())
          addProperty("timeScope", retrievalIntent.timeScope.name)
          addProperty("fallbackVerbatim", retrievalIntent.fallbackVerbatim)
          addProperty("externalFactCount", externalFacts.size)
          addProperty("runtimeStateHit", runtimeState != null)
          addProperty("openThreadCount", openThreads.size)
          addProperty("memoryAtomCount", memoryAtoms.size)
          addProperty("compactionCount", compactionEntries.size)
          addProperty("fallbackMemoryCount", fallbackMemories.size)
          add("openThreadMatches", openThreads.toOpenThreadDebugJsonArray())
          add("externalFacts", externalFacts.toExternalFactDebugJsonArray())
          add("memoryAtomMatches", memoryAtoms.toMemoryAtomDebugJsonArray())
          add("compactionMatches", compactionEntries.toCompactionDebugJsonArray())
          add("fallbackMemoryMatches", fallbackMemories.toLegacyMemoryDebugJsonArray())
        },
    )

    val pack =
      applyPackBudget(
        retrievalIntent = retrievalIntent,
        externalFacts = externalFacts,
        runtimeState = runtimeState,
        openThreads = openThreads,
        memoryAtoms = memoryAtoms,
        compactionEntries = compactionEntries,
        fallbackSummary = fallbackSummary,
        fallbackMemories = fallbackMemories,
        contextProfile = contextProfile,
        budgetMode = budgetMode,
      )

    if (pack.memoryAtoms.isNotEmpty()) {
      memoryAtomRepository.markUsed(pack.memoryAtoms.map { it.id }, System.currentTimeMillis())
    }
    if (pack.fallbackMemories.isNotEmpty()) {
      memoryRepository.markUsed(pack.fallbackMemories.map { it.id }, System.currentTimeMillis())
    }

    val externalFactTokens = tokenEstimator.estimate(pack.externalFacts.joinToString("\n") { renderExternalFact(it) })
    val runtimeStateTokens = tokenEstimator.estimate(renderRuntimeState(pack.runtimeState))
    val openThreadTokens = tokenEstimator.estimate(pack.openThreads.joinToString("\n") { renderOpenThread(it) })
    val memoryAtomTokens = tokenEstimator.estimate(pack.memoryAtoms.joinToString("\n") { renderMemoryAtom(it) })
    val fallbackSummaryTokens = tokenEstimator.estimate(pack.fallbackSummary?.summaryText.orEmpty())
    val fallbackMemoryTokens = tokenEstimator.estimate(pack.fallbackMemories.joinToString("\n") { renderLegacyMemory(it) })
    appendEvent(
      sessionId = session.id,
      eventType = SessionEventType.MEMORY_PACK_COMPILED,
      payload =
        JsonObject().apply {
          addProperty("query", retrievalIntent.query)
          addProperty("reason", retrievalIntent.reason)
          add("needs", retrievalIntent.needs.toDebugJsonArray())
          add("entities", retrievalIntent.entities.toStringJsonArray())
          addProperty("timeScope", retrievalIntent.timeScope.name)
          addProperty("fallbackVerbatim", retrievalIntent.fallbackVerbatim)
          addProperty("externalFactTokens", externalFactTokens)
          addProperty("runtimeStateTokens", runtimeStateTokens)
          addProperty("openThreadTokens", openThreadTokens)
          addProperty("memoryAtomTokens", memoryAtomTokens)
          addProperty("fallbackSummaryTokens", fallbackSummaryTokens)
          addProperty("fallbackMemoryTokens", fallbackMemoryTokens)
          addProperty("compactionCount", pack.compactionEntries.size)
          pack.budgetReport?.let { report -> add("budget", report.toDebugJsonObject()) }
          add("externalFacts", pack.externalFacts.toExternalFactDebugJsonArray())
          add("runtimeState", pack.runtimeState.toDebugJsonObject())
          add("openThreads", pack.openThreads.toOpenThreadDebugJsonArray())
          add("memoryAtoms", pack.memoryAtoms.toMemoryAtomDebugJsonArray())
          add("compactionEntries", pack.compactionEntries.toCompactionDebugJsonArray())
          add("fallbackSummary", pack.fallbackSummary.toDebugJsonObject())
          add("fallbackMemories", pack.fallbackMemories.toLegacyMemoryDebugJsonArray())
        },
    )

    debugLog(
      "memory pack sessionId=${session.id} query=${retrievalIntent.query} mode=$budgetMode externalFacts=${pack.externalFacts.size}/${externalFacts.size} runtimeState=${pack.runtimeState != null} openThreads=${pack.openThreads.size}/${openThreads.size} memoryAtoms=${pack.memoryAtoms.size}/${memoryAtoms.size} compactions=${pack.compactionEntries.size}/${compactionEntries.size} fallbackMemories=${pack.fallbackMemories.size}/${fallbackMemories.size}",
    )

    return pack
  }

  private fun debugLog(message: String) {
    runCatching {
      Log.d(MEMORY_CONTEXT_TAG, message)
    }
  }

  private fun plan(
    session: Session,
    role: RoleCard,
    recentMessages: List<Message>,
    pendingUserInput: String,
    budgetMode: PromptBudgetMode,
  ): RoleplayMemoryRetrievalIntent {
    val normalizedInput = pendingUserInput.normalizeWhitespace()
    val recentUserText = buildRecentUserContext(recentMessages)
    val query = buildQuery(normalizedInput, recentUserText)
    val entities = extractEntities(pendingUserInput = pendingUserInput, recentMessages = recentMessages, query = query)
    val includeOpenThreads =
      normalizedInput.containsAny(THREAD_TRIGGER_PATTERNS)
    val includeSemanticRecall = role.memoryEnabled && query.isNotBlank()
    val memoryAtomLimit =
      min(
        role.memoryMaxItems.coerceIn(1, 4),
        when {
          budgetMode == PromptBudgetMode.AGGRESSIVE -> if (normalizedInput.containsAny(HIGH_RECALL_PATTERNS)) 2 else 1
          budgetMode == PromptBudgetMode.COMPACT -> 2
          normalizedInput.containsAny(HIGH_RECALL_PATTERNS) -> 4
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
        normalizedInput.containsAny(HIGH_RECALL_PATTERNS) -> "explicit_recall"
        includeOpenThreads -> "continuity_check"
        else -> "default_sparse"
      }
    val timeScope =
      when {
        normalizedInput.isBlank() -> RoleplayMemoryTimeScope.IMMEDIATE
        normalizedInput.containsAny(LONG_TERM_TRIGGER_PATTERNS) -> RoleplayMemoryTimeScope.LONG_TERM
        normalizedInput.containsAny(HIGH_RECALL_PATTERNS) || includeOpenThreads || normalizedInput.contains("?") ->
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
        if (normalizedInput.containsAny(HIGH_RECALL_PATTERNS) || includeOpenThreads || session.turnCount >= 10) {
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

  private fun buildQuery(pendingUserInput: String, recentText: String): String {
    val raw = listOf(pendingUserInput, recentText).joinToString(separator = " ").normalizeWhitespace()
    if (raw.isBlank()) {
      return ""
    }

    val keywords =
      raw
        .lowercase()
        .replace(NON_QUERY_CHAR_REGEX, " ")
        .split(WHITESPACE_REGEX)
        .filter { term -> term.length >= 3 && term !in QUERY_STOP_WORDS }
        .distinct()
        .take(MAX_MEMORY_QUERY_TERMS)

    return if (keywords.isNotEmpty()) keywords.joinToString(separator = " ") else raw.take(120)
  }

  private fun extractEntities(
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
      QUOTED_ENTITY_REGEX
        .findAll(raw)
        .map { match -> match.groupValues[1].normalizeWhitespace() }
        .filter { it.length >= 2 }
        .toList()
    val namedEntities =
      TITLE_CASE_ENTITY_REGEX
        .findAll(raw)
        .map { it.value.normalizeWhitespace() }
        .filter { it.length >= 3 }
        .toList()
    val queryEntities =
      query
        .split(WHITESPACE_REGEX)
        .map { it.normalizeWhitespace() }
        .filter { it.length >= 4 }
        .take(3)
    return (quotedEntities + namedEntities + queryEntities)
      .distinctBy(String::lowercase)
      .take(4)
  }

  private fun buildRecentUserContext(recentMessages: List<Message>): String {
    return recentMessages
      .asReversed()
      .filter { message -> message.kind == MessageKind.TEXT && message.side == MessageSide.USER }
      .take(3)
      .joinToString(separator = " ") { message -> message.content.normalizeWhitespace() }
  }

  private fun rankOpenThreads(
    openThreads: List<OpenThread>,
    retrievalIntent: RoleplayMemoryRetrievalIntent,
  ): List<OpenThread> {
    val queryTerms = retrievalIntent.query.toQueryTerms()
    val entityTerms = retrievalIntent.entities.map(::normalizeTerm)
    return openThreads.sortedWith(
      compareByDescending<OpenThread> { thread ->
        scoreOpenThread(thread = thread, queryTerms = queryTerms, entityTerms = entityTerms, retrievalIntent = retrievalIntent)
      }.thenByDescending { it.updatedAt },
    )
  }

  private fun rankMemoryAtoms(
    memoryAtoms: List<MemoryAtom>,
    retrievalIntent: RoleplayMemoryRetrievalIntent,
  ): List<MemoryAtom> {
    val queryTerms = retrievalIntent.query.toQueryTerms()
    val entityTerms = retrievalIntent.entities.map(::normalizeTerm)
    return memoryAtoms.sortedWith(
      compareByDescending<MemoryAtom> { atom ->
        scoreMemoryAtom(atom = atom, queryTerms = queryTerms, entityTerms = entityTerms, retrievalIntent = retrievalIntent)
      }.thenByDescending { it.updatedAt },
    )
  }

  private fun rankLegacyMemories(
    memories: List<MemoryItem>,
    retrievalIntent: RoleplayMemoryRetrievalIntent,
  ): List<MemoryItem> {
    val queryTerms = retrievalIntent.query.toQueryTerms()
    val entityTerms = retrievalIntent.entities.map(::normalizeTerm)
    return memories.sortedWith(
      compareByDescending<MemoryItem> { memory ->
        scoreLegacyMemory(memory = memory, queryTerms = queryTerms, entityTerms = entityTerms)
      }.thenByDescending { it.updatedAt },
    )
  }

  private fun rankCompactionEntries(
    entries: List<CompactionCacheEntry>,
    retrievalIntent: RoleplayMemoryRetrievalIntent,
  ): List<CompactionCacheEntry> {
    val queryTerms = retrievalIntent.query.toQueryTerms()
    val entityTerms = retrievalIntent.entities.map(::normalizeTerm)
    return entries.sortedWith(
      compareByDescending<CompactionCacheEntry> { entry ->
        scoreCompactionEntry(entry = entry, queryTerms = queryTerms, entityTerms = entityTerms, retrievalIntent = retrievalIntent)
      }.thenByDescending { it.updatedAt },
    )
  }

  private fun scoreOpenThread(
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

  private fun scoreMemoryAtom(
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

  private fun scoreLegacyMemory(
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

  private fun scoreCompactionEntry(
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

  private fun shouldIncludeCompactionEntries(
    session: Session,
    retrievalIntent: RoleplayMemoryRetrievalIntent,
  ): Boolean {
    return retrievalIntent.needs.contains(RoleplayMemoryNeed.EPISODIC_EVENTS) || session.turnCount >= 12
  }

  private fun mergeSummaryWithCompactions(
    sessionId: String,
    baseSummary: SessionSummary?,
    compactionEntries: List<CompactionCacheEntry>,
  ): SessionSummary? {
    if (baseSummary == null && compactionEntries.isEmpty()) {
      return null
    }

    val compactionBlock =
      compactionEntries
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = "\n") { entry ->
          "- ${entry.summaryType.name.lowercase()}: ${entry.compactText.normalizeWhitespace().take(180)}"
        }.orEmpty()
    val summaryText =
      buildString {
        baseSummary?.summaryText?.trim()?.takeIf(String::isNotBlank)?.let(::append)
        if (compactionBlock.isNotBlank()) {
          if (isNotBlank()) {
            append("\n\n")
          }
          appendLine("Compacted history:")
          append(compactionBlock)
        }
      }.trim()
    if (summaryText.isBlank()) {
      return null
    }
    return SessionSummary(
      sessionId = sessionId,
      version = baseSummary?.version ?: 0,
      coveredUntilSeq = baseSummary?.coveredUntilSeq ?: 0,
      summaryText = summaryText,
      tokenEstimate = tokenEstimator.estimate(summaryText),
      updatedAt = maxOf(baseSummary?.updatedAt ?: 0L, compactionEntries.maxOfOrNull(CompactionCacheEntry::updatedAt) ?: 0L),
    )
  }

  private fun applyPackBudget(
    retrievalIntent: RoleplayMemoryRetrievalIntent,
    externalFacts: List<RoleplayExternalFact>,
    runtimeState: RuntimeStateSnapshot?,
    openThreads: List<OpenThread>,
    memoryAtoms: List<MemoryAtom>,
    compactionEntries: List<CompactionCacheEntry>,
    fallbackSummary: SessionSummary?,
    fallbackMemories: List<MemoryItem>,
    contextProfile: ModelContextProfile?,
    budgetMode: PromptBudgetMode,
  ): RoleplayMemoryContextPack {
    val targetTokens = contextProfile?.let { resolveMemoryPackTargetTokens(it, budgetMode) } ?: Int.MAX_VALUE
    val categoryBudget = resolveCategoryBudget(targetTokens = targetTokens, budgetMode = budgetMode)
    val selectedExternalFacts =
      selectItemsWithinBudget(
        rankedItems = externalFacts,
        itemLimit = 3,
        tokenBudget = categoryBudget.externalFactTokens,
        guaranteedCount = if (externalFacts.isNotEmpty()) 1 else 0,
        tokenEstimate = { fact -> tokenEstimator.estimate(renderExternalFact(fact)) },
      )
    val selectedExternalFactTokens =
      tokenEstimator.estimate(selectedExternalFacts.joinToString("\n") { renderExternalFact(it) })
    val runtimeStateTokens = tokenEstimator.estimate(renderRuntimeState(runtimeState))
    var remainingTokens =
      if (targetTokens == Int.MAX_VALUE) {
        Int.MAX_VALUE
      } else {
        (targetTokens - selectedExternalFactTokens - runtimeStateTokens).coerceAtLeast(0)
      }
    val selectedOpenThreads =
      selectItemsWithinBudget(
        rankedItems = openThreads,
        itemLimit = retrievalIntent.openThreadLimit,
        tokenBudget = minOf(categoryBudget.openThreadTokens, remainingTokens),
        guaranteedCount = if (retrievalIntent.includeOpenThreads) 1 else 0,
        tokenEstimate = { thread -> tokenEstimator.estimate(renderOpenThread(thread)) },
      )
    val selectedOpenThreadTokens = tokenEstimator.estimate(selectedOpenThreads.joinToString("\n") { renderOpenThread(it) })
    remainingTokens = consumeRemainingTokens(remainingTokens, selectedOpenThreadTokens)
    val selectedMemoryAtoms =
      selectItemsWithinBudget(
        rankedItems = memoryAtoms,
        itemLimit = retrievalIntent.memoryAtomLimit,
        tokenBudget = minOf(categoryBudget.memoryAtomTokens, remainingTokens),
        guaranteedCount = if (retrievalIntent.includeSemanticRecall && memoryAtoms.isNotEmpty()) 1 else 0,
        tokenEstimate = { atom -> tokenEstimator.estimate(renderMemoryAtom(atom)) },
      )
    val selectedMemoryAtomTokens = tokenEstimator.estimate(selectedMemoryAtoms.joinToString("\n") { renderMemoryAtom(it) })
    remainingTokens = consumeRemainingTokens(remainingTokens, selectedMemoryAtomTokens)
    val shouldKeepSummary =
      fallbackSummary != null &&
        (selectedOpenThreads.isEmpty() || selectedMemoryAtoms.isEmpty() || runtimeState == null)
    val fallbackSummaryTokens = tokenEstimator.estimate(fallbackSummary?.summaryText.orEmpty())
    val selectedFallbackSummary =
      fallbackSummary?.takeIf {
        shouldKeepSummary && canFitOptionalSection(fallbackSummaryTokens, minOf(categoryBudget.fallbackSummaryTokens, remainingTokens))
      }
    val selectedFallbackSummaryTokens = tokenEstimator.estimate(selectedFallbackSummary?.summaryText.orEmpty())
    remainingTokens = consumeRemainingTokens(remainingTokens, selectedFallbackSummaryTokens)
    val selectedFallbackMemories =
      selectItemsWithinBudget(
        rankedItems = fallbackMemories,
        itemLimit = retrievalIntent.fallbackMemoryLimit,
        tokenBudget = minOf(categoryBudget.fallbackMemoryTokens, remainingTokens),
        guaranteedCount = 0,
        tokenEstimate = { memory -> tokenEstimator.estimate(renderLegacyMemory(memory)) },
      )
    val selectedFallbackMemoryTokens =
      tokenEstimator.estimate(selectedFallbackMemories.joinToString("\n") { renderLegacyMemory(it) })
    val budgetReport =
      if (contextProfile == null) {
        null
      } else {
        RoleplayMemoryPackBudgetReport(
          targetTokens = targetTokens,
          estimatedTokens =
            selectedExternalFactTokens +
            runtimeStateTokens +
              selectedOpenThreadTokens +
              selectedMemoryAtomTokens +
              selectedFallbackSummaryTokens +
              selectedFallbackMemoryTokens,
          mode = budgetMode,
          externalFactTokens = selectedExternalFactTokens,
          runtimeStateTokens = runtimeStateTokens,
          openThreadTokens = selectedOpenThreadTokens,
          memoryAtomTokens = selectedMemoryAtomTokens,
          fallbackSummaryTokens = selectedFallbackSummaryTokens,
          fallbackMemoryTokens = selectedFallbackMemoryTokens,
          droppedExternalFactCount = (externalFacts.size - selectedExternalFacts.size).coerceAtLeast(0),
          droppedOpenThreadCount = (openThreads.size - selectedOpenThreads.size).coerceAtLeast(0),
          droppedMemoryAtomCount = (memoryAtoms.size - selectedMemoryAtoms.size).coerceAtLeast(0),
          droppedFallbackMemoryCount = (fallbackMemories.size - selectedFallbackMemories.size).coerceAtLeast(0),
          droppedFallbackSummary = fallbackSummary != null && selectedFallbackSummary == null,
        )
      }

    return RoleplayMemoryContextPack(
      retrievalIntent = retrievalIntent,
      externalFacts = selectedExternalFacts,
      runtimeState = runtimeState,
      openThreads = selectedOpenThreads,
      memoryAtoms = selectedMemoryAtoms,
      compactionEntries = compactionEntries,
      fallbackSummary = selectedFallbackSummary,
      fallbackMemories = selectedFallbackMemories,
      budgetReport = budgetReport,
    )
  }

  private fun resolveFetchLimit(baseLimit: Int, budgetMode: PromptBudgetMode, maxLimit: Int): Int {
    val multiplier =
      when (budgetMode) {
        PromptBudgetMode.FULL -> 2
        PromptBudgetMode.COMPACT -> 2
        PromptBudgetMode.AGGRESSIVE -> 3
      }
    return (baseLimit.coerceAtLeast(1) * multiplier).coerceAtMost(maxLimit)
  }

  private fun resolveMemoryPackTargetTokens(
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

  private fun resolveCategoryBudget(targetTokens: Int, budgetMode: PromptBudgetMode): MemoryPackCategoryBudget {
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

  private fun <T> selectItemsWithinBudget(
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

  private fun canFitOptionalSection(sectionTokens: Int, tokenBudget: Int): Boolean {
    return tokenBudget == Int.MAX_VALUE || tokenBudget >= sectionTokens || tokenBudget >= (sectionTokens / 2)
  }

  private fun consumeRemainingTokens(remainingTokens: Int, consumedTokens: Int): Int {
    return if (remainingTokens == Int.MAX_VALUE) Int.MAX_VALUE else (remainingTokens - consumedTokens).coerceAtLeast(0)
  }

  private fun renderOpenThread(thread: OpenThread): String {
    return "[${thread.type.name.lowercase()}/${thread.owner.name.lowercase()}/p${thread.priority}] ${thread.content.normalizeWhitespace()}"
  }

  private fun renderMemoryAtom(atom: MemoryAtom): String {
    return "${atom.subject.normalizeWhitespace()} ${atom.predicate.normalizeWhitespace()}: ${atom.objectValue.normalizeWhitespace()}"
  }

  private fun renderLegacyMemory(memory: MemoryItem): String {
    return "${memory.category.name.lowercase()}: ${memory.content.normalizeWhitespace()}"
  }

  private fun renderExternalFact(fact: RoleplayExternalFact): String {
    val freshness =
      when (fact.freshness()) {
        selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.FRESH -> "fresh"
        selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.STALE -> "stale"
        selfgemma.talk.domain.roleplay.model.RoleplayExternalFactFreshness.STABLE -> "stable"
      }
    return "[$freshness/${fact.sourceToolName}] ${fact.title.normalizeWhitespace()}: ${fact.content.normalizeWhitespace()}"
  }

  private fun renderRuntimeState(snapshot: RuntimeStateSnapshot?): String {
    if (snapshot == null) {
      return ""
    }

    return listOf(snapshot.sceneJson, snapshot.relationshipJson, snapshot.activeEntitiesJson)
      .joinToString(separator = "\n")
      .normalizeWhitespace()
  }

  private suspend fun appendEvent(sessionId: String, eventType: SessionEventType, payload: JsonObject) {
    conversationRepository.appendEvent(
      SessionEvent(
        id = java.util.UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = eventType,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      ),
    )
  }

  private fun String.normalizeWhitespace(): String {
    return trim().replace(WHITESPACE_REGEX, " ")
  }

  private fun String.toQueryTerms(): List<String> {
    return split(WHITESPACE_REGEX)
      .map(::normalizeTerm)
      .filter { it.length >= 3 }
      .distinct()
  }

  private fun String.containsAny(patterns: List<String>): Boolean {
    return patterns.any { contains(it) }
  }

  private fun normalizeTerm(value: String): String {
    return value.lowercase().replace(NON_QUERY_CHAR_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
  }

  private fun overlapCount(text: String, terms: List<String>): Int {
    if (text.isBlank() || terms.isEmpty()) {
      return 0
    }
    return terms.count { term -> term.isNotBlank() && text.contains(term) }
  }

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val NON_QUERY_CHAR_REGEX = Regex("[^\\p{L}\\p{N}]+")
    private val TITLE_CASE_ENTITY_REGEX = Regex("\\b[A-Z][A-Za-z0-9'\\-]{2,}\\b")
    private val QUOTED_ENTITY_REGEX = Regex("[\"“”'']([^\"“”'']{2,40})[\"“”'']")
    private val THREAD_TRIGGER_PATTERNS =
      listOf("still", "remember", "promise", "plan", "need to", "have to", "what happened", "why")
    private val HIGH_RECALL_PATTERNS =
      listOf("remember", "again", "last time", "before", "promised", "who", "where", "when")
    private val LONG_TERM_TRIGGER_PATTERNS =
      listOf("always", "usually", "prefer", "favorite", "used to", "normally")
    private val QUERY_STOP_WORDS =
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
  }
}

private data class MemoryPackCategoryBudget(
  val externalFactTokens: Int,
  val openThreadTokens: Int,
  val memoryAtomTokens: Int,
  val fallbackSummaryTokens: Int,
  val fallbackMemoryTokens: Int,
)

private fun RuntimeStateSnapshot?.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    if (this@toDebugJsonObject == null) {
      addProperty("present", false)
      return@apply
    }
    addProperty("present", true)
    addProperty("updatedAt", updatedAt)
    addProperty("sourceMessageId", sourceMessageId)
    addProperty("scene", sceneJson.compactForDebug())
    addProperty("relationship", relationshipJson.compactForDebug())
    addProperty("entities", activeEntitiesJson.compactForDebug())
  }
}

private fun SessionSummary?.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    if (this@toDebugJsonObject == null) {
      addProperty("present", false)
      return@apply
    }
    addProperty("present", true)
    addProperty("updatedAt", updatedAt)
    addProperty("coveredUntilSeq", coveredUntilSeq)
    addProperty("summary", summaryText.compactForDebug(maxLength = 240))
  }
}

private fun RoleplayMemoryPackBudgetReport.toDebugJsonObject(): JsonObject {
  return JsonObject().apply {
    addProperty("targetTokens", targetTokens)
    addProperty("estimatedTokens", estimatedTokens)
    addProperty("mode", mode.name)
    addProperty("externalFactTokens", externalFactTokens)
    addProperty("runtimeStateTokens", runtimeStateTokens)
    addProperty("openThreadTokens", openThreadTokens)
    addProperty("memoryAtomTokens", memoryAtomTokens)
    addProperty("fallbackSummaryTokens", fallbackSummaryTokens)
    addProperty("fallbackMemoryTokens", fallbackMemoryTokens)
    addProperty("droppedExternalFactCount", droppedExternalFactCount)
    addProperty("droppedOpenThreadCount", droppedOpenThreadCount)
    addProperty("droppedMemoryAtomCount", droppedMemoryAtomCount)
    addProperty("droppedFallbackMemoryCount", droppedFallbackMemoryCount)
    addProperty("droppedFallbackSummary", droppedFallbackSummary)
  }
}

private fun List<RoleplayExternalFact>.toExternalFactDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toExternalFactDebugJsonArray.forEach { fact ->
      add(
        JsonObject().apply {
          addProperty("id", fact.id)
          addProperty("sourceToolName", fact.sourceToolName)
          addProperty("factKey", fact.factKey)
          addProperty("factType", fact.factType)
          addProperty("title", fact.title.compactForDebug(maxLength = 80))
          addProperty("content", fact.content.compactForDebug(maxLength = 180))
          addProperty("ephemeral", fact.ephemeral)
          addProperty("summaryEligible", fact.summaryEligible)
          addProperty("capturedAt", fact.capturedAt)
          addProperty("freshnessTtlMillis", fact.freshnessTtlMillis ?: -1L)
          addProperty("toolInvocationId", fact.toolInvocationId)
        },
      )
    }
  }
}

private fun List<OpenThread>.toOpenThreadDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toOpenThreadDebugJsonArray.forEach { thread ->
      add(
        JsonObject().apply {
          addProperty("id", thread.id)
          addProperty("type", thread.type.name)
          addProperty("status", thread.status.name)
          addProperty("priority", thread.priority)
          addProperty("content", thread.content.compactForDebug(maxLength = 160))
        },
      )
    }
  }
}

private fun List<MemoryAtom>.toMemoryAtomDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toMemoryAtomDebugJsonArray.forEach { atom ->
      add(
        JsonObject().apply {
          addProperty("id", atom.id)
          addProperty("plane", atom.plane.name)
          addProperty("namespace", atom.namespace.name)
          addProperty("stability", atom.stability.name)
          addProperty("subject", atom.subject.compactForDebug(maxLength = 64))
          addProperty("predicate", atom.predicate.compactForDebug(maxLength = 64))
          addProperty("objectValue", atom.objectValue.compactForDebug(maxLength = 120))
          addProperty("evidence", atom.evidenceQuote.compactForDebug(maxLength = 120))
        },
      )
    }
  }
}

private fun List<CompactionCacheEntry>.toCompactionDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toCompactionDebugJsonArray.forEach { entry ->
      add(
        JsonObject().apply {
          addProperty("id", entry.id)
          addProperty("summaryType", entry.summaryType.name)
          addProperty("rangeStartMessageId", entry.rangeStartMessageId)
          addProperty("rangeEndMessageId", entry.rangeEndMessageId)
          addProperty("compactText", entry.compactText.compactForDebug(maxLength = 180))
          addProperty("tokenEstimate", entry.tokenEstimate)
        },
      )
    }
  }
}

private fun List<MemoryItem>.toLegacyMemoryDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toLegacyMemoryDebugJsonArray.forEach { memory ->
      add(
        JsonObject().apply {
          addProperty("id", memory.id)
          addProperty("category", memory.category.name)
          addProperty("content", memory.content.compactForDebug(maxLength = 160))
          addProperty("confidence", memory.confidence)
        },
      )
    }
  }
}

private fun List<RoleplayMemoryNeed>.toDebugJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toDebugJsonArray.forEach { add(it.name) }
  }
}

private fun List<String>.toStringJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toStringJsonArray.forEach(::add)
  }
}

private fun String.compactForDebug(maxLength: Int = 200): String {
  return replace(Regex("\\s+"), " ").trim().take(maxLength)
}
