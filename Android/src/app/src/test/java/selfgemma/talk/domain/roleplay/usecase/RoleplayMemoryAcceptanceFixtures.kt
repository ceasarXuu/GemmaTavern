package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
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
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadStatus
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.testing.FakeDataStoreRepository

internal data class RoleplayMemoryAcceptanceFixture(
  val session: Session,
  val role: RoleCard,
  val conversationRepository: AcceptanceConversationRepository,
  val roleRepository: AcceptanceRoleRepository,
  val runtimeStateRepository: AcceptanceRuntimeStateRepository,
  val memoryAtomRepository: AcceptanceMemoryAtomRepository,
  val openThreadRepository: AcceptanceOpenThreadRepository,
  val memoryRepository: AcceptanceMemoryRepository,
  val compactionCacheRepository: AcceptanceCompactionCacheRepository,
  val tokenEstimator: TokenEstimator,
  val promptAssembler: PromptAssembler,
  val extractMemoriesUseCase: ExtractMemoriesUseCase,
  val summarizeSessionUseCase: SummarizeSessionUseCase,
  val compileMemoryContextUseCase: CompileRoleplayMemoryContextUseCase,
  val rebuildContinuityUseCase: RebuildRoleplayContinuityUseCase,
  val rollbackContinuityUseCase: RollbackRoleplayContinuityUseCase,
)

internal fun createRoleplayMemoryAcceptanceFixture(
  session: Session = acceptanceSession(),
  role: RoleCard = acceptanceRole(roleId = session.roleId),
  messages: List<Message> = emptyList(),
  summary: SessionSummary? = null,
  runtimeState: RuntimeStateSnapshot? = null,
  openThreads: List<OpenThread> = emptyList(),
  memoryAtoms: List<MemoryAtom> = emptyList(),
  legacyMemories: List<MemoryItem> = emptyList(),
  compactionEntries: List<CompactionCacheEntry> = emptyList(),
  userProfile: StUserProfile = StUserProfile(personas = mapOf("captain" to "Captain Mae")),
): RoleplayMemoryAcceptanceFixture {
  val conversationRepository =
    AcceptanceConversationRepository(
      session = session,
      initialMessages = messages,
      initialSummary = summary,
    )
  val roleRepository = AcceptanceRoleRepository(role)
  val runtimeStateRepository = AcceptanceRuntimeStateRepository(runtimeState)
  val memoryAtomRepository = AcceptanceMemoryAtomRepository(memoryAtoms)
  val openThreadRepository = AcceptanceOpenThreadRepository(openThreads)
  val memoryRepository = AcceptanceMemoryRepository(legacyMemories)
  val compactionCacheRepository = AcceptanceCompactionCacheRepository(compactionEntries)
  val tokenEstimator = TokenEstimator()
  val promptAssembler = PromptAssembler(tokenEstimator)
  val extractMemoriesUseCase =
    ExtractMemoriesUseCase(
      memoryRepository = memoryRepository,
      memoryAtomRepository = memoryAtomRepository,
      openThreadRepository = openThreadRepository,
      runtimeStateRepository = runtimeStateRepository,
      conversationRepository = conversationRepository,
      validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
    )
  val summarizeSessionUseCase =
    SummarizeSessionUseCase(
      dataStoreRepository = FakeDataStoreRepository(stUserProfile = userProfile),
      conversationRepository = conversationRepository,
      compactionCacheRepository = compactionCacheRepository,
      tokenEstimator = tokenEstimator,
    )
  val compileMemoryContextUseCase =
    CompileRoleplayMemoryContextUseCase(
      conversationRepository = conversationRepository,
      runtimeStateRepository = runtimeStateRepository,
      openThreadRepository = openThreadRepository,
      memoryAtomRepository = memoryAtomRepository,
      memoryRepository = memoryRepository,
      compactionCacheRepository = compactionCacheRepository,
      tokenEstimator = tokenEstimator,
    )
  val rebuildContinuityUseCase =
    RebuildRoleplayContinuityUseCase(
      conversationRepository = conversationRepository,
      roleRepository = roleRepository,
      runtimeStateRepository = runtimeStateRepository,
      memoryAtomRepository = memoryAtomRepository,
      openThreadRepository = openThreadRepository,
      compactionCacheRepository = compactionCacheRepository,
      extractMemoriesUseCase = extractMemoriesUseCase,
      summarizeSessionUseCase = summarizeSessionUseCase,
    )
  return RoleplayMemoryAcceptanceFixture(
    session = session,
    role = role,
    conversationRepository = conversationRepository,
    roleRepository = roleRepository,
    runtimeStateRepository = runtimeStateRepository,
    memoryAtomRepository = memoryAtomRepository,
    openThreadRepository = openThreadRepository,
    memoryRepository = memoryRepository,
    compactionCacheRepository = compactionCacheRepository,
    tokenEstimator = tokenEstimator,
    promptAssembler = promptAssembler,
    extractMemoriesUseCase = extractMemoriesUseCase,
    summarizeSessionUseCase = summarizeSessionUseCase,
    compileMemoryContextUseCase = compileMemoryContextUseCase,
    rebuildContinuityUseCase = rebuildContinuityUseCase,
    rollbackContinuityUseCase =
      RollbackRoleplayContinuityUseCase(
        conversationRepository = conversationRepository,
        rebuildRoleplayContinuityUseCase = rebuildContinuityUseCase,
      ),
  )
}

