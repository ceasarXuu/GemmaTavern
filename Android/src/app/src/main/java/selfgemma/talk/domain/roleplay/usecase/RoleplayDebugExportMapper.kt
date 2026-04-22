package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayDebugBundle
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportAppInfo
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportNotes
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.model.RoleplayDebugMessageSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugRoleSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSessionEventSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSessionSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugSummarySnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugToolInvocationSnapshot
import selfgemma.talk.domain.roleplay.model.RoleplayDebugUserProfileSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.ToolInvocation

class RoleplayDebugExportMapper
@Inject
constructor() {
  fun buildBundle(
    exportedAt: Long,
    appInfo: RoleplayDebugExportAppInfo,
    session: Session,
    role: RoleCard,
    userProfile: StUserProfile,
    summary: SessionSummary?,
    messages: List<Message>,
    toolInvocations: List<ToolInvocation>,
    sessionEvents: List<SessionEvent>,
    origin: RoleplayDebugExportOrigin,
  ): RoleplayDebugBundle {
    return RoleplayDebugBundle(
      exportedAt = exportedAt,
      app = appInfo,
      session = session.toDebugSnapshot(),
      role = role.toDebugSnapshot(),
      userProfile = userProfile.toDebugSnapshot(),
      summary = summary?.toDebugSnapshot(),
      messages =
        messages
          .sortedWith(compareBy<Message>({ it.seq }, { it.createdAt }, { it.id }))
          .map(Message::toDebugSnapshot),
      toolInvocations =
        toolInvocations
          .sortedWith(compareBy<ToolInvocation>({ it.startedAt }, { it.stepIndex }, { it.id }))
          .map(ToolInvocation::toDebugSnapshot),
      sessionEvents =
        sessionEvents
          .sortedWith(compareBy<SessionEvent>({ it.createdAt }, { it.id }))
          .map(SessionEvent::toDebugSnapshot),
      notes =
        RoleplayDebugExportNotes(
          exportKind = "manual_debug_export",
          initiatedFrom = origin.rawValue,
        ),
    )
  }
}

private fun Session.toDebugSnapshot(): RoleplayDebugSessionSnapshot =
  RoleplayDebugSessionSnapshot(
    id = id,
    title = title,
    roleId = roleId,
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
  )

private fun RoleCard.toDebugSnapshot(): RoleplayDebugRoleSnapshot =
  RoleplayDebugRoleSnapshot(
    id = id,
    name = name,
    avatarUri = avatarUri,
    coverUri = coverUri,
    summary = summary,
    systemPrompt = systemPrompt,
    personaDescription = personaDescription,
    worldSettings = worldSettings,
    openingLine = openingLine,
    exampleDialogues = exampleDialogues,
    tags = tags,
    safetyPolicy = safetyPolicy,
    defaultModelId = defaultModelId,
  )

private fun StUserProfile.toDebugSnapshot(): RoleplayDebugUserProfileSnapshot =
  RoleplayDebugUserProfileSnapshot(
    activePersonaId = resolvedUserAvatarId(),
    defaultPersonaId = defaultPersonaId,
    userName = userName,
    personaTitle = personaTitle,
    personaDescription = personaDescription,
    personaDescriptionPosition = personaDescriptionPosition.name,
    personaDescriptionDepth = personaDescriptionDepth,
    personaDescriptionRole = personaDescriptionRole,
    avatarUri = activeAvatarUri,
  )

private fun SessionSummary.toDebugSnapshot(): RoleplayDebugSummarySnapshot =
  RoleplayDebugSummarySnapshot(
    version = version,
    coveredUntilSeq = coveredUntilSeq,
    summaryText = summaryText,
    tokenEstimate = tokenEstimate,
    updatedAt = updatedAt,
  )

private fun Message.toDebugSnapshot(): RoleplayDebugMessageSnapshot =
  RoleplayDebugMessageSnapshot(
    id = id,
    seq = seq,
    branchId = branchId,
    side = side.name,
    kind = kind.name,
    status = status.name,
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

private fun ToolInvocation.toDebugSnapshot(): RoleplayDebugToolInvocationSnapshot =
  RoleplayDebugToolInvocationSnapshot(
    id = id,
    turnId = turnId,
    toolName = toolName,
    source = source.name,
    status = status.name,
    stepIndex = stepIndex,
    argsJson = argsJson,
    resultJson = resultJson,
    resultSummary = resultSummary,
    artifactRefs = artifactRefs,
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt,
  )

private fun SessionEvent.toDebugSnapshot(): RoleplayDebugSessionEventSnapshot =
  RoleplayDebugSessionEventSnapshot(
    id = id,
    eventType = eventType.name,
    payloadJson = payloadJson,
    createdAt = createdAt,
  )
