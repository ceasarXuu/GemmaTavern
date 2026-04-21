package selfgemma.talk.domain.roleplay.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.Model
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
import selfgemma.talk.domain.roleplay.model.MessageKind
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
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.MemoryAtomRepository
import selfgemma.talk.domain.roleplay.repository.MemoryRepository
import selfgemma.talk.domain.roleplay.repository.OpenThreadRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.RuntimeStateRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class RoleplayContinuityUseCaseTest {
  @Test
  fun rebuild_clearsStaleContinuityAndReplaysCanonicalCompletedTurnsOnly() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "We need to reach the observatory before dawn. Why is the hatch open?",
                now = now,
              ),
              continuityMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "I will get us there. I am worried the hatch was tampered with.",
                now = now,
              ),
              continuityMessage(
                id = "user-3",
                sessionId = session.id,
                seq = 3,
                side = MessageSide.USER,
                content = "Remember the hatch code is blue delta.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-4",
                sessionId = session.id,
                seq = 4,
                side = MessageSide.ASSISTANT,
                status = MessageStatus.INTERRUPTED,
                content = "I will keep the blue delta code ready if we need it.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-branch",
                sessionId = session.id,
                seq = 5,
                side = MessageSide.ASSISTANT,
                isCanonical = false,
                accepted = false,
                content = "Superseded branch reply that should be ignored.",
                now = now,
              ),
            ),
        )
      val runtimeStateRepository =
        ContinuityRuntimeStateRepository(
          snapshot =
            RuntimeStateSnapshot(
              sessionId = session.id,
              sceneJson = """{"location":"stale bunker"}""",
              relationshipJson = """{"currentMood":"stale"}""",
              activeEntitiesJson = """["stale"]""",
              updatedAt = now,
              sourceMessageId = "stale-message",
            ),
        )
      val memoryAtomRepository =
        ContinuityMemoryAtomRepository(
          mutableListOf(
            continuityAtom(
              id = "stale-atom",
              sessionId = session.id,
              roleId = role.id,
              objectValue = "stale continuity artifact",
              now = now,
            ),
          ),
        )
      val openThreadRepository =
        ContinuityOpenThreadRepository(
          mutableListOf(
            continuityThread(
              id = "stale-thread",
              sessionId = session.id,
              content = "Stale unresolved thread",
              priority = 1,
              now = now,
            ),
          ),
        )
      val compactionCacheRepository =
        ContinuityCompactionCacheRepository(
          mutableListOf(
            CompactionCacheEntry(
              id = "stale-cache",
              sessionId = session.id,
              rangeStartMessageId = "user-1",
              rangeEndMessageId = "assistant-2",
              summaryType = CompactionSummaryType.SCENE,
              compactText = "old compacted text",
              sourceHash = "hash",
              tokenEstimate = 10,
              updatedAt = now,
            ),
          ),
        )
      conversationRepository.savedSummary =
        SessionSummary(
          sessionId = session.id,
          version = 7,
          coveredUntilSeq = 99,
          summaryText = "Stale summary that must be replaced.",
          tokenEstimate = 12,
          updatedAt = now,
        )
      val rebuildUseCase =
        continuityRebuildUseCase(
          conversationRepository = conversationRepository,
          role = role,
          runtimeStateRepository = runtimeStateRepository,
          memoryAtomRepository = memoryAtomRepository,
          openThreadRepository = openThreadRepository,
          compactionCacheRepository = compactionCacheRepository,
        )

      val result = rebuildUseCase(session.id)

      assertNotNull(result)
      assertEquals(session.id, result!!.sessionId)
      assertEquals(4, result.canonicalMessageCount)
      assertEquals(1, result.replayedTurnCount)
      assertTrue(runtimeStateRepository.deleteCalls.contains(session.id))
      assertTrue(openThreadRepository.deletedSessionIds.contains(session.id))
      assertTrue(memoryAtomRepository.tombstonedSessionIds.contains(session.id))
      assertTrue(compactionCacheRepository.deletedSessionIds.contains(session.id))

      val rebuiltSnapshot = runtimeStateRepository.getLatestSnapshot(session.id)
      assertNotNull(rebuiltSnapshot)
      assertEquals("assistant-2", rebuiltSnapshot!!.sourceMessageId)
      assertTrue(rebuiltSnapshot.sceneJson.contains("observatory"))

      val rebuiltThreads = openThreadRepository.listBySession(session.id)
      assertTrue(rebuiltThreads.none { it.id == "stale-thread" && it.status == OpenThreadStatus.OPEN })
      assertTrue(rebuiltThreads.none { it.type == OpenThreadType.QUESTION })
      assertTrue(rebuiltThreads.any { it.type == OpenThreadType.PROMISE })

      val atoms = memoryAtomRepository.listBySession(session.id)
      assertTrue(atoms.any { it.id == "stale-atom" && it.tombstone })
      assertTrue(atoms.any { !it.tombstone && it.objectValue.contains("reach the observatory before dawn") })
      assertFalse(atoms.any { !it.tombstone && it.objectValue.contains("blue delta") })

      val summary = conversationRepository.getSummary(session.id)
      assertNotNull(summary)
      assertEquals(1, summary!!.version)
      assertEquals(4, summary.coveredUntilSeq)
      assertTrue(summary.summaryText.contains("Recent developments:"))
      assertTrue(summary.summaryText.contains("Captain Mae: We need to reach the observatory before dawn. Why is the hatch open?"))

      assertTrue(compactionCacheRepository.listBySession(session.id).isEmpty())
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.SUMMARY_UPDATE })
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.MEMORY_REBUILD_COMPLETED })
    }

  @Test
  fun rollback_branchesFutureCanonicalMessagesAndRebuildsContinuityFromTarget() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "We need to reach the observatory before dawn.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "I will get us there.",
                now = now,
              ),
              continuityMessage(
                id = "user-3",
                sessionId = session.id,
                seq = 3,
                side = MessageSide.USER,
                content = "Remember the hatch code is blue delta.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-4",
                sessionId = session.id,
                seq = 4,
                side = MessageSide.ASSISTANT,
                content = "I will keep the blue delta code safe.",
                now = now,
              ),
            ),
        )
      val runtimeStateRepository = ContinuityRuntimeStateRepository()
      val memoryAtomRepository = ContinuityMemoryAtomRepository()
      val openThreadRepository = ContinuityOpenThreadRepository()
      val compactionCacheRepository = ContinuityCompactionCacheRepository()
      val rebuildUseCase =
        continuityRebuildUseCase(
          conversationRepository = conversationRepository,
          role = role,
          runtimeStateRepository = runtimeStateRepository,
          memoryAtomRepository = memoryAtomRepository,
          openThreadRepository = openThreadRepository,
          compactionCacheRepository = compactionCacheRepository,
        )
      rebuildUseCase(session.id)

      assertTrue(memoryAtomRepository.listBySession(session.id).any { !it.tombstone && it.objectValue.contains("blue delta") })
      assertEquals(4, conversationRepository.getSummary(session.id)?.coveredUntilSeq)

      val rollbackUseCase =
        RollbackRoleplayContinuityUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase = rebuildUseCase,
        )

      val rollbackResult = rollbackUseCase(sessionId = session.id, targetMessageId = "assistant-2")

      assertNotNull(rollbackResult)
      assertEquals(2, rollbackResult!!.rolledBackMessageCount)
      assertEquals(2, rollbackResult.rebuildResult?.canonicalMessageCount)
      assertEquals(1, rollbackResult.rebuildResult?.replayedTurnCount)

      val messages = conversationRepository.listMessages(session.id)
      val branchedMessages = messages.filter { it.seq > 2 }
      assertEquals(2, branchedMessages.size)
      assertTrue(branchedMessages.all { !it.isCanonical })
      assertTrue(branchedMessages.all { !it.accepted })
      assertTrue(branchedMessages.all { it.branchId == rollbackResult.rollbackBranchId })

      val remainingSummary = conversationRepository.getSummary(session.id)
      assertNotNull(remainingSummary)
      assertEquals(2, remainingSummary!!.coveredUntilSeq)
      assertFalse(remainingSummary.summaryText.contains("blue delta"))

      val atoms = memoryAtomRepository.listBySession(session.id)
      assertFalse(atoms.any { !it.tombstone && it.objectValue.contains("blue delta") })
      assertTrue(atoms.any { !it.tombstone && it.objectValue.contains("reach the observatory before dawn") })

      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.CONTINUITY_ROLLBACK_APPLIED })
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.MEMORY_REBUILD_COMPLETED })
    }

  @Test
  fun rollback_returnsNullForNonCanonicalTarget() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "assistant-branch",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.ASSISTANT,
                isCanonical = false,
                accepted = false,
                content = "Discarded candidate",
                now = now,
              ),
            ),
        )
      val rollbackUseCase =
        RollbackRoleplayContinuityUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase =
            continuityRebuildUseCase(
              conversationRepository = conversationRepository,
              role = role,
              runtimeStateRepository = ContinuityRuntimeStateRepository(),
              memoryAtomRepository = ContinuityMemoryAtomRepository(),
              openThreadRepository = ContinuityOpenThreadRepository(),
              compactionCacheRepository = ContinuityCompactionCacheRepository(),
            ),
        )

      val result = rollbackUseCase(sessionId = session.id, targetMessageId = "assistant-branch")

      assertNull(result)
      assertTrue(conversationRepository.events.isEmpty())
    }

  @Test
  fun rollback_returnsNullWhenNoFutureCanonicalMessagesExist() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "We need to leave now.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "I will open the route.",
                now = now,
              ),
            ),
        )
      val runtimeStateRepository = ContinuityRuntimeStateRepository()
      val memoryAtomRepository = ContinuityMemoryAtomRepository()
      val openThreadRepository = ContinuityOpenThreadRepository()
      val compactionCacheRepository = ContinuityCompactionCacheRepository()
      val rebuildUseCase =
        continuityRebuildUseCase(
          conversationRepository = conversationRepository,
          role = role,
          runtimeStateRepository = runtimeStateRepository,
          memoryAtomRepository = memoryAtomRepository,
          openThreadRepository = openThreadRepository,
          compactionCacheRepository = compactionCacheRepository,
        )
      val rollbackUseCase =
        RollbackRoleplayContinuityUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase = rebuildUseCase,
        )

      val result = rollbackUseCase(sessionId = session.id, targetMessageId = "assistant-2")

      assertNull(result)
      assertTrue(conversationRepository.events.isEmpty())
      assertNull(runtimeStateRepository.getLatestSnapshot(session.id))
      assertTrue(memoryAtomRepository.listBySession(session.id).isEmpty())
    }

  @Test
  fun prepareRegeneration_collectsContiguousUserRunAndStagesAssistantSeed() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "Check the hatch.",
                now = now,
              ),
              continuityMessage(
                id = "user-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.USER,
                content = "And remember the observatory route.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-3",
                sessionId = session.id,
                seq = 3,
                side = MessageSide.ASSISTANT,
                content = "I will check the hatch and keep the route in mind.",
                now = now,
              ),
            ),
        )
      val runtimeStateRepository = ContinuityRuntimeStateRepository()
      val memoryAtomRepository = ContinuityMemoryAtomRepository()
      val openThreadRepository = ContinuityOpenThreadRepository()
      val compactionCacheRepository = ContinuityCompactionCacheRepository()
      val rebuildUseCase =
        continuityRebuildUseCase(
          conversationRepository = conversationRepository,
          role = role,
          runtimeStateRepository = runtimeStateRepository,
          memoryAtomRepository = memoryAtomRepository,
          openThreadRepository = openThreadRepository,
          compactionCacheRepository = compactionCacheRepository,
        )
      val rollbackUseCase =
        RollbackRoleplayContinuityUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase = rebuildUseCase,
        )
      val prepareUseCase =
        PrepareRoleplayRegenerationUseCase(
          conversationRepository = conversationRepository,
          rollbackRoleplayContinuityUseCase = rollbackUseCase,
          sendRoleplayMessageUseCase = queueOnlySendRoleplayMessageUseCase(conversationRepository, role),
        )

      val result =
        prepareUseCase(
          sessionId = session.id,
          assistantMessageId = "assistant-3",
          model = queueOnlyModel(),
        )

      assertNotNull(result)
      assertEquals(listOf("user-1", "user-2"), result!!.sourceUserMessageIds)
      assertEquals("user-2", result.rollbackResult.targetMessageId)
      assertEquals("Check the hatch.\n\nAnd remember the observatory route.", result.pendingMessage.combinedUserInput)
      assertEquals(listOf("user-1", "user-2"), result.pendingMessage.userMessages.map(Message::id))
      assertEquals("user-2", result.pendingMessage.assistantSeed.parentMessageId)
      assertEquals("user-2", result.pendingMessage.assistantSeed.regenerateGroupId)
      assertEquals("main", result.pendingMessage.assistantSeed.branchId)
      assertFalse(result.pendingMessage.assistantSeed.isCanonical)
      assertFalse(result.pendingMessage.assistantSeed.accepted)

      val messages = conversationRepository.listMessages(session.id)
      assertEquals(4, messages.size)
      assertTrue(messages.any { it.id == "assistant-3" && !it.isCanonical && !it.accepted })
      assertTrue(messages.any { it.id == result.pendingMessage.assistantSeed.id && it.side == MessageSide.ASSISTANT })
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.CONTINUITY_REGENERATE_TRIGGERED })
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.CONTINUITY_ROLLBACK_APPLIED })
    }

  @Test
  fun prepareRegeneration_returnsNullWhenAssistantIsNotTriggeredByContiguousUserRun() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "Open the map.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "Map opened.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-3",
                sessionId = session.id,
                seq = 3,
                side = MessageSide.ASSISTANT,
                content = "Second assistant reply without a new user trigger.",
                now = now,
              ),
            ),
        )
      val prepareUseCase =
        PrepareRoleplayRegenerationUseCase(
          conversationRepository = conversationRepository,
          rollbackRoleplayContinuityUseCase =
            RollbackRoleplayContinuityUseCase(
              conversationRepository = conversationRepository,
              rebuildRoleplayContinuityUseCase =
                continuityRebuildUseCase(
                  conversationRepository = conversationRepository,
                  role = role,
                  runtimeStateRepository = ContinuityRuntimeStateRepository(),
                  memoryAtomRepository = ContinuityMemoryAtomRepository(),
                  openThreadRepository = ContinuityOpenThreadRepository(),
                  compactionCacheRepository = ContinuityCompactionCacheRepository(),
                ),
            ),
          sendRoleplayMessageUseCase = queueOnlySendRoleplayMessageUseCase(conversationRepository, role),
        )

      val result =
        prepareUseCase(
          sessionId = session.id,
          assistantMessageId = "assistant-3",
          model = queueOnlyModel(),
        )

      assertNull(result)
      assertTrue(conversationRepository.events.isEmpty())
      assertEquals(3, conversationRepository.listMessages(session.id).size)
    }

  @Test
  fun prepareEdit_rollsBackTargetUserMessageInclusivelyAndRestoresDraft() =
    runBlocking {
      val now = System.currentTimeMillis()
      val session = continuitySession(now)
      val role = continuityRole(now)
      val conversationRepository =
        ContinuityConversationRepository(
          session = session,
          messages =
            mutableListOf(
              continuityMessage(
                id = "user-1",
                sessionId = session.id,
                seq = 1,
                side = MessageSide.USER,
                content = "We need to leave now.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-2",
                sessionId = session.id,
                seq = 2,
                side = MessageSide.ASSISTANT,
                content = "I will open the route.",
                now = now,
              ),
              continuityMessage(
                id = "user-3",
                sessionId = session.id,
                seq = 3,
                side = MessageSide.USER,
                content = "Remember the hatch code is blue delta.",
                now = now,
              ),
              continuityMessage(
                id = "assistant-4",
                sessionId = session.id,
                seq = 4,
                side = MessageSide.ASSISTANT,
                content = "I will keep the blue delta code safe.",
                now = now,
              ),
            ),
        )
      val runtimeStateRepository = ContinuityRuntimeStateRepository()
      val memoryAtomRepository = ContinuityMemoryAtomRepository()
      val openThreadRepository = ContinuityOpenThreadRepository()
      val compactionCacheRepository = ContinuityCompactionCacheRepository()
      val rebuildUseCase =
        continuityRebuildUseCase(
          conversationRepository = conversationRepository,
          role = role,
          runtimeStateRepository = runtimeStateRepository,
          memoryAtomRepository = memoryAtomRepository,
          openThreadRepository = openThreadRepository,
          compactionCacheRepository = compactionCacheRepository,
        )
      rebuildUseCase(session.id)
      val prepareEditUseCase =
        PrepareRoleplayEditUseCase(
          conversationRepository = conversationRepository,
          rebuildRoleplayContinuityUseCase = rebuildUseCase,
        )

      val result =
        prepareEditUseCase(
          sessionId = session.id,
          targetMessageId = "user-3",
        )

      assertNotNull(result)
      assertEquals("Remember the hatch code is blue delta.", result!!.restoredDraft)
      assertEquals(2, result.rolledBackMessageCount)
      assertEquals(2, result.rebuildResult?.canonicalMessageCount)

      val messages = conversationRepository.listMessages(session.id)
      val editedRun = messages.filter { it.seq >= 3 }
      assertEquals(2, editedRun.size)
      assertTrue(editedRun.all { !it.isCanonical && !it.accepted })
      assertTrue(editedRun.all { it.branchId == result.rollbackBranchId })

      val summary = conversationRepository.getSummary(session.id)
      assertNotNull(summary)
      assertEquals(2, summary!!.coveredUntilSeq)
      assertFalse(summary.summaryText.contains("blue delta"))
      assertTrue(conversationRepository.events.any { it.eventType == SessionEventType.CONTINUITY_EDIT_TRIGGERED })
    }
}

