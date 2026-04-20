package selfgemma.talk.data.roleplay.mapper

import selfgemma.talk.data.roleplay.db.entity.CompactionCacheEntity
import selfgemma.talk.data.roleplay.db.entity.MemoryAtomEntity
import selfgemma.talk.data.roleplay.db.entity.OpenThreadEntity
import selfgemma.talk.data.roleplay.db.entity.RuntimeStateSnapshotEntity
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot

fun RuntimeStateSnapshotEntity.toDomain(): RuntimeStateSnapshot {
  return RuntimeStateSnapshot(
    sessionId = sessionId,
    sceneJson = sceneJson,
    relationshipJson = relationshipJson,
    activeEntitiesJson = activeEntitiesJson,
    updatedAt = updatedAt,
    sourceMessageId = sourceMessageId,
  )
}

fun RuntimeStateSnapshot.toEntity(): RuntimeStateSnapshotEntity {
  return RuntimeStateSnapshotEntity(
    sessionId = sessionId,
    sceneJson = sceneJson,
    relationshipJson = relationshipJson,
    activeEntitiesJson = activeEntitiesJson,
    updatedAt = updatedAt,
    sourceMessageId = sourceMessageId,
  )
}

fun MemoryAtomEntity.toDomain(): MemoryAtom {
  return MemoryAtom(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = plane,
    namespace = namespace,
    subject = subject,
    predicate = predicate,
    objectValue = objectValue,
    normalizedObjectValue = normalizedObjectValue,
    stability = stability,
    epistemicStatus = epistemicStatus,
    salience = salience,
    confidence = confidence,
    timeStartMessageId = timeStartMessageId,
    timeEndMessageId = timeEndMessageId,
    branchScope = branchScope,
    sourceMessageIds = sourceMessageIds,
    evidenceQuote = evidenceQuote,
    supersedesMemoryId = supersedesMemoryId,
    tombstone = tombstone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
  )
}

fun MemoryAtom.toEntity(): MemoryAtomEntity {
  return MemoryAtomEntity(
    id = id,
    sessionId = sessionId,
    roleId = roleId,
    plane = plane,
    namespace = namespace,
    subject = subject,
    predicate = predicate,
    objectValue = objectValue,
    normalizedObjectValue = normalizedObjectValue,
    stability = stability,
    epistemicStatus = epistemicStatus,
    salience = salience,
    confidence = confidence,
    timeStartMessageId = timeStartMessageId,
    timeEndMessageId = timeEndMessageId,
    branchScope = branchScope,
    sourceMessageIds = sourceMessageIds,
    evidenceQuote = evidenceQuote,
    supersedesMemoryId = supersedesMemoryId,
    tombstone = tombstone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
  )
}

fun OpenThreadEntity.toDomain(): OpenThread {
  return OpenThread(
    id = id,
    sessionId = sessionId,
    type = type,
    content = content,
    owner = owner,
    priority = priority,
    status = status,
    sourceMessageIds = sourceMessageIds,
    resolvedByMessageId = resolvedByMessageId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun OpenThread.toEntity(): OpenThreadEntity {
  return OpenThreadEntity(
    id = id,
    sessionId = sessionId,
    type = type,
    content = content,
    owner = owner,
    priority = priority,
    status = status,
    sourceMessageIds = sourceMessageIds,
    resolvedByMessageId = resolvedByMessageId,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun CompactionCacheEntity.toDomain(): CompactionCacheEntry {
  return CompactionCacheEntry(
    id = id,
    sessionId = sessionId,
    rangeStartMessageId = rangeStartMessageId,
    rangeEndMessageId = rangeEndMessageId,
    summaryType = summaryType,
    compactText = compactText,
    sourceHash = sourceHash,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
  )
}

fun CompactionCacheEntry.toEntity(): CompactionCacheEntity {
  return CompactionCacheEntity(
    id = id,
    sessionId = sessionId,
    rangeStartMessageId = rangeStartMessageId,
    rangeEndMessageId = rangeEndMessageId,
    summaryType = summaryType,
    compactText = compactText,
    sourceHash = sourceHash,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
  )
}
