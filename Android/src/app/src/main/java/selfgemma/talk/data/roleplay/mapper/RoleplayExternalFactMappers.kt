package selfgemma.talk.data.roleplay.mapper

import selfgemma.talk.data.roleplay.db.entity.ExternalFactEntity
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.expiresAt

fun ExternalFactEntity.toDomain(): RoleplayExternalFact {
  return RoleplayExternalFact(
    id = id,
    sourceToolName = sourceToolName,
    title = title,
    content = content,
    ephemeral = ephemeral,
    factKey = factKey,
    factType = factType,
    structuredValueJson = structuredValueJson,
    summaryEligible = summaryEligible,
    turnId = turnId,
    toolInvocationId = toolInvocationId,
    capturedAt = capturedAt,
    freshnessTtlMillis = freshnessTtlMillis,
    confidence = confidence,
  )
}

fun RoleplayExternalFact.toEntity(
  sessionId: String,
  turnId: String,
): ExternalFactEntity {
  return ExternalFactEntity(
    id = id,
    sessionId = sessionId,
    turnId = turnId,
    toolInvocationId = toolInvocationId,
    sourceToolName = sourceToolName,
    title = title,
    content = content,
    factKey = factKey,
    factType = factType,
    structuredValueJson = structuredValueJson,
    ephemeral = ephemeral,
    summaryEligible = summaryEligible,
    capturedAt = capturedAt,
    freshnessTtlMillis = freshnessTtlMillis,
    expiresAt = expiresAt(),
    confidence = confidence,
  )
}