private fun continuityRebuildUseCase(
  conversationRepository: ContinuityConversationRepository,
  role: RoleCard,
  runtimeStateRepository: ContinuityRuntimeStateRepository,
  memoryAtomRepository: ContinuityMemoryAtomRepository,
  openThreadRepository: ContinuityOpenThreadRepository,
  compactionCacheRepository: ContinuityCompactionCacheRepository,
): RebuildRoleplayContinuityUseCase {
  val extractMemoriesUseCase =
    ExtractMemoriesUseCase(
      memoryRepository = ContinuityMemoryRepository(),
      memoryAtomRepository = memoryAtomRepository,
      openThreadRepository = openThreadRepository,
      runtimeStateRepository = runtimeStateRepository,
      conversationRepository = conversationRepository,
      validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
    )

  return RebuildRoleplayContinuityUseCase(
    conversationRepository = conversationRepository,
    roleRepository = ContinuityRoleRepository(role),
    runtimeStateRepository = runtimeStateRepository,
    memoryAtomRepository = memoryAtomRepository,
    openThreadRepository = openThreadRepository,
    compactionCacheRepository = compactionCacheRepository,
    extractMemoriesUseCase = extractMemoriesUseCase,
    summarizeSessionUseCase =
      SummarizeSessionUseCase(
        FakeDataStoreRepository(
          stUserProfile =
            StUserProfile(
              personas = mapOf("captain" to "Captain Mae"),
            ),
        ),
        conversationRepository,
        compactionCacheRepository,
        TokenEstimator(),
      ),
  )
}

