package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.ToolInvocation

data class RoleplayToolExecutionRequest(
  val pendingMessage: PendingRoleplayMessage,
  val model: Model,
  val enableStreamingOutput: Boolean,
  val isStopRequested: () -> Boolean,
)

data class RoleplayToolExecutionResult(
  val handled: Boolean = false,
  val finalResult: SendRoleplayMessageResult? = null,
  val toolInvocations: List<ToolInvocation> = emptyList(),
  val augmentedPendingMessage: PendingRoleplayMessage? = null,
)

interface RoleplayToolOrchestrator {
  suspend fun execute(request: RoleplayToolExecutionRequest): RoleplayToolExecutionResult
}

@Singleton
class NoOpRoleplayToolOrchestrator @Inject constructor() : RoleplayToolOrchestrator {
  override suspend fun execute(request: RoleplayToolExecutionRequest): RoleplayToolExecutionResult {
    return RoleplayToolExecutionResult()
  }
}
