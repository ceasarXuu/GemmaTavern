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
      planMemoryRetrieval(
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
        tokenEstimator = tokenEstimator,
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

}

