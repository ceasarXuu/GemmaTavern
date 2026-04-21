package selfgemma.talk.domain.roleplay.repository

import selfgemma.talk.domain.roleplay.model.ToolInvocation

interface ToolInvocationRepository {
  suspend fun listBySession(sessionId: String): List<ToolInvocation>

  suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation>

  suspend fun upsert(invocation: ToolInvocation)
}
