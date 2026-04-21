package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoleplayToolOrchestrator"

@Singleton
class DefaultRoleplayToolOrchestrator @Inject constructor(
  private val toolHandlers: Set<@JvmSuppressWildcards RoleplayToolHandler>,
) : RoleplayToolOrchestrator {
  override suspend fun execute(request: RoleplayToolExecutionRequest): RoleplayToolExecutionResult {
    if (toolHandlers.isEmpty() || request.isStopRequested()) {
      return RoleplayToolExecutionResult()
    }

    val orderedHandlers = toolHandlers.sortedWith(compareBy<RoleplayToolHandler>({ it.priority }, { it.toolName }))
    val toolInvocations = mutableListOf<selfgemma.talk.domain.roleplay.model.ToolInvocation>()
    val externalFacts = mutableListOf<selfgemma.talk.domain.roleplay.model.RoleplayExternalFact>()

    for (handler in orderedHandlers) {
      if (request.isStopRequested()) {
        logDebug("stop requested before tool dispatch turnId=${request.pendingMessage.assistantSeed.id}")
        break
      }
      val stepIndex = toolInvocations.size
      val result = handler.maybeExecute(request = request, stepIndex = stepIndex) ?: continue
      toolInvocations += result.toolInvocation
      externalFacts += result.externalFacts
      logDebug(
        "tool executed turnId=${request.pendingMessage.assistantSeed.id} tool=${result.toolInvocation.toolName} step=$stepIndex facts=${result.externalFacts.size}",
      )
    }

    if (toolInvocations.isEmpty()) {
      return RoleplayToolExecutionResult()
    }

    return RoleplayToolExecutionResult(
      toolInvocations = toolInvocations,
      augmentedPendingMessage =
        request.pendingMessage.copy(
          externalFacts = request.pendingMessage.externalFacts + externalFacts,
        ),
    )
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }
}