private fun queueOnlySendRoleplayMessageUseCase(
  conversationRepository: ContinuityConversationRepository,
  role: RoleCard,
): SendRoleplayMessageUseCase {
  val compactionCacheRepository = ContinuityCompactionCacheRepository()
  return SendRoleplayMessageUseCase(
    dataStoreRepository = FakeDataStoreRepository(),
    conversationRepository = conversationRepository,
    roleRepository = ContinuityRoleRepository(role),
    toolOrchestrator = NoOpRoleplayToolOrchestrator(),
    compileRuntimeRoleProfileUseCase = CompileRuntimeRoleProfileUseCase(TokenEstimator()),
    promptAssembler = PromptAssembler(TokenEstimator()),
    compileRoleplayMemoryContextUseCase =
      CompileRoleplayMemoryContextUseCase(
        conversationRepository = conversationRepository,
        runtimeStateRepository = ContinuityRuntimeStateRepository(),
        openThreadRepository = ContinuityOpenThreadRepository(),
        memoryAtomRepository = ContinuityMemoryAtomRepository(),
        memoryRepository = ContinuityMemoryRepository(),
        compactionCacheRepository = compactionCacheRepository,
        tokenEstimator = TokenEstimator(),
      ),
    summarizeSessionUseCase =
      SummarizeSessionUseCase(
        FakeDataStoreRepository(),
        conversationRepository,
        compactionCacheRepository,
        TokenEstimator(),
      ),
    extractMemoriesUseCase =
      ExtractMemoriesUseCase(
        memoryRepository = ContinuityMemoryRepository(),
        memoryAtomRepository = ContinuityMemoryAtomRepository(),
        openThreadRepository = ContinuityOpenThreadRepository(),
        runtimeStateRepository = ContinuityRuntimeStateRepository(),
        conversationRepository = conversationRepository,
        validateMemoryAtomCandidateUseCase = ValidateMemoryAtomCandidateUseCase(),
      ),
  )
}

