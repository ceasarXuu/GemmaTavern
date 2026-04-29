package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository

private const val MAX_AUTOMATIC_MEMORY_ITEMS = 3

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
        memoryAtomRepository = memoryAtomRepository,
        memoryRepository = memoryRepository,
        runtimeStateRepository = runtimeStateRepository,
        conversationRepository = conversationRepository,
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
        conversationRepository = conversationRepository,
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
            conversationRepository = conversationRepository,
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
            conversationRepository = conversationRepository,
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
        conversationRepository = conversationRepository,
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

    syncOpenThreads(
      openThreadRepository = openThreadRepository,
      conversationRepository = conversationRepository,
      session = session,
      userMessage = userMessage,
      assistantMessage = assistantMessage,
      now = now,
    )
    upsertRuntimeState(
      runtimeStateRepository = runtimeStateRepository,
      conversationRepository = conversationRepository,
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
    appendMemoryUpsertEvent(
      conversationRepository = conversationRepository,
      sessionId = session.id,
      memories = listOf(memory),
      source = "pinned",
    )
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
    appendMemoryUpsertEvent(
      conversationRepository = conversationRepository,
      sessionId = session.id,
      memories = listOf(memory),
      source = "manual",
    )
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
        conversationRepository = conversationRepository,
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
        conversationRepository = conversationRepository,
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

}
