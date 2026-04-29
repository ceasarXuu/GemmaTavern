package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.resolvedName
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

/**
 * Suspend orchestration helpers extracted from [ExtractMemoriesUseCase]. Each function takes the
 * repositories it needs as parameters so the use case can compose them without owning their
 * implementation. Behavior is preserved verbatim from the original instance methods.
 */

private const val EM_ORCH_TAG = "ExtractMemoriesUseCase"

internal fun emDebugLog(message: String) {
  runCatching {
    Log.d(EM_ORCH_TAG, message)
  }
}

internal suspend fun appendEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  eventType: SessionEventType,
  payload: JsonObject,
) {
  conversationRepository.appendEvent(
    SessionEvent(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      eventType = eventType,
      payloadJson = payload.toString(),
      createdAt = System.currentTimeMillis(),
    ),
  )
}

internal suspend fun appendMemoryUpsertEvent(
  conversationRepository: ConversationRepository,
  sessionId: String,
  memories: List<MemoryItem>,
  source: String,
) {
  if (memories.isEmpty()) {
    return
  }

  val memoryIds =
    JsonArray().apply {
      memories.forEach { memory -> add(memory.id) }
    }
  val categories =
    JsonArray().apply {
      memories
        .map { it.category.name }
        .distinct()
        .forEach(::add)
    }
  appendEvent(
    conversationRepository = conversationRepository,
    sessionId = sessionId,
    eventType = SessionEventType.MEMORY_UPSERT,
    payload =
      JsonObject().apply {
        addProperty("source", source)
        addProperty("memoryCount", memories.size)
        add("memoryIds", memoryIds)
        add("categories", categories)
      },
  )
}

internal suspend fun applyUserCorrections(
  memoryAtomRepository: MemoryAtomRepository,
  memoryRepository: MemoryRepository,
  runtimeStateRepository: RuntimeStateRepository,
  conversationRepository: ConversationRepository,
  session: Session,
  userMessage: Message,
  existingAtoms: MutableList<MemoryAtom>,
  existingMemories: List<MemoryItem>,
  now: Long,
  writeLegacyMemories: Boolean,
): CorrectionResult {
  val preferenceCorrection = extractPreferenceCorrection(userMessage.content)
  val correctedLocation = extractLocationCorrection(userMessage.content)
  val previousSnapshot = runtimeStateRepository.getLatestSnapshot(session.id)
  var correctedAtomCount = 0
  var correctedMemoryCount = 0

  if (preferenceCorrection != null) {
    val replacementCandidate = preferenceCorrection.replacementCandidate
    val correctedAtoms =
      existingAtoms.filter { atom ->
        !atom.tombstone &&
          atom.namespace == replacementCandidate.toNamespace() &&
          atom.subject == replacementCandidate.toSubject() &&
          atom.predicate == replacementCandidate.toPredicate() &&
          atom.normalizedObjectValue.contains(preferenceCorrection.rejectedNormalizedValue)
      }
    correctedAtoms.forEach { atom ->
      memoryAtomRepository.tombstone(atom.id, now)
      val index = existingAtoms.indexOfFirst { existing -> existing.id == atom.id }
      if (index != -1) {
        existingAtoms[index] = existingAtoms[index].copy(tombstone = true, updatedAt = now)
      }
    }
    correctedAtomCount += correctedAtoms.size

    if (writeLegacyMemories) {
      val correctedMemories =
        existingMemories.filter { memory ->
          memory.active &&
            memory.category == replacementCandidate.category &&
            normalizeForHash(memory.content).contains(preferenceCorrection.rejectedNormalizedValue)
        }
      correctedMemories.forEach { memory ->
        memoryRepository.deactivate(memory.id)
      }
      correctedMemoryCount += correctedMemories.size
    }
  }

  val previousLocation = previousSnapshot.extractSceneField("location")
  if (
    correctedAtomCount > 0 ||
      correctedMemoryCount > 0 ||
      (correctedLocation != null && correctedLocation != previousLocation)
  ) {
    appendEvent(
      conversationRepository = conversationRepository,
      sessionId = session.id,
      eventType = SessionEventType.MEMORY_CORRECTION_APPLIED,
      payload =
        JsonObject().apply {
          addProperty("correctedAtomCount", correctedAtomCount)
          addProperty("correctedMemoryCount", correctedMemoryCount)
          preferenceCorrection?.let { correction ->
            addProperty("kind", "preference_or_fact")
            addProperty("rejectedValue", correction.rejectedRawValue)
            addProperty("replacementValue", correction.replacementCandidate.content)
          }
          if (correctedLocation != null) {
            addProperty("locationBefore", previousLocation)
            addProperty("locationAfter", correctedLocation)
          }
        },
    )
  }

  return CorrectionResult(correctedLocation = correctedLocation)
}