private fun queueOnlyModel(): Model = Model(name = "queue-only", downloadFileName = "queue-only.tflite")

private class ContinuityConversationRepository(
  private val session: Session,
  private val messages: MutableList<Message>,
) : ConversationRepository {
  var savedSummary: SessionSummary? = null
  val events = mutableListOf<SessionEvent>()

  override fun observeSessions(): Flow<List<Session>> = flowOf(listOf(session))

  override fun observeMessages(sessionId: String): Flow<List<Message>> =
    flowOf(messages.filter { it.sessionId == sessionId })

  override suspend fun listMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId }.sortedBy(Message::seq)

  override suspend fun listCanonicalMessages(sessionId: String): List<Message> =
    messages.filter { it.sessionId == sessionId && it.isCanonical }.sortedBy(Message::seq)

  override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }

  override suspend fun getSession(sessionId: String): Session? = session.takeIf { it.id == sessionId }

  override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
    error("Not needed in this test")
  }

  override suspend fun updateSession(session: Session) {
    error("Not needed in this test")
  }

  override suspend fun archiveSession(sessionId: String) = Unit

  override suspend fun deleteSession(sessionId: String) = Unit

  override suspend fun appendMessage(message: Message) {
    messages += message
  }

  override suspend fun updateMessage(message: Message) {
    val index = messages.indexOfFirst { it.id == message.id }
    if (index >= 0) {
      messages[index] = message
    }
  }

  override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? {
    error("Not needed in this test")
  }

  override suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messages.firstOrNull { it.id == targetMessageId && it.sessionId == sessionId } ?: return 0
    var count = 0
    messages.replaceAll { message ->
      if (message.sessionId == sessionId && message.seq > target.seq && message.isCanonical) {
        count += 1
        message.copy(
          branchId = rollbackBranchId,
          accepted = false,
          isCanonical = false,
          updatedAt = updatedAt,
        )
      } else {
        message
      }
    }
    return count
  }

  override suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int {
    val target = messages.firstOrNull { it.id == targetMessageId && it.sessionId == sessionId } ?: return 0
    var count = 0
    messages.replaceAll { message ->
      if (message.sessionId == sessionId && message.seq >= target.seq && message.isCanonical) {
        count += 1
        message.copy(
          branchId = rollbackBranchId,
          accepted = false,
          isCanonical = false,
          updatedAt = updatedAt,
        )
      } else {
        message
      }
    }
    return count
  }

  override suspend fun replaceMessages(sessionId: String, messages: List<Message>) {
    this.messages.removeAll { it.sessionId == sessionId }
    this.messages += messages
  }

  override suspend fun nextMessageSeq(sessionId: String): Int = listMessages(sessionId).size + 1

  override suspend fun getSummary(sessionId: String): SessionSummary? = savedSummary?.takeIf { it.sessionId == sessionId }

  override suspend fun upsertSummary(summary: SessionSummary) {
    savedSummary = summary
  }

  override suspend fun deleteSummary(sessionId: String) {
    savedSummary = savedSummary?.takeUnless { it.sessionId == sessionId }
  }

  override suspend fun listEvents(sessionId: String): List<SessionEvent> = events.filter { it.sessionId == sessionId }

  override suspend fun appendEvent(event: SessionEvent) {
    events += event
  }
}

