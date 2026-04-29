package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.StChatRuntimeSession
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository

internal data class PromptAssemblyStack(
  val memoryContext: RoleplayMemoryContextPack,
  val promptAssembly: PromptAssemblyResult,
  val currentTurnMedia: CurrentTurnMedia,
)

internal suspend fun failForModelReadiness(
  conversationRepository: ConversationRepository,
  assistantSeed: Message,
  modelReadiness: ModelReadinessResult,
): SendRoleplayMessageResult {
  val failedMessage =
    assistantSeed.copy(
      status = if (modelReadiness.interrupted) MessageStatus.INTERRUPTED else MessageStatus.FAILED,
      errorMessage = modelReadiness.errorMessage,
      updatedAt = System.currentTimeMillis(),
    )
  conversationRepository.updateMessage(failedMessage)
  return SendRoleplayMessageResult(
    assistantMessage = failedMessage,
    interrupted = modelReadiness.interrupted,
    errorMessage = failedMessage.errorMessage,
  )
}

internal suspend fun assemblePromptForTurn(
  promptAssembler: PromptAssembler,
  runtimeRole: StChatRuntimeRole,
  runtimeSession: StChatRuntimeSession,
  memoryContext: RoleplayMemoryContextPack,
  recentMessages: List<Message>,
  trimmedInput: String,
  externalFacts: List<RoleplayExternalFact>,
  hasRuntimeTools: Boolean,
  role: RoleCard,
  contextProfile: ModelContextProfile,
  budgetMode: PromptBudgetMode,
): PromptAssemblyResult {
  return promptAssembler.assembleForSession(
    runtimeRole = runtimeRole,
    runtimeSession = runtimeSession,
    summary = memoryContext.fallbackSummary,
    memories = memoryContext.fallbackMemories,
    recentMessages = recentMessages,
    runtimeStateSnapshot = memoryContext.runtimeState,
    openThreads = memoryContext.openThreads,
    memoryAtoms = memoryContext.memoryAtoms,
    pendingUserInput = trimmedInput,
    externalFacts = memoryContext.externalFacts + externalFacts,
    hasRuntimeTools = hasRuntimeTools,
    runtimeProfile = role.runtimeProfile,
    contextProfile = contextProfile,
    budgetMode = budgetMode,
  )
}

internal fun annotateToolBackedTurn(
  message: Message,
  userMessages: List<Message>,
  externalFacts: List<RoleplayExternalFact>,
): Message {
  if (externalFacts.isEmpty()) {
    return message
  }
  val metadata =
    RoleplayToolTurnMetadata(
      userMessageIds = userMessages.map(Message::id),
      toolNames = externalFacts.map(RoleplayExternalFact::sourceToolName).distinct(),
      externalFactIds = externalFacts.map(RoleplayExternalFact::id),
      excludeFromStableSynopsis = externalFacts.any { it.ephemeral && !it.summaryEligible },
      externalFactCount = externalFacts.size,
    )
  return message.copy(
    metadataJson = mergeRoleplayToolTurnMetadata(message.metadataJson, metadata),
    updatedAt = System.currentTimeMillis(),
  )
}

internal suspend fun compileMemoryContextForTurn(
  compileRoleplayMemoryContextUseCase: CompileRoleplayMemoryContextUseCase,
  session: Session,
  role: RoleCard,
  recentMessages: List<Message>,
  pendingUserInput: String,
  contextProfile: ModelContextProfile,
  budgetMode: PromptBudgetMode,
): RoleplayMemoryContextPack {
  return compileRoleplayMemoryContextUseCase(
    session = session,
    role = role,
    recentMessages = recentMessages,
    pendingUserInput = pendingUserInput,
    contextProfile = contextProfile,
    budgetMode = budgetMode,
  )
}

internal suspend fun applyUpdatedChatMetadata(
  conversationRepository: ConversationRepository,
  session: Session,
  promptAssembly: PromptAssemblyResult,
) {
  promptAssembly.updatedChatMetadataJson
    ?.takeIf { it != session.interopChatMetadataJson }
    ?.let { updatedChatMetadataJson ->
      conversationRepository.updateSession(
        session.copy(
          interopChatMetadataJson = updatedChatMetadataJson,
          updatedAt = System.currentTimeMillis(),
        )
      )
    }
}

internal suspend fun ensureCompiledRoleRuntimeProfile(
  compileRuntimeRoleProfileUseCase: CompileRuntimeRoleProfileUseCase,
  roleRepository: RoleRepository,
  role: RoleCard,
): RoleCard {
  val runtimeProfile = role.runtimeProfile
  val needsCompilation =
    runtimeProfile == null ||
      runtimeProfile.characterKernel == null ||
      runtimeProfile.compiledCorePrompt.isBlank() ||
      runtimeProfile.sourceFingerprint.isBlank()
  if (!needsCompilation) {
    return role
  }

  val compiledRole = compileRuntimeRoleProfileUseCase(role)
  roleRepository.saveRole(compiledRole)
  return compiledRole
}

internal fun normalizeFinalMessage(message: Message): Message {
  if (message.status != MessageStatus.FAILED || !ContextOverflowRecovery.isContextOverflow(message.errorMessage)) {
    return message
  }
  return message.copy(errorMessage = ContextOverflowRecovery.toUserFacingError(message.errorMessage))
}

internal suspend fun reassemblePromptStack(
  compileRoleplayMemoryContextUseCase: CompileRoleplayMemoryContextUseCase,
  promptAssembler: PromptAssembler,
  session: Session,
  role: RoleCard,
  promptRole: RoleCard,
  runtimeRole: StChatRuntimeRole,
  runtimeSession: StChatRuntimeSession,
  recentMessages: List<Message>,
  effectiveInput: String,
  externalFacts: List<RoleplayExternalFact>,
  hasRuntimeTools: Boolean,
  contextProfile: ModelContextProfile,
  budgetMode: PromptBudgetMode,
  userMessages: List<Message>,
): PromptAssemblyStack {
  val memoryContext =
    compileMemoryContextForTurn(
      compileRoleplayMemoryContextUseCase = compileRoleplayMemoryContextUseCase,
      session = session,
      role = role,
      recentMessages = recentMessages,
      pendingUserInput = effectiveInput,
      contextProfile = contextProfile,
      budgetMode = budgetMode,
    )
  val promptAssembly =
    assemblePromptForTurn(
      promptAssembler = promptAssembler,
      runtimeRole = runtimeRole,
      runtimeSession = runtimeSession,
      memoryContext = memoryContext,
      recentMessages = recentMessages,
      trimmedInput = effectiveInput,
      externalFacts = externalFacts,
      hasRuntimeTools = hasRuntimeTools,
      role = promptRole,
      contextProfile = contextProfile,
      budgetMode = budgetMode,
    )
  val currentTurnMedia =
    loadConversationMedia(
      dialogueWindow = promptAssembly.dialogueWindow,
      currentMessages = userMessages,
    )
  return PromptAssemblyStack(
    memoryContext = memoryContext,
    promptAssembly = promptAssembly,
    currentTurnMedia = currentTurnMedia,
  )
}
