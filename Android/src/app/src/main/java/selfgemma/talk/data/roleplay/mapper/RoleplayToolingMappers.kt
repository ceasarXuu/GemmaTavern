package selfgemma.talk.data.roleplay.mapper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import selfgemma.talk.data.roleplay.db.entity.ToolInvocationEntity
import selfgemma.talk.domain.roleplay.model.ToolArtifactRef
import selfgemma.talk.domain.roleplay.model.ToolInvocation

private val roleplayToolingGson = Gson()

fun ToolInvocationEntity.toDomain(): ToolInvocation {
  return ToolInvocation(
    id = id,
    sessionId = sessionId,
    turnId = turnId,
    toolName = toolName,
    source = source,
    status = status,
    stepIndex = stepIndex,
    argsJson = argsJson,
    resultJson = resultJson,
    resultSummary = resultSummary,
    artifactRefs = decodeToolArtifactRefs(artifactRefsJson),
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt,
  )
}

fun ToolInvocation.toEntity(): ToolInvocationEntity {
  return ToolInvocationEntity(
    id = id,
    sessionId = sessionId,
    turnId = turnId,
    toolName = toolName,
    source = source,
    status = status,
    stepIndex = stepIndex,
    argsJson = argsJson,
    resultJson = resultJson,
    resultSummary = resultSummary,
    artifactRefsJson = encodeToolArtifactRefs(artifactRefs),
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt,
  )
}

private fun encodeToolArtifactRefs(value: List<ToolArtifactRef>): String {
  return roleplayToolingGson.toJson(value)
}

private fun decodeToolArtifactRefs(value: String?): List<ToolArtifactRef> {
  if (value.isNullOrBlank()) {
    return emptyList()
  }
  val type = object : TypeToken<List<ToolArtifactRef>>() {}.type
  return roleplayToolingGson.fromJson(value, type) ?: emptyList()
}