private class ContinuityRoleRepository(
  private val role: RoleCard,
) : RoleRepository {
  override fun observeRoles(): Flow<List<RoleCard>> = flowOf(listOf(role))

  override suspend fun getRole(roleId: String): RoleCard? = role.takeIf { it.id == roleId }

  override suspend fun saveRole(role: RoleCard) = Unit

  override suspend fun deleteRole(roleId: String) = Unit
}

private class ContinuityRuntimeStateRepository(
  private var snapshot: RuntimeStateSnapshot? = null,
) : RuntimeStateRepository {
  val deleteCalls = mutableListOf<String>()

  override suspend fun getLatestSnapshot(sessionId: String): RuntimeStateSnapshot? =
    snapshot?.takeIf { it.sessionId == sessionId }

  override suspend fun upsert(snapshot: RuntimeStateSnapshot) {
    this.snapshot = snapshot
  }

  override suspend fun deleteBySession(sessionId: String) {
    deleteCalls += sessionId
    if (snapshot?.sessionId == sessionId) {
      snapshot = null
    }
  }
}

private class ContinuityMemoryAtomRepository(
  private val atoms: MutableList<MemoryAtom> = mutableListOf(),
) : MemoryAtomRepository {
  val tombstonedSessionIds = mutableListOf<String>()

  override suspend fun listBySession(sessionId: String): List<MemoryAtom> =
    atoms.filter { it.sessionId == sessionId }

  override suspend fun upsert(atom: MemoryAtom) {
    atoms.removeAll { it.id == atom.id }
    atoms += atom
  }

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) = Unit

  override suspend fun tombstone(memoryId: String, updatedAt: Long) {
    val index = atoms.indexOfFirst { it.id == memoryId }
    if (index >= 0) {
      atoms[index] = atoms[index].copy(tombstone = true, updatedAt = updatedAt)
    }
  }

  override suspend fun tombstoneBySession(sessionId: String, updatedAt: Long) {
    tombstonedSessionIds += sessionId
    atoms.replaceAll { atom ->
      if (atom.sessionId == sessionId) {
        atom.copy(tombstone = true, updatedAt = updatedAt)
      } else {
        atom
      }
    }
  }

  override suspend fun searchRelevant(
    sessionId: String,
    roleId: String,
    query: String,
    limit: Int,
  ): List<MemoryAtom> =
    atoms.filter { it.sessionId == sessionId && it.roleId == roleId && !it.tombstone }.take(limit)
}