internal suspend fun syncOpenThreads(
  openThreadRepository: OpenThreadRepository,
  conversationRepository: ConversationRepository,
  session: Session,
  userMessage: Message,
  assistantMessage: Message?,
  now: Long,
) {
  val openThreads = openThreadRepository.listByStatus(session.id, OpenThreadStatus.OPEN)
  val deprecatedQuestionThreads =
    openThreads.filter { thread -> thread.type == OpenThreadType.QUESTION }
  deprecatedQuestionThreads.forEach { thread ->
    openThreadRepository.updateStatus(
      threadId = thread.id,
      status = OpenThreadStatus.DROPPED,
      resolvedByMessageId = null,
      updatedAt = now,
    )
  }
  val activeOpenThreads =
    openThreads.filterNot { thread -> thread.type == OpenThreadType.QUESTION }
  val resolutionText = listOfNotNull(userMessage.content, assistantMessage?.content).joinToString("\n").lowercase()
  var resolvedCount = 0
  var droppedCount = deprecatedQuestionThreads.size

  if (resolutionText.emContainsAny(THREAD_RESOLUTION_PATTERNS)) {
    activeOpenThreads.forEach { thread ->
      if (shouldResolveThread(thread, resolutionText)) {
        openThreadRepository.updateStatus(
          threadId = thread.id,
          status = OpenThreadStatus.RESOLVED,
          resolvedByMessageId = assistantMessage?.id ?: userMessage.id,
          updatedAt = now,
        )
        resolvedCount += 1
      }
    }
  }

  val existingByContent = activeOpenThreads.associateBy { normalizeForHash(it.content) }
  val threadCandidates = extractOpenThreadCandidates(userMessage = userMessage, assistantMessage = assistantMessage)
  var upsertedCount = 0
  threadCandidates.forEach { candidate ->
    val existing = existingByContent[normalizeForHash(candidate.content)]
    val thread =
      if (existing != null) {
        existing.copy(
          priority = maxOf(existing.priority, candidate.priority),
          sourceMessageIds = (existing.sourceMessageIds + candidate.sourceMessageIds).distinct(),
          updatedAt = now,
          status = OpenThreadStatus.OPEN,
        )
      } else {
        OpenThread(
          id = UUID.randomUUID().toString(),
          sessionId = session.id,
          type = candidate.type,
          content = candidate.content,
          owner = candidate.owner,
          priority = candidate.priority,
          status = OpenThreadStatus.OPEN,
          sourceMessageIds = candidate.sourceMessageIds,
          createdAt = now,
          updatedAt = now,
        )
      }
    openThreadRepository.upsert(thread)
    upsertedCount += 1
  }

  if (upsertedCount > 0 || resolvedCount > 0 || droppedCount > 0) {
    appendEvent(
      conversationRepository = conversationRepository,
      sessionId = session.id,
      eventType = SessionEventType.MEMORY_THREAD_UPSERTED,
      payload =
        JsonObject().apply {
          addProperty("upsertedCount", upsertedCount)
          addProperty("resolvedCount", resolvedCount)
          addProperty("droppedCount", droppedCount)
        },
    )
    emDebugLog("thread maintenance sessionId=${session.id} upserted=$upsertedCount resolved=$resolvedCount dropped=$droppedCount")
  }
}

