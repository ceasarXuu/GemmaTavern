package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolInvocation

data class RoleplayToolHandlerResult(
  val toolInvocation: ToolInvocation,
  val externalFacts: List<RoleplayExternalFact> = emptyList(),
)

interface RoleplayToolHandler {
  val toolName: String
  val priority: Int
    get() = 0

  suspend fun maybeExecute(
    request: RoleplayToolExecutionRequest,
    stepIndex: Int,
  ): RoleplayToolHandlerResult?
}