private class ContinuityOpenThreadRepository(
  private val threads: MutableList<OpenThread> = mutableListOf(),
) : OpenThreadRepository {
  val deletedSessionIds = mutableListOf<String>()

  override suspend fun listBySession(sessionId: String): List<OpenThread> =
    threads.filter { it.sessionId == sessionId }

  override suspend fun listByStatus(sessionId: String, status: OpenThreadStatus): List<OpenThread> =
    threads.filter { it.sessionId == sessionId && it.status == status }

  override suspend fun upsert(thread: OpenThread) {
    threads.removeAll { it.id == thread.id }
    threads += thread
  }

  override suspend fun deleteBySession(sessionId: String) {
    deletedSessionIds += sessionId
    threads.removeAll { it.sessionId == sessionId }
  }

  override suspend fun updateStatus(
    threadId: String,
    status: OpenThreadStatus,
    resolvedByMessageId: String?,
    updatedAt: Long,
  ) {
    val index = threads.indexOfFirst { it.id == threadId }
    if (index >= 0) {
      threads[index] =
        threads[index].copy(
          status = status,
          resolvedByMessageId = resolvedByMessageId,
          updatedAt = updatedAt,
        )
    }
  }
}

private class ContinuityCompactionCacheRepository(
  private val entries: MutableList<CompactionCacheEntry> = mutableListOf(),
) : CompactionCacheRepository {
  val deletedSessionIds = mutableListOf<String>()

  override suspend fun listBySession(sessionId: String): List<CompactionCacheEntry> =
    entries.filter { it.sessionId == sessionId }

  override suspend fun upsert(entry: CompactionCacheEntry) {
    entries.removeAll { it.id == entry.id }
    entries += entry
  }

  override suspend fun deleteBySession(sessionId: String) {
    deletedSessionIds += sessionId
    entries.removeAll { it.sessionId == sessionId }
  }
}

