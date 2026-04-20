package selfgemma.talk.data.roleplay.mapper

import selfgemma.talk.data.roleplay.db.entity.MemoryEntity
import selfgemma.talk.data.roleplay.db.entity.MessageEntity
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEventEntity
import selfgemma.talk.data.roleplay.db.entity.SessionSummaryEntity
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.resolvedDescription
import selfgemma.talk.domain.roleplay.model.resolvedFirstMessage
import selfgemma.talk.domain.roleplay.model.resolvedMessageExample
import selfgemma.talk.domain.roleplay.model.resolvedName
import selfgemma.talk.domain.roleplay.model.resolvedPersonality
import selfgemma.talk.domain.roleplay.model.resolvedScenario
import selfgemma.talk.domain.roleplay.model.resolvedSystemPrompt
import selfgemma.talk.domain.roleplay.model.resolvedTags

fun RoleEntity.toDomain(): RoleCard {
  val stCard = toRoleCardCoreOrLegacy()
  val runtimeProfile = toRoleRuntimeProfileOrLegacy()
  val mediaProfile = toRoleMediaProfileOrLegacy()
  val interopState = toRoleInteropStateOrDefault()
  val resolvedAvatarUri = mediaProfile.primaryAvatar?.uri ?: avatarUri
  val resolvedCoverUri = mediaProfile.coverImage?.uri ?: coverUri
  return RoleCard(
    id = id,
    stCard = stCard,
    avatarUri = resolvedAvatarUri,
    coverUri = resolvedCoverUri,
    safetyPolicy = safetyPolicy,
    defaultModelId = defaultModelId,
    defaultTemperature = defaultTemperature,
    defaultTopP = defaultTopP,
    defaultTopK = defaultTopK,
    enableThinking = enableThinking,
    summaryTurnThreshold = summaryTurnThreshold,
    memoryEnabled = memoryEnabled,
    memoryMaxItems = memoryMaxItems,
    runtimeProfile = runtimeProfile,
    mediaProfile = mediaProfile,
    interopState = interopState,
    builtIn = builtIn,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun RoleCard.toEntity(): RoleEntity {
  val stCard = toPersistedRoleCardCore()
  val runtimeProfile = toPersistedRoleRuntimeProfile()
  val mediaProfile = toPersistedRoleMediaProfile()
  val interopState = toPersistedRoleInteropState()
  return RoleEntity(
    id = id,
    name = stCard.resolvedName(),
    avatarUri = mediaProfile?.primaryAvatar?.uri ?: avatarUri,
    coverUri = mediaProfile?.coverImage?.uri ?: coverUri,
    summary = stCard.resolvedDescription(),
    systemPrompt = stCard.resolvedSystemPrompt(),
    personaDescription = stCard.resolvedPersonality(),
    worldSettings = stCard.resolvedScenario(),
    openingLine = stCard.resolvedFirstMessage(),
    exampleDialogues = stCard.resolvedMessageExample().split("\n\n").map(String::trim).filter(String::isNotBlank),
    safetyPolicy = safetyPolicy,
    defaultModelId = defaultModelId,
    defaultTemperature = defaultTemperature,
    defaultTopP = defaultTopP,
    defaultTopK = defaultTopK,
    enableThinking = enableThinking,
    summaryTurnThreshold = summaryTurnThreshold,
    memoryEnabled = memoryEnabled,
    memoryMaxItems = memoryMaxItems,
    tags = stCard.resolvedTags(),
    cardCoreJson = RoleplayInteropJsonCodec.encodeRoleCardCore(stCard),
    runtimeProfileJson = RoleplayInteropJsonCodec.encodeRoleRuntimeProfile(runtimeProfile),
    mediaProfileJson = RoleplayInteropJsonCodec.encodeRoleMediaProfile(mediaProfile),
    interopStateJson = RoleplayInteropJsonCodec.encodeRoleInteropState(interopState),
    builtIn = builtIn,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun SessionEntity.toDomain(): Session {
  return Session(
    id = id,
    roleId = roleId,
    title = title,
    activeModelId = activeModelId,
    pinned = pinned,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessageAt = lastMessageAt,
    lastSummary = lastSummary,
    lastUserMessageExcerpt = lastUserMessageExcerpt,
    lastAssistantMessageExcerpt = lastAssistantMessageExcerpt,
    turnCount = turnCount,
    summaryVersion = summaryVersion,
    draftInput = draftInput,
    interopChatMetadataJson = interopChatMetadataJson,
    sessionUserProfile = RoleplayInteropJsonCodec.decodeStUserProfile(sessionUserProfileJson),
  )
}

fun Session.toEntity(): SessionEntity {
  return SessionEntity(
    id = id,
    roleId = roleId,
    title = title,
    activeModelId = activeModelId,
    pinned = pinned,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessageAt = lastMessageAt,
    lastSummary = lastSummary,
    lastUserMessageExcerpt = lastUserMessageExcerpt,
    lastAssistantMessageExcerpt = lastAssistantMessageExcerpt,
    turnCount = turnCount,
    summaryVersion = summaryVersion,
    draftInput = draftInput,
    interopChatMetadataJson = interopChatMetadataJson,
    sessionUserProfileJson = RoleplayInteropJsonCodec.encodeStUserProfile(sessionUserProfile),
  )
}

fun MessageEntity.toDomain(): Message {
  return Message(
    id = id,
    sessionId = sessionId,
    seq = seq,
    branchId = branchId,
    side = side,
    kind = kind,
    status = status,
    accepted = accepted,
    isCanonical = isCanonical,
    content = content,
    isMarkdown = isMarkdown,
    errorMessage = errorMessage,
    latencyMs = latencyMs,
    accelerator = accelerator,
    parentMessageId = parentMessageId,
    regenerateGroupId = regenerateGroupId,
    editedFromMessageId = editedFromMessageId,
    supersededMessageId = supersededMessageId,
    metadataJson = metadataJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun Message.toEntity(): MessageEntity {
  return MessageEntity(
    id = id,
    sessionId = sessionId,
    seq = seq,
    branchId = branchId,
    side = side,
    kind = kind,
    status = status,
    accepted = accepted,
    isCanonical = isCanonical,
    content = content,
    isMarkdown = isMarkdown,
    errorMessage = errorMessage,
    latencyMs = latencyMs,
    accelerator = accelerator,
    parentMessageId = parentMessageId,
    regenerateGroupId = regenerateGroupId,
    editedFromMessageId = editedFromMessageId,
    supersededMessageId = supersededMessageId,
    metadataJson = metadataJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
  )
}

fun SessionSummaryEntity.toDomain(): SessionSummary {
  return SessionSummary(
    sessionId = sessionId,
    version = version,
    coveredUntilSeq = coveredUntilSeq,
    summaryText = summaryText,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
  )
}

fun SessionSummary.toEntity(): SessionSummaryEntity {
  return SessionSummaryEntity(
    sessionId = sessionId,
    version = version,
    coveredUntilSeq = coveredUntilSeq,
    summaryText = summaryText,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
  )
}

fun MemoryEntity.toDomain(): MemoryItem {
  return MemoryItem(
    id = id,
    roleId = roleId,
    sessionId = sessionId,
    category = category,
    content = content,
    normalizedHash = normalizedHash,
    confidence = confidence,
    pinned = pinned,
    active = active,
    sourceMessageIds = sourceMessageIds,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
  )
}

fun MemoryItem.toEntity(): MemoryEntity {
  return MemoryEntity(
    id = id,
    roleId = roleId,
    sessionId = sessionId,
    category = category,
    content = content,
    normalizedHash = normalizedHash,
    confidence = confidence,
    pinned = pinned,
    active = active,
    sourceMessageIds = sourceMessageIds,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
  )
}

fun SessionEventEntity.toDomain(): SessionEvent {
  return SessionEvent(
    id = id,
    sessionId = sessionId,
    eventType = eventType,
    payloadJson = payloadJson,
    createdAt = createdAt,
  )
}

fun SessionEvent.toEntity(): SessionEventEntity {
  return SessionEventEntity(
    id = id,
    sessionId = sessionId,
    eventType = eventType,
    payloadJson = payloadJson,
    createdAt = createdAt,
  )
}
