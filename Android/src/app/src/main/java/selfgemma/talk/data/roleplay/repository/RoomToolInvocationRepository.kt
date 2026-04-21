package selfgemma.talk.data.roleplay.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import selfgemma.talk.data.roleplay.db.dao.ToolInvocationDao
import selfgemma.talk.data.roleplay.mapper.toDomain
import selfgemma.talk.data.roleplay.mapper.toEntity
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository

@Singleton
class RoomToolInvocationRepository @Inject constructor(
  private val toolInvocationDao: ToolInvocationDao,
) : ToolInvocationRepository {
  override fun observeBySession(sessionId: String): Flow<List<ToolInvocation>> {
    return toolInvocationDao.observeBySession(sessionId).map { invocations ->
      invocations.map { it.toDomain() }
    }
  }

  override suspend fun listBySession(sessionId: String): List<ToolInvocation> {
    return toolInvocationDao.listBySession(sessionId).map { it.toDomain() }
  }

  override suspend fun listByTurn(sessionId: String, turnId: String): List<ToolInvocation> {
    return toolInvocationDao.listByTurn(sessionId, turnId).map { it.toDomain() }
  }

  override suspend fun upsert(invocation: ToolInvocation) {
    toolInvocationDao.upsert(invocation.toEntity())
  }
}