internal fun acceptanceSession(
  sessionId: String = "session-1",
  roleId: String = "role-1",
  title: String = "Observatory Run",
  activeModelId: String = "gemma-3n",
  turnCount: Int = 8,
  now: Long = System.currentTimeMillis(),
): Session =
  Session(
    id = sessionId,
    roleId = roleId,
    title = title,
    activeModelId = activeModelId,
    createdAt = now,
    updatedAt = now,
    lastMessageAt = now,
    turnCount = turnCount,
  )

internal fun acceptanceRole(
  roleId: String = "role-1",
  name: String = "Captain Astra",
  summary: String = "A disciplined starship captain.",
  systemPrompt: String = "Stay focused on continuity and tactical realism.",
  memoryMaxItems: Int = 4,
  now: Long = System.currentTimeMillis(),
): RoleCard =
  RoleCard(
    id = roleId,
    name = name,
    summary = summary,
    systemPrompt = systemPrompt,
    memoryEnabled = true,
    memoryMaxItems = memoryMaxItems,
    createdAt = now,
    updatedAt = now,
  )

internal fun acceptanceMessage(
  id: String,
  sessionId: String,
  seq: Int,
  side: MessageSide,
  content: String,
  now: Long,
  status: MessageStatus = MessageStatus.COMPLETED,
  accepted: Boolean = true,
  isCanonical: Boolean = true,
  branchId: String = "main",
): Message =
  Message(
    id = id,
    sessionId = sessionId,
    seq = seq,
    branchId = branchId,
    side = side,
    status = status,
    accepted = accepted,
    isCanonical = isCanonical,
    content = content,
    createdAt = now,
    updatedAt = now,
  )

internal fun acceptanceOpenThread(
  id: String,
  sessionId: String,
  type: OpenThreadType,
  content: String,
  priority: Int,
  now: Long,
  owner: OpenThreadOwner = OpenThreadOwner.SHARED,
  status: OpenThreadStatus = OpenThreadStatus.OPEN,
  sourceMessageIds: List<String> = listOf("assistant-2"),
): OpenThread =
  OpenThread(
    id = id,
    sessionId = sessionId,
    type = type,
    content = content,
    owner = owner,
    priority = priority,
    status = status,
    sourceMessageIds = sourceMessageIds,
    createdAt = now,
    updatedAt = now,
  )

internal fun acceptanceMemoryAtom(
  id: String,
  sessionId: String,
  roleId: String,
  subject: String,
  predicate: String,
  objectValue: String,
  evidenceQuote: String,
  now: Long,
  namespace: MemoryNamespace = MemoryNamespace.PROMISE,
  stability: MemoryStability = MemoryStability.STABLE,
  salience: Float = 0.92f,
): MemoryAtom =
  MemoryAtom(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = MemoryPlane.CANON,
    namespace = namespace,
    subject = subject,
    predicate = predicate,
    objectValue = objectValue,
    normalizedObjectValue = objectValue.lowercase(),
    stability = stability,
    epistemicStatus = MemoryEpistemicStatus.OBSERVED,
    salience = salience,
    confidence = 0.92f,
    branchScope = MemoryBranchScope.ACCEPTED_ONLY,
    sourceMessageIds = listOf("assistant-2"),
    evidenceQuote = evidenceQuote,
    createdAt = now,
    updatedAt = now,
  )

