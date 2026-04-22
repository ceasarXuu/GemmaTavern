package selfgemma.talk.domain.roleplay.usecase

import com.google.ai.edge.litertlm.ToolProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolArtifactRef
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource
import selfgemma.talk.domain.roleplay.model.ToolInvocation

data class RoleplayPreparedToolContext(
  val tools: List<ToolProvider> = emptyList(),
  val collector: RoleplayToolTraceCollector,
)

interface RoleplayToolProviderFactory {
  val toolId: String

  val priority: Int
    get() = 0

  fun createToolProvider(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolProvider?
}

class RoleplayToolTraceCollector(
  private val sessionId: String,
  private val turnId: String,
) {
  private val nextStepIndex = AtomicInteger(0)
  private val invocations = CopyOnWriteArrayList<ToolInvocation>()
  private val externalFacts = CopyOnWriteArrayList<RoleplayExternalFact>()

  fun recordSucceeded(
    toolName: String,
    argsJson: String = "{}",
    resultJson: String? = null,
    resultSummary: String? = null,
    source: ToolExecutionSource = ToolExecutionSource.NATIVE,
    artifactRefs: List<ToolArtifactRef> = emptyList(),
    externalFacts: List<RoleplayExternalFact> = emptyList(),
    startedAt: Long = System.currentTimeMillis(),
    finishedAt: Long = System.currentTimeMillis(),
  ): ToolInvocation {
    val invocation =
      ToolInvocation(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        turnId = turnId,
        toolName = toolName,
        source = source,
        status = selfgemma.talk.domain.roleplay.model.ToolInvocationStatus.SUCCEEDED,
        stepIndex = nextStepIndex.getAndIncrement(),
        argsJson = argsJson,
        resultJson = resultJson,
        resultSummary = resultSummary,
        artifactRefs = artifactRefs,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
    invocations += invocation
    this.externalFacts +=
      externalFacts.map { fact ->
        applyRoleplayExternalFactPolicy(
          toolName = toolName,
          fact = fact,
          resultJson = resultJson,
          turnId = turnId,
          toolInvocationId = invocation.id,
          finishedAt = finishedAt,
        )
      }
    return invocation
  }

  fun recordFailed(
    toolName: String,
    argsJson: String = "{}",
    errorMessage: String,
    source: ToolExecutionSource = ToolExecutionSource.NATIVE,
    startedAt: Long = System.currentTimeMillis(),
    finishedAt: Long = System.currentTimeMillis(),
  ): ToolInvocation {
    val invocation =
      ToolInvocation(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        turnId = turnId,
        toolName = toolName,
        source = source,
        status = selfgemma.talk.domain.roleplay.model.ToolInvocationStatus.FAILED,
        stepIndex = nextStepIndex.getAndIncrement(),
        argsJson = argsJson,
        errorMessage = errorMessage,
        startedAt = startedAt,
        finishedAt = finishedAt,
      )
    invocations += invocation
    return invocation
  }

  fun snapshotInvocations(): List<ToolInvocation> {
    return invocations.sortedBy(ToolInvocation::stepIndex)
  }

  fun snapshotExternalFacts(): List<RoleplayExternalFact> {
    return externalFacts.toList()
  }
}
