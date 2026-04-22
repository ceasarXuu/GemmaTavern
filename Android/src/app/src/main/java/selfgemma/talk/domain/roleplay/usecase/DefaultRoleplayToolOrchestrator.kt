package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RoleplayToolOrchestrator"

@Singleton
class DefaultRoleplayToolOrchestrator @Inject constructor(
  private val toolProviderFactories: Set<@JvmSuppressWildcards RoleplayToolProviderFactory>,
  private val accessPolicy: RoleplayToolAccessPolicy,
) : RoleplayToolOrchestrator {
  override suspend fun prepareTurnContext(request: RoleplayToolPreparationRequest): RoleplayPreparedToolContext {
    val collector =
      RoleplayToolTraceCollector(
        sessionId = request.pendingMessage.session.id,
        turnId = request.pendingMessage.assistantSeed.id,
      )
    if (toolProviderFactories.isEmpty() || request.isStopRequested()) {
      return RoleplayPreparedToolContext(collector = collector)
    }

    val orderedFactories =
      toolProviderFactories.sortedWith(
        compareBy<RoleplayToolProviderFactory>(
          RoleplayToolProviderFactory::priority,
          { factory -> factory.javaClass.name },
        ),
      )
    val providers =
      orderedFactories.mapNotNull { factory ->
          if (!accessPolicy.canRegisterTool(factory.toolId)) {
            logDebug(
              "tool hidden turnId=${request.pendingMessage.assistantSeed.id} toolId=${factory.toolId}",
            )
            return@mapNotNull null
          }
          factory.createToolProvider(
            pendingMessage = request.pendingMessage,
            collector = collector,
          )
        }
    logDebug(
      "prepared tool context turnId=${request.pendingMessage.assistantSeed.id} toolCount=${providers.size} factories=${orderedFactories.joinToString(separator = ",") { factory -> factory.javaClass.simpleName }}",
    )
    return RoleplayPreparedToolContext(
      tools = providers,
      collector = collector,
    )
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }
}