internal fun acceptanceLegacyMemory(
  id: String,
  roleId: String,
  sessionId: String,
  content: String,
  now: Long,
  category: MemoryCategory = MemoryCategory.PLOT,
): MemoryItem =
  MemoryItem(
    id = id,
    roleId = roleId,
    sessionId = sessionId,
    category = category,
    content = content,
    normalizedHash = content.lowercase(),
    confidence = 0.82f,
    createdAt = now,
    updatedAt = now,
  )

internal fun acceptanceCompactionEntry(
  id: String,
  sessionId: String,
  rangeStartMessageId: String,
  rangeEndMessageId: String,
  compactText: String,
  tokenEstimate: Int,
  now: Long,
  summaryType: CompactionSummaryType = CompactionSummaryType.SCENE,
): CompactionCacheEntry =
  CompactionCacheEntry(
    id = id,
    sessionId = sessionId,
    rangeStartMessageId = rangeStartMessageId,
    rangeEndMessageId = rangeEndMessageId,
    summaryType = summaryType,
    compactText = compactText,
    sourceHash = "$sessionId:$rangeStartMessageId:$rangeEndMessageId",
    tokenEstimate = tokenEstimate,
    updatedAt = now,
  )

internal class AcceptanceConversationRepository(
  session: Session,
  initialMessages: List<Message>,
  initialSummary: SessionSummary? = null,
) : ConversationRepository {
  private val sessions = MutableStateFlow(listOf(session))
  private val messages = MutableStateFlow(initialMessages.sortedBy(Message::seq))
  private var summary: SessionSummary? = initialSummary
  val events = mutableListOf<SessionEvent>()

  val storedMessages: List<Message>
    get() = messages.value

  override fun observeSessions(): Flow<List<Session>> = sessions

  override fun observeMessages(sessionId: String): Flow<List<Message>> =
    flowOf(messages.value.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> =
    messages.value.filter { it.sessionId == sessionId }.sortedBy(Message::seq)

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    listMessages(sessionId).filter { it.isCanonical }

  override suspend fun getMessage(messageId: String): Message? =
    messages.value.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? =
    sessions.value.firstOrNull { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    val now = System.currentTimeMillis()
    val session =
      Session(
        id = UUID.randomUUID().toString(),
        roleId = roleId,
        title = "Acceptance Session",
        activeModelId = modelId,
        createdAt = now,
        updatedAt = now,
        lastMessageAt = now,
        sessionUserProfile = userProfile,
      )
    sessions.value = sessions.value + session
    return session
  }

  override suspend fun updateSession(session: Session) {
    sessions.value = sessions.value.map { current -> if (current.id == session.id) session else current }
  }

  override suspend fun archiveSession(sessionId: String) {
    sessions.value =
      sessions.value.map { session -> if (session.id == sessionId) session.copy(archived = true) else session }
  }

  override suspend fun deleteSession(sessionId: String) {
    sessions.value = sessions.value.filterNot { it.id == sessionId }
    messages.value = messages.value.filterNot { it.sessionId == sessionId }
    summary = summary?.takeUnless { it.sessionId == sessionId }
    events.removeAll { it.sessionId == sessionId }
  }

  override suspend fun appendMessage(message: Message) {
    messages.value = (messages.value + message).sortedBy(Message::seq)
  }

  override suspend fun updateMessage(message: Message) {
    messages.value = messages.value.map { current -> if (current.id == message.id) message else current }.sortedBy(Message::seq)
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    val acceptedMessage =
      messages.value.firstOrNull { it.id == messageId }?.copy(
        accepted = true,
        isCanonical = true,
        updatedAt = acceptedAt,
      ) ?: return null
    updateMessage(acceptedMessage)
    return acceptedMessage
  }

  override suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messages.value.firstOrNull { it.id == targetMessageId && it.sessionId == sessionId } ?: return 0
    var rolledBackCount = 0
    messages.value =
      messages.value.map { message ->
        if (
          message.sessionId != sessionId ||
            !message.accepted ||
            !message.isCanonical ||
            message.seq <= target.seq
        ) {
          message
        } else {
          rolledBackCount += 1
          message.copy(
            accepted = false,
            isCanonical = false,
            branchId = rollbackBranchId,
            updatedAt = updatedAt,
          )
        }
      }.sortedBy(Message::seq)
    return rolledBackCount
  }

  override suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messages.value.firstOrNull { it.id == targetMessageId && it.sessionId == sessionId } ?: return 0
    var rolledBackCount = 0
    messages.value =
      messages.value.map { message ->
        if (
          message.sessionId != sessionId ||
            !message.accepted ||
            !message.isCanonical ||
            message.seq < target.seq
        ) {
          message
        } else {
          rolledBackCount += 1
          message.copy(
            accepted = false,
            isCanonical = false,
            branchId = rollbackBranchId,
            updatedAt = updatedAt,
          )
        }
      }.sortedBy(Message::seq)
    return rolledBackCount
  }

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    this.messages.value =
      this.messages.value.filterNot { it.sessionId == sessionId } + messages.sortedBy(Message::seq)
  }

  override suspend fun nextMessageSeq(sessionId: String): Int =
    (messages.value.filter { it.sessionId == sessionId }.maxOfOrNull(Message::seq) ?: 0) + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? =
    summary?.takeIf { it.sessionId == sessionId }

  override suspend fun upsertSummary(summary: SessionSummary) {
    this.summary = summary
  }

  override suspend fun deleteSummary(sessionId: String) {
    summary = summary?.takeUnless { it.sessionId == sessionId }
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> =
    events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

internal class AcceptanceRoleRepository(
  private var role: RoleCard,
) : RoleRepository {
  val savedRoles = mutableListOf<RoleCard>()

  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) {
    this.role = role
    savedRoles += role
  }

  override suspend fun deleteRole(roleId: String) = Unit
}

