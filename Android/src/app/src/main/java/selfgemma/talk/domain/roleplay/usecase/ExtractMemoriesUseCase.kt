package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
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

private const val TAG = "ExtractMemoriesUseCase"
private const val MAX_AUTOMATIC_MEMORY_ITEMS = 3
private const val MAX_MEMORY_LENGTH = 180
private const val MAX_THREAD_CONTENT_LENGTH = 180
private const val MAX_RUNTIME_ACTION_LENGTH = 180

class ExtractMemoriesUseCase @Inject constructor(
  private val memoryRepository: MemoryRepository,
  private val memoryAtomRepository: MemoryAtomRepository,
  private val openThreadRepository: OpenThreadRepository,
  private val runtimeStateRepository: RuntimeStateRepository,
  private val conversationRepository: ConversationRepository,
  private val validateMemoryAtomCandidateUseCase: ValidateMemoryAtomCandidateUseCase,
) {
  suspend operator fun invoke(
    session: Session,
    role: RoleCard,
    userMessage: Message,
    assistantMessage: Message?,
  ) {
    processTurn(
      session = session,
      role = role,
      userMessage = userMessage,
      assistantMessage = assistantMessage,
      writeLegacyMemories = true,
    )
  }

  suspend fun rebuildStructuredState(
    session: Session,
    role: RoleCard,
    userMessage: Message,
    assistantMessage: Message?,
  ) {
    processTurn(
      session = session,
      role = role,
      userMessage = userMessage,
      assistantMessage = assistantMessage,
      writeLegacyMemories = false,
    )
  }

  private suspend fun processTurn(
    session: Session,
    role: RoleCard,
    userMessage: Message,
    assistantMessage: Message?,
    writeLegacyMemories: Boolean,
  ) {
    if (!role.memoryEnabled || role.memoryMaxItems <= 0) {
      return
    }

    val now = System.currentTimeMillis()
    val existingMemories = if (writeLegacyMemories) loadExistingMemoryList(role.id, session.id) else emptyList()
    val existingByHash = existingMemories.associateBy { it.normalizedHash }
    val existingAtoms = memoryAtomRepository.listBySession(session.id).toMutableList()
    val correctionResult =
      applyUserCorrections(
        session = session,
        userMessage = userMessage,
        existingAtoms = existingAtoms,
        existingMemories = existingMemories,
        now = now,
        writeLegacyMemories = writeLegacyMemories,
      )
    val candidates =
      buildList {
          addAll(extractFromUserMessage(userMessage.content))
          if (assistantMessage != null) {
            addAll(extractFromAssistantMessage(assistantMessage.content))
          }
        }
        .distinctBy { normalizeForHash(it.content) }
        .take(minOf(role.memoryMaxItems, MAX_AUTOMATIC_MEMORY_ITEMS))
    val sourceMessageIds = listOfNotNull(userMessage.id, assistantMessage?.id)

    if (writeLegacyMemories && candidates.isNotEmpty()) {
      candidates.forEach { candidate ->
        val memory =
          buildMemory(
            session = session,
            role = role,
            candidate = candidate,
            sourceMessageIds = sourceMessageIds,
            existing = existingByHash[hashContent(candidate.content)],
            pinned = null,
            now = now,
          )
        memoryRepository.upsert(memory)
      }
      appendEvent(
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_UPSERT,
        payload =
          JsonObject().apply {
            addProperty("source", "automatic")
            addProperty("count", candidates.size)
            addProperty("sourceMessageCount", sourceMessageIds.size)
          },
      )

    }

    if (candidates.isNotEmpty()) {
      var acceptedAtomCount = 0
      var promotedAtomCount = 0
      var rejectedAtomCount = 0
      candidates.forEach { candidate ->
        val sourceSide = if (candidate.fromAssistant) MessageSide.ASSISTANT else MessageSide.USER
        val decision =
          validateMemoryAtomCandidateUseCase(
            candidate =
              buildMemoryAtom(
                session = session,
                role = role,
                candidate = candidate,
                sourceSide = sourceSide,
                sourceMessageIds = sourceMessageIds,
                existing = existingAtoms.firstOrNull { atom -> atom.hasSameAtomKey(candidate) },
                pinned = null,
                now = now,
              ),
            sourceSide = sourceSide,
            pinned = false,
            existingAtoms = existingAtoms,
          )

        if (!decision.accepted || decision.atom == null) {
          rejectedAtomCount += 1
          appendEvent(
            sessionId = session.id,
            eventType = SessionEventType.MEMORY_OP_REJECTED,
            payload =
              JsonObject().apply {
                addProperty("reason", decision.rejectionReason ?: "unknown")
                addProperty("subject", candidate.toSubject())
                addProperty("predicate", candidate.toPredicate())
              },
          )
          return@forEach
        }

        decision.tombstoneIds.forEach { memoryId ->
          memoryAtomRepository.tombstone(memoryId, now)
          val tombstonedIndex = existingAtoms.indexOfFirst { atom -> atom.id == memoryId }
          if (tombstonedIndex != -1) {
            existingAtoms[tombstonedIndex] = existingAtoms[tombstonedIndex].copy(tombstone = true, updatedAt = now)
          }
        }
        memoryAtomRepository.upsert(decision.atom)
        existingAtoms.removeAll { atom -> atom.id == decision.atom.id }
        existingAtoms.add(decision.atom)
        acceptedAtomCount += 1
        if (decision.promoted) {
          promotedAtomCount += 1
          appendEvent(
            sessionId = session.id,
            eventType = SessionEventType.MEMORY_ATOM_PROMOTED,
            payload =
              JsonObject().apply {
                addProperty("id", decision.atom.id)
                addProperty("stability", decision.atom.stability.name)
              },
          )
        }
      }
      appendEvent(
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_ATOM_UPSERTED,
        payload =
          JsonObject().apply {
            addProperty("count", acceptedAtomCount)
            addProperty("promotedCount", promotedAtomCount)
            addProperty("rejectedCount", rejectedAtomCount)
          },
      )
    }

    syncOpenThreads(session = session, userMessage = userMessage, assistantMessage = assistantMessage, now = now)
    upsertRuntimeState(
      session = session,
      role = role,
      userMessage = userMessage,
      assistantMessage = assistantMessage,
      correctedLocation = correctionResult.correctedLocation,
      now = now,
    )
  }

  suspend fun pinMessage(session: Session, role: RoleCard, message: Message): MemoryItem? {
    if (message.content.isBlank()) {
      return null
    }

    val candidate =
      inferCandidate(message.content, message.side)
        ?: MemoryCandidate(
          category = if (message.side == MessageSide.USER) MemoryCategory.PREFERENCE else MemoryCategory.PLOT,
          content = sanitizeContent(message.content),
          confidence = 0.95f,
          fromAssistant = message.side == MessageSide.ASSISTANT,
        )

    if (candidate.content.isBlank()) {
      return null
    }

    val existingByHash = loadExistingMemoryList(role.id, session.id).associateBy { it.normalizedHash }
    val existingAtoms = memoryAtomRepository.listBySession(session.id)
    val now = System.currentTimeMillis()
    val memory =
      buildMemory(
        session = session,
        role = role,
        candidate = candidate,
        sourceMessageIds = listOf(message.id),
        existing = existingByHash[hashContent(candidate.content)],
        pinned = true,
        now = now,
      )
    memoryRepository.upsert(memory)
    appendMemoryUpsertEvent(sessionId = session.id, memories = listOf(memory), source = "pinned")
    persistValidatedAtom(
      session = session,
      role = role,
      candidate = candidate,
      sourceSide = message.side,
      sourceMessageIds = listOf(message.id),
      pinned = true,
      now = now,
      existingAtoms = existingAtoms,
    )
    return memory
  }

  suspend fun addManualMemory(
    session: Session,
    role: RoleCard,
    content: String,
    category: MemoryCategory,
  ): MemoryItem? {
    val sanitized = sanitizeContent(content)
    if (sanitized.length < 3) {
      return null
    }

    val candidate = MemoryCandidate(category = category, content = sanitized, confidence = 1.0f, fromAssistant = false)
    val existingByHash = loadExistingMemoryList(role.id, session.id).associateBy { it.normalizedHash }
    val existingAtoms = memoryAtomRepository.listBySession(session.id)
    val now = System.currentTimeMillis()
    val memory =
      buildMemory(
        session = session,
        role = role,
        candidate = candidate,
        sourceMessageIds = emptyList(),
        existing = existingByHash[hashContent(sanitized)],
        pinned = true,
        now = now,
      )
    memoryRepository.upsert(memory)
    appendMemoryUpsertEvent(sessionId = session.id, memories = listOf(memory), source = "manual")
    persistValidatedAtom(
      session = session,
      role = role,
      candidate = candidate,
      sourceSide = MessageSide.USER,
      sourceMessageIds = emptyList(),
      pinned = true,
      now = now,
      existingAtoms = existingAtoms,
    )
    return memory
  }

  private suspend fun loadExistingMemoryList(roleId: String, sessionId: String): List<MemoryItem> {
    return memoryRepository.listRoleMemories(roleId) + memoryRepository.listSessionMemories(sessionId)
  }

  private suspend fun persistValidatedAtom(
    session: Session,
    role: RoleCard,
    candidate: MemoryCandidate,
    sourceSide: MessageSide,
    sourceMessageIds: List<String>,
    pinned: Boolean,
    now: Long,
    existingAtoms: List<MemoryAtom>,
  ): MemoryAtomValidationResult {
    val decision =
      validateMemoryAtomCandidateUseCase(
        candidate =
          buildMemoryAtom(
            session = session,
            role = role,
            candidate = candidate,
            sourceSide = sourceSide,
            sourceMessageIds = sourceMessageIds,
            existing = existingAtoms.firstOrNull { atom -> atom.hasSameAtomKey(candidate) },
            pinned = pinned,
            now = now,
          ),
        sourceSide = sourceSide,
        pinned = pinned,
        existingAtoms = existingAtoms,
      )
    if (!decision.accepted || decision.atom == null) {
      appendEvent(
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_OP_REJECTED,
        payload =
          JsonObject().apply {
            addProperty("reason", decision.rejectionReason ?: "unknown")
            addProperty("subject", candidate.toSubject())
            addProperty("predicate", candidate.toPredicate())
          },
      )
      return decision
    }

    decision.tombstoneIds.forEach { memoryId ->
      memoryAtomRepository.tombstone(memoryId, now)
    }
    memoryAtomRepository.upsert(decision.atom)
    if (decision.promoted) {
      appendEvent(
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_ATOM_PROMOTED,
        payload =
          JsonObject().apply {
            addProperty("id", decision.atom.id)
            addProperty("stability", decision.atom.stability.name)
          },
      )
    }
    return decision
  }

  private suspend fun syncOpenThreads(
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

    if (resolutionText.containsAny(THREAD_RESOLUTION_PATTERNS)) {
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
        sessionId = session.id,
        eventType = SessionEventType.MEMORY_THREAD_UPSERTED,
        payload =
          JsonObject().apply {
            addProperty("upsertedCount", upsertedCount)
            addProperty("resolvedCount", resolvedCount)
            addProperty("droppedCount", droppedCount)
          },
      )
      debugLog("thread maintenance sessionId=${session.id} upserted=$upsertedCount resolved=$resolvedCount dropped=$droppedCount")
    }
  }

  private suspend fun upsertRuntimeState(
    session: Session,
    role: RoleCard,
    userMessage: Message,
    assistantMessage: Message?,
    correctedLocation: String? = null,
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

  private fun extractFromUserMessage(content: String): List<MemoryCandidate> {
    return content
      .split(SPLIT_REGEX)
      .map(::sanitizeContent)
      .filter { it.length >= 4 }
      .mapNotNull { line ->
        extractPreferenceCorrection(line)?.replacementCandidate ?: inferCandidate(line, MessageSide.USER)
      }
  }

  private fun extractFromAssistantMessage(content: String): List<MemoryCandidate> {
    return splitIntoCandidates(content).mapNotNull { line -> inferCandidate(line, MessageSide.ASSISTANT) }
  }

  private fun inferCandidate(content: String, side: MessageSide): MemoryCandidate? {
    val sanitized = sanitizeContent(content)
    if (sanitized.length < 12) {
      return null
    }

    val normalized = sanitized.lowercase()

    val category =
      when {
        normalized.containsAny(PREFERENCE_PATTERNS) -> MemoryCategory.PREFERENCE
        normalized.containsAny(RELATION_PATTERNS) -> MemoryCategory.RELATION
        normalized.containsAny(PLOT_PATTERNS) -> MemoryCategory.PLOT
        normalized.containsAny(WORLD_PATTERNS) -> MemoryCategory.WORLD
        side == MessageSide.USER && normalized.startsWith("remember") -> MemoryCategory.TODO
        else -> null
      }
        ?: return null

    val confidence = if (side == MessageSide.USER) 0.78f else 0.62f
    return MemoryCandidate(
      category = category,
      content = sanitized,
      confidence = confidence,
      fromAssistant = side == MessageSide.ASSISTANT,
    )
  }

  private fun buildMemory(
    session: Session,
    role: RoleCard,
    candidate: MemoryCandidate,
    sourceMessageIds: List<String>,
    existing: MemoryItem?,
    pinned: Boolean?,
    now: Long,
  ): MemoryItem {
    val hash = hashContent(candidate.content)

    return MemoryItem(
      id = existing?.id ?: UUID.randomUUID().toString(),
      roleId = role.id,
      sessionId = session.id,
      category = existing?.category ?: candidate.category,
      content = candidate.content,
      normalizedHash = hash,
      confidence = maxOf(existing?.confidence ?: 0f, candidate.confidence),
      pinned = pinned ?: existing?.pinned ?: false,
      active = true,
      sourceMessageIds = (existing?.sourceMessageIds.orEmpty() + sourceMessageIds).distinct(),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
      lastUsedAt = existing?.lastUsedAt,
    )
  }

  private fun buildMemoryAtom(
    session: Session,
    role: RoleCard,
    candidate: MemoryCandidate,
    sourceSide: MessageSide,
    sourceMessageIds: List<String>,
    existing: MemoryAtom?,
    pinned: Boolean?,
    now: Long,
  ): MemoryAtom {
    val normalizedObjectValue = normalizeForHash(candidate.content)
    val namespace = candidate.toNamespace()
    val subject = candidate.toSubject()
    val predicate = candidate.toPredicate()

    return MemoryAtom(
      id = existing?.id ?: UUID.randomUUID().toString(),
      sessionId = session.id,
      roleId = role.id,
      plane = candidate.toPlane(),
      namespace = namespace,
      subject = subject,
      predicate = predicate,
      objectValue = candidate.content,
      normalizedObjectValue = normalizedObjectValue,
      stability =
        when {
          pinned == true -> MemoryStability.LOCKED
          sourceSide == MessageSide.USER -> MemoryStability.STABLE
          else -> MemoryStability.CANDIDATE
        },
      epistemicStatus =
        when (sourceSide) {
          MessageSide.USER -> MemoryEpistemicStatus.SELF_REPORT
          MessageSide.ASSISTANT -> MemoryEpistemicStatus.OBSERVED
          MessageSide.SYSTEM -> MemoryEpistemicStatus.INFERRED
        },
      salience = candidate.toSalience(),
      confidence = maxOf(existing?.confidence ?: 0f, candidate.confidence),
      timeStartMessageId = sourceMessageIds.firstOrNull(),
      timeEndMessageId = sourceMessageIds.lastOrNull(),
      branchScope = MemoryBranchScope.ACCEPTED_ONLY,
      sourceMessageIds = (existing?.sourceMessageIds.orEmpty() + sourceMessageIds).distinct(),
      evidenceQuote = candidate.content,
      supersedesMemoryId = null,
      tombstone = false,
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
      lastUsedAt = existing?.lastUsedAt,
    )
  }

  private fun extractOpenThreadCandidates(
    userMessage: Message,
    assistantMessage: Message?,
  ): List<OpenThreadCandidate> {
    return buildList {
        extractTaskCandidate(userMessage, assistantMessage)?.let(::add)
        extractPromiseCandidate(assistantMessage)?.let(::add)
        extractMysteryCandidate(userMessage, assistantMessage)?.let(::add)
        extractEmotionalCandidate(userMessage, assistantMessage)?.let(::add)
      }
      .distinctBy { normalizeForHash(it.content) }
  }

  private fun extractTaskCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
    val sourceMessage = assistantMessage?.takeIf { it.content.lowercase().containsAny(TASK_PATTERNS) } ?: userMessage
    val sentence = firstSentenceContaining(sourceMessage.content, TASK_PATTERNS) ?: return null
    return OpenThreadCandidate(
      type = OpenThreadType.TASK,
      owner = OpenThreadOwner.SHARED,
      content = sentence.take(MAX_THREAD_CONTENT_LENGTH),
      priority = 88,
      sourceMessageIds = listOf(sourceMessage.id),
    )
  }

  private fun extractPromiseCandidate(message: Message?): OpenThreadCandidate? {
    val sourceMessage = message ?: return null
    val sentence = firstSentenceContaining(sourceMessage.content, PROMISE_PATTERNS) ?: return null
    return OpenThreadCandidate(
      type = OpenThreadType.PROMISE,
      owner = OpenThreadOwner.ASSISTANT,
      content = sentence.take(MAX_THREAD_CONTENT_LENGTH),
      priority = 90,
      sourceMessageIds = listOf(sourceMessage.id),
    )
  }

  private fun extractMysteryCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
    val sourceMessage =
      listOfNotNull(userMessage, assistantMessage)
        .firstOrNull { message -> message.content.lowercase().containsAny(MYSTERY_PATTERNS) }
        ?: return null
    val sentence = firstSentenceContaining(sourceMessage.content, MYSTERY_PATTERNS) ?: return null
    return OpenThreadCandidate(
      type = OpenThreadType.MYSTERY,
      owner = OpenThreadOwner.SHARED,
      content = sentence.take(MAX_THREAD_CONTENT_LENGTH),
      priority = 84,
      sourceMessageIds = listOf(sourceMessage.id),
    )
  }

  private fun extractEmotionalCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
    val sourceMessage =
      listOfNotNull(userMessage, assistantMessage)
        .firstOrNull { message -> message.content.lowercase().containsAny(EMOTIONAL_PATTERNS) }
        ?: return null
    val sentence = firstSentenceContaining(sourceMessage.content, EMOTIONAL_PATTERNS) ?: return null
    return OpenThreadCandidate(
      type = OpenThreadType.EMOTIONAL,
      owner = if (sourceMessage.side == MessageSide.USER) OpenThreadOwner.USER else OpenThreadOwner.ASSISTANT,
      content = sentence.take(MAX_THREAD_CONTENT_LENGTH),
      priority = 76,
      sourceMessageIds = listOf(sourceMessage.id),
    )
  }

  private fun shouldResolveThread(thread: OpenThread, resolutionText: String): Boolean {
    val threadTerms =
      normalizeForHash(thread.content)
        .split(WHITESPACE_REGEX)
        .filter { term -> term.length >= 4 && term !in THREAD_STOP_WORDS }
    val overlap = threadTerms.count { term -> resolutionText.contains(term) }
    return overlap >= 1
  }

  private fun extractLocation(vararg contents: String?): String? {
    contents.forEach { raw ->
      val match = LOCATION_REGEX.find(raw.orEmpty()) ?: return@forEach
      val location = normalizeLocationValue(match.groupValues[1])
      if (location.length >= 3) {
        return location
      }
    }
    return null
  }

  private fun extractSceneTime(vararg contents: String?): String? {
    contents.forEach { raw ->
      val normalized = raw.orEmpty().lowercase()
      SCENE_TIME_PATTERNS.firstOrNull { (pattern, _) -> normalized.contains(pattern) }?.let { (_, canonicalValue) ->
        return canonicalValue
      }
    }
    return null
  }

  private fun extractGoal(vararg contents: String?): String? {
    contents.forEach { raw ->
      val sentence = firstSentenceContaining(raw.orEmpty(), GOAL_PATTERNS) ?: return@forEach
      return sentence.take(MAX_RUNTIME_ACTION_LENGTH)
    }
    return null
  }

  private fun buildRecentAction(userMessage: Message, assistantMessage: Message?): String {
    val source = assistantMessage?.content?.takeIf(String::isNotBlank) ?: userMessage.content
    return sanitizeContent(source).take(MAX_RUNTIME_ACTION_LENGTH)
  }

  private fun detectMood(content: String): String? {
    val normalized = content.lowercase()
    return when {
      normalized.containsAny(listOf("angry", "furious", "annoyed")) -> "angry"
      normalized.containsAny(listOf("worried", "anxious", "afraid", "nervous", "tense", "grim", "urgent")) -> "tense"
      normalized.containsAny(listOf("sad", "upset", "hurt")) -> "sad"
      normalized.containsAny(listOf("happy", "relieved", "glad")) -> "positive"
      normalized.containsAny(listOf("calm", "steady", "composed")) -> "calm"
      else -> null
    }
  }

  private fun detectDangerLevel(vararg contents: String?): String? {
    contents.forEach { raw ->
      val normalized = raw.orEmpty().lowercase()
      when {
        normalized.containsAny(DANGER_CRITICAL_PATTERNS) -> return "critical"
        normalized.containsAny(DANGER_HIGH_PATTERNS) -> return "high"
        normalized.containsAny(DANGER_GUARDED_PATTERNS) -> return "guarded"
      }
    }
    return null
  }

  private fun extractImportantItems(vararg contents: String?): List<String> {
    val items = linkedSetOf<String>()
    contents.forEach { raw ->
      IMPORTANT_ITEM_REGEX.findAll(raw.orEmpty()).forEach { match ->
        sanitizeItemValue(match.groupValues[1])?.let(items::add)
      }
    }
    return items.take(3)
  }

  private fun extractActiveTopic(userContent: String, assistantContent: String?): String? {
    if (userContent.contains("?")) {
      return sanitizeContent(userContent.substringBefore("?") + "?").take(96)
    }
    listOf(userContent, assistantContent.orEmpty()).forEach { raw ->
      val sentence = firstSentenceContaining(raw, ACTIVE_TOPIC_PATTERNS) ?: return@forEach
      return sentence.take(96)
    }
    return sanitizeContent(userContent).take(96).takeIf { it.length >= 8 }
  }

  private fun extractPresentEntities(
    roleName: String,
    activeEntities: JsonObject,
    userContent: String,
    assistantContent: String?,
  ): List<String> {
    val entities = linkedSetOf<String>()
    entities.add("user")
    entities.add(roleName)

    listOf(userContent, assistantContent.orEmpty()).forEach { raw ->
      TITLE_CASE_ENTITY_REGEX.findAll(raw).forEach { match ->
        sanitizeEntityValue(match.value)?.let { candidate ->
          if (!candidate.equals(roleName, ignoreCase = true) && candidate.lowercase() !in ENTITY_STOP_WORDS) {
            entities.add(candidate)
          }
        }
      }
    }

    if (entities.size <= 2) {
      activeEntities.getStringArray("present")
        .filterNot { existing ->
          existing.equals("user", ignoreCase = true) || existing.equals(roleName, ignoreCase = true)
        }.take(2)
        .forEach(entities::add)
    }

    return entities.take(4)
  }

  private fun updateRelationshipState(
    relationship: JsonObject,
    userMessage: Message,
    assistantMessage: Message?,
    currentMood: String?,
    dangerLevel: String?,
  ) {
    val userText = userMessage.content.lowercase()
    val assistantText = assistantMessage?.content.orEmpty().lowercase()
    val combined = listOf(userText, assistantText).filter(String::isNotBlank).joinToString(separator = " ")

    relationship.updateScalar("trust", defaultValue = 2, delta = detectTrustDelta(combined))
    relationship.updateScalar("intimacy", defaultValue = 1, delta = detectIntimacyDelta(combined))
    relationship.updateScalar(
      "tension",
      defaultValue = 1,
      delta = detectTensionDelta(combined = combined, currentMood = currentMood, dangerLevel = dangerLevel),
    )
    relationship.updateScalar("dependence", defaultValue = 1, delta = detectDependenceDelta(combined))
    relationship.updateScalar(
      "initiative",
      defaultValue = 2,
      delta = detectInitiativeDelta(userText = userText, assistantText = assistantText),
    )
    relationship.updateScalar("respect", defaultValue = 2, delta = detectRespectDelta(combined))
    relationship.updateScalar(
      "fear",
      defaultValue = 1,
      delta = detectFearDelta(combined = combined, currentMood = currentMood, dangerLevel = dangerLevel),
    )
  }

  private fun detectTrustDelta(combined: String): Int {
    return when {
      combined.containsAny(TRUST_NEGATIVE_PATTERNS) -> -1
      combined.containsAny(TRUST_POSITIVE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun detectIntimacyDelta(combined: String): Int {
    return when {
      combined.containsAny(INTIMACY_NEGATIVE_PATTERNS) -> -1
      combined.containsAny(INTIMACY_POSITIVE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun detectTensionDelta(combined: String, currentMood: String?, dangerLevel: String?): Int {
    return when {
      combined.containsAny(TENSION_RELIEF_PATTERNS) || currentMood in listOf("calm", "positive") -> -1
      dangerLevel in listOf("critical", "high") || currentMood in listOf("tense", "angry") -> 1
      combined.containsAny(TENSION_PRESSURE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun detectDependenceDelta(combined: String): Int {
    return when {
      combined.containsAny(DEPENDENCE_NEGATIVE_PATTERNS) -> -1
      combined.containsAny(DEPENDENCE_POSITIVE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun detectInitiativeDelta(userText: String, assistantText: String): Int {
    return when {
      assistantText.containsAny(INITIATIVE_POSITIVE_PATTERNS) -> 1
      assistantText.isBlank() -> 0
      userText.containsAny(INITIATIVE_NEGATIVE_PATTERNS) -> -1
      else -> 0
    }
  }

  private fun detectRespectDelta(combined: String): Int {
    return when {
      combined.containsAny(RESPECT_NEGATIVE_PATTERNS) -> -1
      combined.containsAny(RESPECT_POSITIVE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun detectFearDelta(combined: String, currentMood: String?, dangerLevel: String?): Int {
    return when {
      combined.containsAny(FEAR_RELIEF_PATTERNS) || currentMood in listOf("calm", "positive") -> -1
      dangerLevel in listOf("critical", "high") || currentMood == "tense" || combined.containsAny(FEAR_POSITIVE_PATTERNS) -> 1
      else -> 0
    }
  }

  private fun splitIntoCandidates(content: String): List<String> {
    return content
      .split(SPLIT_REGEX)
      .map(::sanitizeContent)
      .filter { it.length in 12..MAX_MEMORY_LENGTH }
  }

  private fun firstSentenceContaining(content: String, patterns: List<String>): String? {
    return content
      .split(SPLIT_REGEX)
      .map(::sanitizeContent)
      .firstOrNull { line -> line.lowercase().containsAny(patterns) && line.length >= 8 }
  }

  private fun sanitizeContent(content: String): String {
    return content.trim().replace(WHITESPACE_REGEX, " ").take(MAX_MEMORY_LENGTH)
  }

  private suspend fun applyUserCorrections(
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

  private fun extractPreferenceCorrection(content: String): ExtractedPreferenceCorrection? {
    val sanitized = sanitizeContent(content)
    if (sanitized.isBlank()) {
      return null
    }

    PREFERENCE_CORRECTION_REGEXES.forEach { regex ->
      val match = regex.find(sanitized) ?: return@forEach
      val preferredValue = sanitizePreferenceValue(match.groupValues[1])
      val rejectedValue = sanitizePreferenceValue(match.groupValues[2])
      if (preferredValue == null || rejectedValue == null || preferredValue == rejectedValue) {
        return@forEach
      }
      return ExtractedPreferenceCorrection(
        rejectedRawValue = rejectedValue,
        rejectedNormalizedValue = normalizeForHash(rejectedValue),
        replacementCandidate =
          MemoryCandidate(
            category = MemoryCategory.PREFERENCE,
            content = "I prefer $preferredValue.",
            confidence = 0.96f,
            fromAssistant = false,
          ),
      )
    }
    return null
  }

  private fun extractLocationCorrection(content: String): String? {
    val match = LOCATION_CORRECTION_REGEX.find(content) ?: return null
    return sanitizeLocationValue(match.groupValues[2])
  }

  private fun sanitizePreferenceValue(value: String): String? {
    return sanitizeContent(
      value
        .trim()
        .trim('.', ',', ';', ':')
        .removePrefix("the ")
    ).ifBlank { null }
  }

  private fun sanitizeLocationValue(value: String): String? {
    return normalizeLocationValue(value).ifBlank { null }
  }

  private fun normalizeLocationValue(value: String): String {
    return sanitizeContent(
      value
        .trim()
        .trim('.', ',', ';', ':')
        .replace(TRAILING_LOCATION_TIME_REGEX, "")
        .trim(),
    )
  }

  private fun sanitizeItemValue(value: String): String? {
    val normalized =
      sanitizeContent(
      value
        .trim()
        .trim('.', ',', ';', ':')
        .removePrefix("the ")
        .removePrefix("our ")
        .removePrefix("my ")
        .removePrefix("your "),
    )
    val tokens = normalized.split(WHITESPACE_REGEX).filter(String::isNotBlank)
    if (tokens.isEmpty()) {
      return null
    }
    val keywordIndex =
      tokens.indexOfLast { token ->
        token.lowercase() in IMPORTANT_ITEM_KEYWORDS
      }
    if (keywordIndex == -1) {
      return normalized.ifBlank { null }
    }
    val selectedTokens = mutableListOf(tokens[keywordIndex])
    var index = keywordIndex - 1
    while (index >= 0 && selectedTokens.size < 3) {
      val token = tokens[index]
      val normalizedToken = token.lowercase()
      when {
        normalizedToken in ITEM_DETERMINERS -> {
          index -= 1
          continue
        }
        normalizedToken in ITEM_DESCRIPTOR_STOP_WORDS -> break
        else -> selectedTokens.add(0, token)
      }
      index -= 1
    }
    return selectedTokens.joinToString(separator = " ").ifBlank { null }
  }

  private fun sanitizeEntityValue(value: String): String? {
    return sanitizeContent(value.trim().trim('.', ',', ';', ':')).ifBlank { null }
  }

  private fun normalizeForHash(content: String): String {
    return sanitizeContent(content).lowercase()
  }

  private fun hashContent(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(normalizeForHash(content).toByteArray()).joinToString(separator = "") { byte ->
      "%02x".format(byte)
    }
  }

  private fun String?.toMutableJsonObject(): JsonObject {
    if (this.isNullOrBlank()) {
      return JsonObject()
    }
    return runCatching { JsonParser.parseString(this).asJsonObject.deepCopy() }.getOrElse { JsonObject() }
  }

  private fun JsonObject.getStringArray(field: String): List<String> {
    val element = get(field) ?: return emptyList()
    if (!element.isJsonArray) {
      return emptyList()
    }
    return element.asJsonArray.mapNotNull { value ->
      value.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
    }
  }

  private fun JsonObject.putStringArray(field: String, values: List<String>) {
    add(
      field,
      JsonArray().apply {
        values.filter(String::isNotBlank).forEach(::add)
      },
    )
  }

  private fun JsonObject.updateScalar(field: String, defaultValue: Int, delta: Int) {
    val currentValue = get(field)?.takeIf { it.isJsonPrimitive }?.asInt ?: defaultValue
    addProperty(field, (currentValue + delta).coerceIn(0, 5))
  }

  private suspend fun appendEvent(sessionId: String, eventType: SessionEventType, payload: JsonObject) {
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

  private suspend fun appendMemoryUpsertEvent(
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

  private fun debugLog(message: String) {
    runCatching {
      Log.d(TAG, message)
    }
  }

  private fun String.containsAny(patterns: List<String>): Boolean {
    return patterns.any(::contains)
  }

  private fun RuntimeStateSnapshot?.extractSceneField(field: String): String? {
    val snapshot = this ?: return null
    val scene = runCatching { JsonParser.parseString(snapshot.sceneJson).asJsonObject }.getOrNull() ?: return null
    return scene.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
  }

  private fun MemoryCandidate.atomKey(): String {
    return memoryAtomKey(toSubject(), toPredicate(), normalizeForHash(content), toNamespace())
  }

  private fun MemoryAtom.hasSameAtomKey(candidate: MemoryCandidate): Boolean {
    return memoryAtomKey(subject, predicate, normalizedObjectValue, namespace) == candidate.atomKey()
  }

  private fun MemoryCandidate.toNamespace(): MemoryNamespace {
    return when (category) {
      MemoryCategory.PREFERENCE,
      MemoryCategory.RELATION,
      MemoryCategory.RULE -> MemoryNamespace.SEMANTIC
      MemoryCategory.WORLD -> MemoryNamespace.WORLD
      MemoryCategory.PLOT -> MemoryNamespace.EPISODIC
      MemoryCategory.TODO -> MemoryNamespace.PROMISE
    }
  }

  private fun MemoryCandidate.toSubject(): String {
    return when (category) {
      MemoryCategory.PREFERENCE -> "user"
      MemoryCategory.RELATION -> "relationship"
      MemoryCategory.WORLD -> "world"
      MemoryCategory.PLOT -> "scene"
      MemoryCategory.TODO -> "thread"
      MemoryCategory.RULE -> "rule"
    }
  }

  private fun MemoryCandidate.toPredicate(): String {
    return when (category) {
      MemoryCategory.PREFERENCE -> "preference"
      MemoryCategory.RELATION -> "state"
      MemoryCategory.WORLD -> "fact"
      MemoryCategory.PLOT -> "event"
      MemoryCategory.TODO -> "pending"
      MemoryCategory.RULE -> "constraint"
    }
  }

  private fun MemoryCandidate.toPlane(): MemoryPlane {
    return when (category) {
      MemoryCategory.WORLD,
      MemoryCategory.PLOT,
      MemoryCategory.RELATION,
      MemoryCategory.TODO -> MemoryPlane.IC
      MemoryCategory.PREFERENCE,
      MemoryCategory.RULE -> MemoryPlane.SHARED
    }
  }

  private fun MemoryCandidate.toSalience(): Float {
    return when (category) {
      MemoryCategory.TODO -> 0.95f
      MemoryCategory.RELATION -> 0.9f
      MemoryCategory.PREFERENCE -> 0.86f
      MemoryCategory.WORLD -> 0.82f
      MemoryCategory.RULE -> 0.88f
      MemoryCategory.PLOT -> 0.78f
    }
  }

  private data class MemoryCandidate(
    val category: MemoryCategory,
    val content: String,
    val confidence: Float,
    val fromAssistant: Boolean,
  )

  private data class ExtractedPreferenceCorrection(
    val rejectedRawValue: String,
    val rejectedNormalizedValue: String,
    val replacementCandidate: MemoryCandidate,
  )

  private data class CorrectionResult(
    val correctedLocation: String? = null,
  )

  private data class OpenThreadCandidate(
    val type: OpenThreadType,
    val owner: OpenThreadOwner,
    val content: String,
    val priority: Int,
    val sourceMessageIds: List<String>,
  )

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val SPLIT_REGEX = Regex("[\\n.!?]")
    private val LOCATION_REGEX = Regex("\\b(?:at|in|inside|outside|near|on)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})")
    private val TRAILING_LOCATION_TIME_REGEX =
      Regex("\\b(?:tonight|today|tomorrow|right now|this morning|this afternoon|this evening|before dawn|by dawn|at dawn|at dusk|at night)\\b.*$", RegexOption.IGNORE_CASE)
    private val LOCATION_CORRECTION_REGEX =
      Regex("\\b(?:we are|we're|i am|i'm)\\s+not\\s+(?:at|in)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})[,.;\\s]+(?:we are|we're|i am|i'm)\\s+(?:at|in)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})", RegexOption.IGNORE_CASE)
    private val TITLE_CASE_ENTITY_REGEX =
      Regex("\\b[A-Z][A-Za-z0-9'\\-]{1,}(?:\\s+[A-Z][A-Za-z0-9'\\-]{1,}){0,2}\\b")
    private val IMPORTANT_ITEM_REGEX =
      Regex("\\b(?:the|a|an|our|my|your)?\\s*([A-Za-z][A-Za-z0-9'\\-]*(?:\\s+[A-Za-z0-9'\\-]+){0,2}\\s+(?:code|key|pass|map|badge|beacon|artifact|device|weapon|dossier|letter|file|ring|amulet|token|book|note))(?!\\s+(?:code|key|pass|map|badge|beacon|artifact|device|weapon|dossier|letter|file|ring|amulet|token|book|note))\\b", RegexOption.IGNORE_CASE)
    private val PREFERENCE_CORRECTION_REGEXES =
      listOf(
        Regex("\\bi\\s+(?:prefer|like|love|want)\\s+(.+?)\\s+(?:instead of|rather than|over)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
        Regex("\\bi\\s+(?:do not|don't)\\s+(?:like|love|want)\\s+(.+?)[,;]\\s*i\\s+(?:prefer|like|love|want)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
        Regex("\\bnot\\s+(.+?)[,;]\\s*i\\s+(?:prefer|like|love|want)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
      )
    private val PREFERENCE_PATTERNS =
      listOf("i like", "i love", "i enjoy", "i prefer", "my favorite", "i hate", "i dislike")
    private val RELATION_PATTERNS =
      listOf("we are", "we're", "you promised", "our bond", "our relationship", "trust me")
    private val PLOT_PATTERNS =
      listOf("we need to", "our mission", "the plan is", "remember that", "the goal is")
    private val WORLD_PATTERNS =
      listOf("my name is", "call me", "i live", "i work", "i study", "i am from", "i'm from")
    private val TASK_PATTERNS = listOf("we need to", "have to", "must", "our mission", "the plan is", "goal is")
    private val PROMISE_PATTERNS = listOf("i will", "i'll", "we will", "we'll", "promise")
    private val MYSTERY_PATTERNS = listOf("don't know", "do not know", "what happened", "who is", "why is", "where is")
    private val EMOTIONAL_PATTERNS = listOf("worried", "afraid", "upset", "angry", "sad", "hurt")
    private val GOAL_PATTERNS = listOf("we need to", "our mission", "the plan is", "the goal is", "must")
    private val ACTIVE_TOPIC_PATTERNS =
      listOf("why", "who", "where", "remember", "need to", "plan", "goal", "code", "key", "promise", "danger")
    private val SCENE_TIME_PATTERNS =
      listOf(
        "before dawn" to "before dawn",
        "by dawn" to "before dawn",
        "at dawn" to "dawn",
        "sunrise" to "sunrise",
        "morning" to "morning",
        "afternoon" to "afternoon",
        "evening" to "evening",
        "sunset" to "sunset",
        "tonight" to "tonight",
        "midnight" to "midnight",
        "night" to "night",
      )
    private val DANGER_CRITICAL_PATTERNS =
      listOf("under attack", "bleeding", "kill", "die", "explod", "alarm", "ambush", "gun", "knife", "fire")
    private val DANGER_HIGH_PATTERNS =
      listOf("danger", "threat", "risk", "chase", "closing in", "tampered", "trap", "hostile", "afraid", "worried")
    private val DANGER_GUARDED_PATTERNS = listOf("careful", "cautious", "tense", "uneasy", "watch out")
    private val TRUST_POSITIVE_PATTERNS =
      listOf("trust you", "trust me", "count on", "watch your back", "i will protect", "i'll protect", "i will help", "i'll help")
    private val TRUST_NEGATIVE_PATTERNS =
      listOf("can't trust", "cannot trust", "do not trust", "don't trust", "betray", "deceived", "lied", "ignored me")
    private val INTIMACY_POSITIVE_PATTERNS =
      listOf("stay with you", "glad you're here", "care about you", "hold you", "hug", "kiss", "close to you")
    private val INTIMACY_NEGATIVE_PATTERNS = listOf("stay away", "back off", "leave me alone", "keep your distance", "don't touch")
    private val TENSION_PRESSURE_PATTERNS =
      listOf("argument", "fight", "pressure", "deadline", "hurry", "closing in", "threat", "alarm")
    private val TENSION_RELIEF_PATTERNS = listOf("resolved", "safe now", "steady now", "we're clear", "all clear", "relieved")
    private val DEPENDENCE_POSITIVE_PATTERNS =
      listOf("need you", "can't do this without", "cannot do this without", "rely on you", "help me", "cover me")
    private val DEPENDENCE_NEGATIVE_PATTERNS =
      listOf("i can handle it alone", "i don't need you", "i do not need you", "without your help")
    private val INITIATIVE_POSITIVE_PATTERNS =
      listOf("i will", "i'll", "let me", "follow me", "move now", "stay behind me", "i can handle")
    private val INITIATIVE_NEGATIVE_PATTERNS = listOf("you need to", "you must", "go do", "listen to me", "remember this")
    private val RESPECT_POSITIVE_PATTERNS =
      listOf("you're right", "you are right", "good call", "smart", "impressive", "captain", "sir", "ma'am", "maam")
    private val RESPECT_NEGATIVE_PATTERNS = listOf("idiot", "stupid", "useless", "pathetic", "incompetent", "fool")
    private val FEAR_POSITIVE_PATTERNS =
      listOf("afraid", "scared", "terrified", "panic", "danger", "threat", "under attack", "bleeding")
    private val FEAR_RELIEF_PATTERNS = listOf("safe now", "all clear", "steady now", "calm down", "we made it")
    private val ENTITY_STOP_WORDS =
      setOf("we", "i", "you", "the", "a", "an", "this", "that", "tonight", "tomorrow", "today", "dawn")
    private val IMPORTANT_ITEM_KEYWORDS =
      setOf("code", "key", "pass", "map", "badge", "beacon", "artifact", "device", "weapon", "dossier", "letter", "file", "ring", "amulet", "token", "book", "note")
    private val ITEM_DETERMINERS = setOf("the", "our", "my", "your", "a", "an")
    private val ITEM_DESCRIPTOR_STOP_WORDS =
      setOf("trade", "keep", "carry", "take", "bring", "find", "grab", "hold", "use", "need", "remember", "about", "with", "before", "after", "to", "so")
    private val THREAD_RESOLUTION_PATTERNS =
      listOf("resolved", "solved", "done", "finished", "answered", "figured out", "no longer", "never mind")
    private val THREAD_STOP_WORDS = setOf("what", "when", "where", "with", "have", "this", "that", "from")

    private fun memoryAtomKey(
      subject: String,
      predicate: String,
      normalizedObjectValue: String,
      namespace: MemoryNamespace,
    ): String {
      return listOf(namespace.name, subject, predicate, normalizedObjectValue).joinToString(separator = "::")
    }
  }
}
