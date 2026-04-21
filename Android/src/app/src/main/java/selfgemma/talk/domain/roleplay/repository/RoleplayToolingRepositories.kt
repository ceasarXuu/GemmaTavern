package selfgemma.talk.domain.roleplay.repository

import kotlinx.coroutines.flow.Flow
import selfgemma.talk.domain.roleplay.model.ToolInvocation

interface ToolInvocationRepository {
  fun observeBySession(sessionId: String): Flow<List<ToolInvocation>>

  suspend fun listBySession(sessionId: String): List<ToolInvocation>

  suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation>

  suspend fun upsert(invocation: ToolInvocation)
}