internal class AcceptanceRuntimeStateRepository(
  var snapshot: RuntimeStateSnapshot? = null,
) : RuntimeStateRepository {
  val deletedSessionIds = mutableListOf<String>()

  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? =
    snapshot?.takeIf { it.sessionId == sessionId }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun deleteBySession(sessionId: String) {
    deletedSessionIds += sessionId
    snapshot = snapshot?.takeUnless { it.sessionId == sessionId }
  }
}

internal class AcceptanceMemoryAtomRepository(
  atoms: List<MemoryAtom> = emptyList(),
) : MemoryAtomRepository {
  private val storedAtoms = atoms.toMutableList()
  val markedUsedIds = mutableListOf<String>()
  val tombstonedSessionIds = mutableListOf<String>()

  val atoms: List<MemoryAtom>
    get() = storedAtoms.toList()

  override suspend fun listBySession(sessionId: String): List<MemoryAtom> =
    storedAtoms.filter { it.sessionId == sessionId }

  override suspend fun upsert(atom: MemoryAtom) {
    storedAtoms.removeAll { it.id == atom.id }
    storedAtoms += atom
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    markedUsedIds += memoryIds
    storedAtoms.replaceAll { atom ->
      if (atom.id !in memoryIds) {
        atom
      } else {
        atom.copy(lastUsedAt = usedAt, updatedAt = usedAt)
      }
    }
  }

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    storedAtoms.replaceAll { atom ->
      if (atom.id != memoryId) {
        atom
      } else {
        atom.copy(tombstone = true, updatedAt = updatedAt)
      }
    }
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) {
    tombstonedSessionIds += sessionId
    storedAtoms.replaceAll { atom ->
      if (atom.sessionId != sessionId) {
        atom
      } else {
        atom.copy(tombstone = true, updatedAt = updatedAt)
      }
    }
  }

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> =
    storedAtoms
      .filter { it.sessionId == sessionId && it.roleId == roleId && !it.tombstone }
      .sortedWith(
        compareByDescending<MemoryAtom> {
          queryOverlap(
            query = query,
            text = "${it.subject} ${it.predicate} ${it.objectValue} ${it.evidenceQuote}",
          )
        }
          .thenByDescending { it.salience }
          .thenByDescending { it.updatedAt },
      )
      .take(limit)
}

