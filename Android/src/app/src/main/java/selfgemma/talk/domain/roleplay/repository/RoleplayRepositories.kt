package selfgemma.talk.domain.roleplay.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile

interface ConversationRepository {
  fun observeSessions(): Flow<List<Session>>

  fun observeArchivedSessions(): Flow<List<Session>> = flowOf(emptyList())

  fun observeMessages(sessionId: String): Flow<List<Message>>

  suspend fun listMessages(sessionId: String): List<Message>

  suspend fun listCanonicalMessages(sessionId: String): List<Message>

  suspend fun getMessage(messageId: String): Message?

  suspend fun getSession(sessionId: String): Session?

  suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile? = null): Session

  suspend fun updateSession(session: Session)

  suspend fun archiveSession(sessionId: String)

  suspend fun restoreSession(sessionId: String) = Unit

  suspend fun deleteSession(sessionId: String)

  suspend fun appendMessage(message: Message)

  suspend fun updateMessage(message: Message)

  suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message?

  suspend fun rollbackToMessage(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int

  suspend fun rollbackFromMessageInclusive(
    sessionId: String,
    targetMessageId: String,
    rollbackBranchId: String,
    updatedAt: Long,
  ): Int

  suspend fun replaceMessages(sessionId: String, messages: List<Message>)

  suspend fun nextMessageSeq(sessionId: String): Int

  suspend fun getSummary(sessionId: String): SessionSummary?

  suspend fun upsertSummary(summary: SessionSummary)

  suspend fun deleteSummary(sessionId: String)

  suspend fun listEvents(sessionId: String): List<SessionEvent>

  suspend fun appendEvent(event: SessionEvent)
}

interface RoleRepository {
  fun observeRoles(): Flow<List<RoleCard>>

  suspend fun getRole(roleId: String): RoleCard?

  suspend fun saveRole(role: RoleCard)

  suspend fun deleteRole(roleId: String)
}

interface MemoryRepository {
  suspend fun listRoleMemories(roleId: String): List<MemoryItem>

  suspend fun listSessionMemories(sessionId: String): List<MemoryItem>

  suspend fun upsert(memory: MemoryItem)

  suspend fun deactivate(memoryId: String)

  suspend fun markUsed(memoryIds: List<String>, usedAt: Long)

  suspend fun searchRelevant(
    roleId: String,
    sessionId: String?,
    query: String,
    limit: Int,
  ): List<MemoryItem>
}
