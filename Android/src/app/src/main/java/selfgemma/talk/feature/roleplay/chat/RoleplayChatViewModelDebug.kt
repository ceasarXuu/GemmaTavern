package selfgemma.talk.feature.roleplay.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEventType

internal fun RoleplayChatViewModel.refreshSupplementalState() {
  viewModelScope.launch {
    val session = conversationRepository.getSession(sessionId)
    if (session == null) {
      metaState.update { current ->
        current.copy(
          summary = null,
          pinnedMemories = emptyList(),
          continuityDebug = RoleplayContinuityDebugState(),
        )
      }
      return@launch
    }

    val summary = conversationRepository.getSummary(sessionId)
    val pinnedMemories = loadPinnedMemories(session)
    val continuityDebug = loadContinuityDebugState(sessionId = sessionId)
    metaState.update { current ->
      current.copy(
        summary = summary,
        pinnedMemories = pinnedMemories,
        continuityDebug = continuityDebug,
      )
    }
  }
}

internal suspend fun RoleplayChatViewModel.loadPinnedMemories(session: Session): List<MemoryItem> {
  return (memoryRepository.listSessionMemories(session.id) + memoryRepository.listRoleMemories(session.roleId))
    .filter { it.pinned }
    .distinctBy { it.normalizedHash }
    .sortedByDescending { it.updatedAt }
    .take(8)
}

internal suspend fun RoleplayChatViewModel.loadContinuityDebugState(sessionId: String): RoleplayContinuityDebugState {
  val runtimeState = runtimeStateRepository.getLatestSnapshot(sessionId)
  val openThreads =
    openThreadRepository
      .listBySession(sessionId)
      .sortedWith(compareByDescending<OpenThread> { it.priority }.thenByDescending { it.updatedAt })
  val memoryAtoms =
    memoryAtomRepository
      .listBySession(sessionId)
      .filterNot { it.tombstone }
      .sortedWith(
        compareByDescending<MemoryAtom> { it.updatedAt }
          .thenByDescending { it.salience }
          .thenByDescending { it.confidence }
      )
  val recentEvents = conversationRepository.listEvents(sessionId).take(12)
  val latestMemoryQueryPayload =
    recentEvents.firstOrNull { it.eventType == SessionEventType.MEMORY_QUERY_EXECUTED }?.payloadJson
  val latestMemoryPackPayload =
    recentEvents.firstOrNull { it.eventType == SessionEventType.MEMORY_PACK_COMPILED }?.payloadJson
  val compactionEntryCount = compactionCacheRepository.listBySession(sessionId).size
  return RoleplayContinuityDebugState(
    runtimeState = runtimeState,
    openThreads = openThreads.take(8),
    memoryAtoms = memoryAtoms.take(12),
    recentEvents = recentEvents,
    latestMemoryQueryPayload = latestMemoryQueryPayload,
    latestMemoryPackPayload = latestMemoryPackPayload,
    compactionEntryCount = compactionEntryCount,
  )
}

internal fun RoleplayChatViewModel.hasContinuityMutationConflict(): Boolean {
  return metaState.value.inProgress || metaState.value.pendingUserMessages.isNotEmpty()
}