internal suspend fun upsertRuntimeState(
  runtimeStateRepository: RuntimeStateRepository,
  conversationRepository: ConversationRepository,
  session: Session,
  role: RoleCard,
  userMessage: Message,
  assistantMessage: Message?,
  correctedLocation: String?,
  now: Long,
) {
  val previous = runtimeStateRepository.getLatestSnapshot(session.id)
  val scene = previous?.sceneJson.toMutableJsonObject()
  val relationship = previous?.relationshipJson.toMutableJsonObject()
  val activeEntities = previous?.activeEntitiesJson.toMutableJsonObject()
  val roleName = role.resolvedName().ifBlank { "assistant" }
  val sceneTime = extractSceneTime(userMessage.content, assistantMessage?.content)
  val currentGoal = extractGoal(userMessage.content, assistantMessage?.content)
  val dangerLevel = detectDangerLevel(userMessage.content, assistantMessage?.content)
  val importantItems = extractImportantItems(userMessage.content, assistantMessage?.content)
  val activeTopic = extractActiveTopic(userMessage.content, assistantMessage?.content)
  val currentMood = detectMood(assistantMessage?.content ?: userMessage.content)
  val presentEntities =
    extractPresentEntities(
      roleName = roleName,
      activeEntities = activeEntities,
      userContent = userMessage.content,
      assistantContent = assistantMessage?.content,
    )
  val focusEntities =
    (presentEntities.take(3) + importantItems.take(2))
      .distinct()
      .take(4)

  (correctedLocation ?: extractLocation(userMessage.content, assistantMessage?.content))
    ?.let { scene.addProperty("location", it) }
  sceneTime?.let { scene.addProperty("time", it) }
  currentGoal?.let {
    scene.addProperty("currentGoal", it)
    scene.addProperty("goal", it)
  }
  dangerLevel?.let { scene.addProperty("dangerLevel", it) }
  if (importantItems.isNotEmpty()) {
    scene.putStringArray("importantItems", importantItems)
  }
  activeTopic?.let { scene.addProperty("activeTopic", it) }
  scene.addProperty("recentAction", buildRecentAction(userMessage, assistantMessage))

  currentMood?.let { relationship.addProperty("currentMood", it) }
  updateRelationshipState(
    relationship = relationship,
    userMessage = userMessage,
    assistantMessage = assistantMessage,
    currentMood = currentMood,
    dangerLevel = dangerLevel,
  )
  relationship.addProperty("lastShiftReason", sanitizeContent(userMessage.content).take(120))

  if (presentEntities.isNotEmpty()) {
    activeEntities.putStringArray("present", presentEntities)
  }
  if (focusEntities.isNotEmpty()) {
    activeEntities.putStringArray("focus", focusEntities)
  }

  runtimeStateRepository.upsert(
    RuntimeStateSnapshot(
      sessionId = session.id,
      sceneJson = scene.toString(),
      relationshipJson = relationship.toString(),
      activeEntitiesJson = activeEntities.toString(),
      updatedAt = now,
      sourceMessageId = assistantMessage?.id ?: userMessage.id,
    ),
  )
  appendEvent(
    conversationRepository = conversationRepository,
    sessionId = session.id,
    eventType = SessionEventType.MEMORY_RUNTIME_STATE_UPDATED,
    payload =
      JsonObject().apply {
        addProperty("hasLocation", scene.has("location"))
        addProperty("hasGoal", scene.has("currentGoal") || scene.has("goal"))
        addProperty("hasDangerLevel", scene.has("dangerLevel"))
        addProperty("hasMood", relationship.has("currentMood"))
        addProperty("presentEntityCount", presentEntities.size)
        addProperty("importantItemCount", importantItems.size)
      },
  )
}