internal class AcceptanceOpenThreadRepository(
  threads: List<OpenThread> = emptyList(),
) : OpenThreadRepository {
  private val storedThreads = threads.toMutableList()
  val deletedSessionIds = mutableListOf<String>()

  val threads: List<OpenThread>
    get() = storedThreads.toList()

  override suspend fun listBySession(sessionId: String): List<OpenThread> =
    storedThreads.filter { it.sessionId == sessionId }.sortedByDescending(OpenThread::priority)

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> =
    listBySession(sessionId).filter { it.status == status }

  override suspend fun upsert(thread: OpenThread) {
    storedThreads.removeAll { it.id == thread.id }
    storedThreads += thread
  }

  override suspend fun deleteBySession(sessionId: String) {
    deletedSessionIds += sessionId
    storedThreads.removeAll { it.sessionId == sessionId }
  }

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) {
    storedThreads.replaceAll { thread ->
      if (thread.id != threadId) {
        thread
      } else {
        thread.copy(
          status = status,
          resolvedByMessageId = resolvedByMessageId,
          updatedAt = updatedAt,
        )
      }
    }
  }
}

internal class AcceptanceMemoryRepository(
  memories: List<MemoryItem> = emptyList(),
) : MemoryRepository {
  private val storedMemories = memories.toMutableList()
  val markedUsedIds = mutableListOf<String>()

  val memories: List<MemoryItem>
    get() = storedMemories.toList()

  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> =
    storedMemories.filter { it.roleId == roleId && it.sessionId == null }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> =
    storedMemories.filter { it.sessionId == sessionId }

  override suspend fun upsert(memory: MemoryItem) {
    storedMemories.removeAll { it.id == memory.id }
    storedMemories += memory
  }

  override suspend fun deactivate(memoryId: String) {
    storedMemories.replaceAll { memory ->
      if (memory.id != memoryId) {
        memory
      } else {
        memory.copy(active = false)
      }
    }
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) {
    markedUsedIds += memoryIds
    storedMemories.replaceAll { memory ->
      if (memory.id !in memoryIds) {
        memory
      } else {
        memory.copy(lastUsedAt = usedAt, updatedAt = usedAt)
      }
    }
  }

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> =
    storedMemories
      .filter { it.roleId == roleId && it.active && (sessionId == null || it.sessionId == sessionId) }
      .sortedWith(
        compareByDescending<MemoryItem> { queryOverlap(query = query, text = it.content) }
          .thenByDescending { it.confidence }
          .thenByDescending { it.updatedAt },
      )
      .take(limit)
}

internal class AcceptanceCompactionCacheRepository(
  entries: List<CompactionCacheEntry> = emptyList(),
) : CompactionCacheRepository {
  private val storedEntries = entries.toMutableList()
  val deletedSessionIds = mutableListOf<String>()

  val entries: List<CompactionCacheEntry>
    get() = storedEntries.toList()

  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> =
    storedEntries.filter { it.sessionId == sessionId }.sortedByDescending(CompactionCacheEntry::updatedAt)

  override suspend fun upsert(entry: CompactionCacheEntry) {
    storedEntries.removeAll { it.id == entry.id }
    storedEntries += entry
  }

  override suspend fun deleteBySession(sessionId: String) {
    deletedSessionIds += sessionId
    storedEntries.removeAll { it.sessionId == sessionId }
  }
}

private val TERM_SPLIT_REGEX = Regex("[^a-z0-9]+")

private fun queryOverlap(query: String, text: String): Int {
  if (query.isBlank() || text.isBlank()) {
    return 0
  }
  val terms = query.lowercase().split(TERM_SPLIT_REGEX).filter { it.length >= 3 }.distinct()
  if (terms.isEmpty()) {
    return 0
  }
  val haystack = text.lowercase()
  return terms.count(haystack::contains)
}