private class ContinuityMemoryRepository : MemoryRepository {
  private val memories = mutableListOf<MemoryItem>()

  override suspend fun listRoleMemories(roleId: String): List<MemoryItem> =
    memories.filter { it.roleId == roleId && it.sessionId == null }

  override suspend fun listSessionMemories(sessionId: String): List<MemoryItem> =
    memories.filter { it.sessionId == sessionId }

  override suspend fun upsert(memory: MemoryItem) {
    memories.removeAll { it.id == memory.id }
    memories += memory
  }

  override suspend fun deactivate(memoryId: String) = Unit

  override suspend fun markUsed(memoryIds: List<String>, usedAt: Long) = Unit

  override suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem> =
    memories.filter { it.roleId == roleId && (sessionId == null || it.sessionId == sessionId) }.take(limit)
}

private fun continuitySession(now: Long): Session =
  Session(
    id = "session-1",
    roleId = "role-1",
    title = "Observatory Run",
    activeModelId = "gemma-3n",
    createdAt = now,
    updatedAt = now,
    lastMessageAt = now,
    turnCount = 4,
    sessionUserProfile =
      StUserProfile(
        personas = mapOf("captain" to "Captain Mae"),
      ).snapshotSelectedPersona("captain"),
  )

private fun continuityRole(now: Long): RoleCard =
  RoleCard(
    id = "role-1",
    name = "Captain Astra",
    summary = "A disciplined starship captain.",
    systemPrompt = "Stay tactically focused.",
    memoryEnabled = true,
    memoryMaxItems = 4,
    createdAt = now,
    updatedAt = now,
  )

