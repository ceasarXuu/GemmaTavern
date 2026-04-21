package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.Model

data class RoleplayToolPreparationRequest(
  val pendingMessage: PendingRoleplayMessage,
  val model: Model,
  val enableStreamingOutput: Boolean,
  val isStopRequested: () -> Boolean,
)

interface RoleplayToolOrchestrator {
  suspend fun prepareTurnContext(request: RoleplayToolPreparationRequest): RoleplayPreparedToolContext
}

@Singleton
class NoOpRoleplayToolOrchestrator @Inject constructor() : RoleplayToolOrchestrator {
  override suspend fun prepareTurnContext(request: RoleplayToolPreparationRequest): RoleplayPreparedToolContext {
    return RoleplayPreparedToolContext(
      collector =
        RoleplayToolTraceCollector(
          sessionId = request.pendingMessage.session.id,
          turnId = request.pendingMessage.assistantSeed.id,
        )
    )
  }
}