private fun continuityMessage(
  id: String,
  sessionId: String,
  seq: Int,
  side: MessageSide,
  content: String,
  now: Long,
  status: MessageStatus = MessageStatus.COMPLETED,
  accepted: Boolean = true,
  isCanonical: Boolean = true,
): Message =
  Message(
    id = id,
    sessionId = sessionId,
    seq = seq,
    branchId = if (isCanonical) "main" else "branch-alt",
    side = side,
    kind = MessageKind.TEXT,
    status = status,
    accepted = accepted,
    isCanonical = isCanonical,
    content = content,
    createdAt = now,
    updatedAt = now,
  )

private fun continuityAtom(
  id: String,
  sessionId: String,
  roleId: String,
  objectValue: String,
  now: Long,
): MemoryAtom =
  MemoryAtom(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = MemoryPlane.CANON,
    namespace = MemoryNamespace.EPISODIC,
    subject = "scene",
    predicate = "event",
    objectValue = objectValue,
    normalizedObjectValue = objectValue.lowercase(),
    stability = MemoryStability.STABLE,
    epistemicStatus = MemoryEpistemicStatus.OBSERVED,
    branchScope = MemoryBranchScope.ACCEPTED_ONLY,
    evidenceQuote = objectValue,
    createdAt = now,
    updatedAt = now,
  )

private fun continuityThread(
  id: String,
  sessionId: String,
  content: String,
  priority: Int,
  now: Long,
): OpenThread =
  OpenThread(
    id = id,
    sessionId = sessionId,
    type = OpenThreadType.TASK,
    content = content,
    owner = OpenThreadOwner.SHARED,
    priority = priority,
    status = OpenThreadStatus.OPEN,
    createdAt = now,
    updatedAt = now,
  )
