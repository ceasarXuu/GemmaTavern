package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeSession
import selfgemma.talk.domain.roleplay.model.toModelContextProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.runtime.LlmModelHelper
import selfgemma.talk.runtime.runtimeHelper

data class SendRoleplayMessageResult(
  val assistantMessage: Message? = null,
  val interrupted: Boolean = false,
  val errorMessage: String? = null,
  val toolInvocations: List<ToolInvocation> = emptyList(),
  val externalFacts: List<RoleplayExternalFact> = emptyList(),
)

data class PendingRoleplayMessage(
  val session: Session,
  val userMessages: List<Message>,
  val assistantSeed: Message,
  val combinedUserInput: String,
  val externalFacts: List<RoleplayExternalFact> = emptyList(),
)

data class StagedRoleplayTurn(
  val userMessages: List<Message>,
  val assistantMessage: Message,
  val combinedUserInput: String,
)

class SendRoleplayMessageUseCase
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val toolOrchestrator: RoleplayToolOrchestrator,
  private val compileRuntimeRoleProfileUseCase: CompileRuntimeRoleProfileUseCase,
  private val promptAssembler: PromptAssembler,
  private val compileRoleplayMemoryContextUseCase: CompileRoleplayMemoryContextUseCase,
  private val summarizeSessionUseCase: SummarizeSessionUseCase,
  private val extractMemoriesUseCase: ExtractMemoriesUseCase,
  private val cloudInferenceCoordinator: CloudRoleplayInferenceCoordinator,
) {
  internal var runtimeHelperResolver: (Model) -> LlmModelHelper = { runtimeModel -> runtimeModel.runtimeHelper }

  suspend operator fun invoke(
    sessionId: String,
    model: Model,
    userInput: String,
    stagedTurn: StagedRoleplayTurn? = null,
    enableStreamingOutput: Boolean = true,
    isStopRequested: () -> Boolean,
  ): SendRoleplayMessageResult {
    val resolvedTurn =
      stagedTurn
        ?: createStagedTurn(
          conversationRepository = conversationRepository,
          sessionId = sessionId,
          model = model,
          userInputs = listOf(userInput),
        )
    val pendingMessage =
      enqueuePendingMessage(sessionId = sessionId, stagedTurn = resolvedTurn)
        ?: return SendRoleplayMessageResult(errorMessage = "Session no longer exists.")

    return completePendingMessage(
      pendingMessage = pendingMessage,
      model = model,
      enableStreamingOutput = enableStreamingOutput,
      isStopRequested = isStopRequested,
    )
  }

  suspend fun enqueuePendingMessage(
    sessionId: String,
    stagedTurn: StagedRoleplayTurn,
    persistedUserMessageIds: Set<String> = emptySet(),
  ): PendingRoleplayMessage? {
    val startTime = srmSafeElapsedRealtime()
    val trimmedInput = stagedTurn.combinedUserInput.trim()
    val hasMediaInput =
      stagedTurn.userMessages.any { it.kind == MessageKind.IMAGE || it.kind == MessageKind.AUDIO }
    if (trimmedInput.isBlank() && !hasMediaInput) {
      return null
    }

    val session = conversationRepository.getSession(sessionId) ?: return null
    srmDebugLog("queue session loaded after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId")

    val userMessages = stagedTurn.userMessages.map { it.copy(content = it.content.trim()) }
    userMessages
      .filterNot { message -> message.id in persistedUserMessageIds }
      .forEach { userMessage ->
        conversationRepository.appendMessage(userMessage)
        srmDebugLog("queued user message after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId messageId=${userMessage.id}")
      }

    val parentMessageId = userMessages.lastOrNull()?.id
    val assistantSeed =
      stagedTurn.assistantMessage.copy(
        branchId = stagedTurn.assistantMessage.branchId.ifBlank { userMessages.lastOrNull()?.branchId ?: DEFAULT_BRANCH_ID },
        accepted = false,
        isCanonical = false,
        parentMessageId = parentMessageId,
        regenerateGroupId = stagedTurn.assistantMessage.regenerateGroupId ?: parentMessageId,
      )
    conversationRepository.appendMessage(assistantSeed)
    srmDebugLog("queued assistant seed after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId messageId=${assistantSeed.id} userMessageCount=${userMessages.size}")

    return PendingRoleplayMessage(
      session = session,
      userMessages = userMessages,
      assistantSeed = assistantSeed,
      combinedUserInput = trimmedInput,
    )
  }

  suspend fun completePendingMessage(
    pendingMessage: PendingRoleplayMessage,
    model: Model,
    enableStreamingOutput: Boolean = true,
    isStopRequested: () -> Boolean,
  ): SendRoleplayMessageResult {
    val startTime = srmSafeElapsedRealtime()
    val sessionId = pendingMessage.session.id
    val session = pendingMessage.session
    val turnToolContext =
      prepareTurnToolContext(
        toolOrchestrator = toolOrchestrator,
        conversationRepository = conversationRepository,
        pendingMessage = pendingMessage,
        model = model,
        enableStreamingOutput = enableStreamingOutput,
        isStopRequested = isStopRequested,
      )
    var userMessages = pendingMessage.userMessages
    val assistantSeed = pendingMessage.assistantSeed
    var effectiveInput = pendingMessage.combinedUserInput
    var localModelReadiness: ModelReadinessResult? = null
    suspend fun awaitLocalModelReady(reason: String): ModelReadinessResult {
      val cached = localModelReadiness
      if (cached != null) return cached
      val resolved = awaitModelReady(model = model, isStopRequested = isStopRequested)
      localModelReadiness = resolved
      srmDebugLog(
        "local model readiness resolved after ${srmSafeElapsedRealtime() - startTime}ms sessionId=$sessionId reason=$reason ready=${resolved.ready} interrupted=${resolved.interrupted}",
      )
      return resolved
    }
    suspend fun failReadiness(readiness: ModelReadinessResult) =
      failForModelReadiness(conversationRepository, assistantSeed, readiness)

    val storedRole = roleRepository.getRole(session.roleId)
    var role = storedRole
    if (role == null) {
      val failedMessage =
        assistantSeed.copy(
          status = MessageStatus.FAILED,
          errorMessage = "Role data is missing for this session.",
          updatedAt = System.currentTimeMillis(),
        )
      conversationRepository.updateMessage(failedMessage)
      return SendRoleplayMessageResult(
        assistantMessage = failedMessage,
        errorMessage = failedMessage.errorMessage,
      )
    }
    role = ensureCompiledRoleRuntimeProfile(compileRuntimeRoleProfileUseCase, roleRepository, role)

    if (requiresLocalMediaContext(userMessages)) {
      val mediaReadiness = awaitLocalModelReady(reason = "media_context")
      if (!mediaReadiness.ready) return failReadiness(mediaReadiness)
      userMessages =
        ensureCurrentMediaAttachmentContextTexts(
          conversationRepository = conversationRepository,
          runtimeHelper = runtimeHelperFor(model),
          userMessages = userMessages,
          model = model,
          sessionId = sessionId,
          isStopRequested = isStopRequested,
        )
    }

    val recentMessages =
      conversationRepository.listCanonicalMessages(sessionId).filter { message ->
        message.id != assistantSeed.id && userMessages.none { userMessage -> userMessage.id == message.id }
      }
    val runtimeRole =
      role.toStChatRuntimeRole(
        userProfile = session.resolveUserProfile(dataStoreRepository.getStUserProfile()),
      )
    val runtimeSession = session.toStChatRuntimeSession(generationTrigger = "normal")
    val contextProfile = model.toModelContextProfile()
    val promptRole = buildStyleRepairRole(conversationRepository, sessionId, role, recentMessages)
    var attemptMode = PromptBudgetMode.FULL
    suspend fun reassemble(input: String, mode: PromptBudgetMode): PromptAssemblyStack =
      reassemblePromptStack(
        compileRoleplayMemoryContextUseCase = compileRoleplayMemoryContextUseCase,
        promptAssembler = promptAssembler,
        session = session,
        role = role,
        promptRole = promptRole,
        runtimeRole = runtimeRole,
        runtimeSession = runtimeSession,
        recentMessages = recentMessages,
        effectiveInput = input,
        externalFacts = pendingMessage.externalFacts,
        hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
        contextProfile = contextProfile,
        budgetMode = mode,
        userMessages = userMessages,
      )
    var stack = reassemble(effectiveInput, attemptMode)
    val overflowAwareInput = mergeUserInputWithOverflowText(effectiveInput, stack.currentTurnMedia.overflowText)
    if (overflowAwareInput != effectiveInput) {
      srmDebugLog(
        "applying overflow media context text sessionId=$sessionId overflowChars=${stack.currentTurnMedia.overflowText.length}",
      )
      effectiveInput = overflowAwareInput
      stack = reassemble(effectiveInput, attemptMode)
    }
    if (ContextOverflowRecovery.shouldUseAggressiveModePreflight(stack.promptAssembly.budgetReport)) {
      attemptMode = PromptBudgetMode.AGGRESSIVE
      srmWarnLog(
        "prompt preflight overflow sessionId=$sessionId estimatedTokens=${stack.promptAssembly.budgetReport?.estimatedInputTokens} usableTokens=${stack.promptAssembly.budgetReport?.usableInputTokens} switchingTo=$attemptMode",
      )
      stack = reassemble(effectiveInput, attemptMode)
    }
    suspend fun appendBudget() =
      appendBudgetEventIfNeeded(conversationRepository, sessionId, stack.promptAssembly.budgetReport)
    suspend fun applyMetadata() =
      applyUpdatedChatMetadata(conversationRepository, session, stack.promptAssembly)
    suspend fun appendRecovery(stage: String, retry: Int) =
      appendOverflowRecoveryEvent(conversationRepository, sessionId, stage, retry, stack.promptAssembly.budgetReport)

    appendBudget()
    applyMetadata()

    var finalMessage: Message? =
      when (
        val cloudOutcome =
          cloudInferenceCoordinator.tryGenerate(
            CloudRoleplayInferenceRequest(
              sessionId = sessionId,
              assistantSeed = assistantSeed,
              promptAssembly = stack.promptAssembly,
              input = effectiveInput,
              userMessages = userMessages,
              currentTurnMedia = stack.currentTurnMedia,
              turnToolContext = turnToolContext,
              enableStreamingOutput = enableStreamingOutput,
              isStopRequested = isStopRequested,
            )
          )
      ) {
        is CloudRoleplayInferenceOutcome.Completed -> cloudOutcome.message
        else -> null
      }
    if (finalMessage == null) {
      val fallbackReadiness = awaitLocalModelReady(reason = "local_generation")
      if (!fallbackReadiness.ready) return failReadiness(fallbackReadiness)
      var overflowRetries = 0
      while (true) {
        applyMetadata()
        val preparationResult =
          prepareConversation(
            runtimeHelper = runtimeHelperFor(model),
            assistantSeed = assistantSeed,
            model = model,
            promptAssembly = stack.promptAssembly,
            turnToolContext = turnToolContext,
            currentTurnMedia = stack.currentTurnMedia,
            sessionId = sessionId,
            recentMessages = recentMessages,
            memoryContext = stack.memoryContext,
            trigger = runtimeSession.generationTrigger,
            startTime = startTime,
          )
        if (preparationResult.failureMessage != null) {
          if (
            preparationResult.overflowDetected &&
              overflowRetries < ContextOverflowRecovery.MAX_OVERFLOW_RETRIES
          ) {
            overflowRetries += 1
            attemptMode = PromptBudgetMode.AGGRESSIVE
            srmWarnLog(
              "context overflow during reset sessionId=$sessionId retry=$overflowRetries message=${preparationResult.failureMessage.errorMessage}",
            )
            appendRecovery("reset", overflowRetries)
            stack = reassemble(effectiveInput, attemptMode)
            appendBudget()
            continue
          }
          val failedMessage = preparationResult.failureMessage
          conversationRepository.updateMessage(failedMessage)
          return SendRoleplayMessageResult(
            assistantMessage = failedMessage,
            errorMessage = failedMessage.errorMessage,
          )
        }

        val inferenceResult =
          runInferenceAttempt(
            runtimeHelper = runtimeHelperFor(model),
            conversationRepository = conversationRepository,
            assistantSeed = assistantSeed,
            model = model,
            input = effectiveInput,
            currentTurnMedia = stack.currentTurnMedia,
            role = role,
            sessionId = sessionId,
            startTime = startTime,
            enableStreamingOutput = enableStreamingOutput,
            isStopRequested = isStopRequested,
          )
        finalMessage = inferenceResult.message
        if (
          !inferenceResult.overflowDetected ||
            finalMessage.status == MessageStatus.INTERRUPTED ||
            overflowRetries >= ContextOverflowRecovery.MAX_OVERFLOW_RETRIES
        ) {
          break
        }

        overflowRetries += 1
        attemptMode = PromptBudgetMode.AGGRESSIVE
        srmWarnLog(
          "context overflow retry sessionId=$sessionId retry=$overflowRetries message=${finalMessage.errorMessage}",
        )
        appendRecovery("inference", overflowRetries)
        stack = reassemble(effectiveInput, attemptMode)
        appendBudget()
      }
    }
    val runtimeToolInvocations = turnToolContext.collector.snapshotInvocations()
    val runtimeExternalFacts = turnToolContext.collector.snapshotExternalFacts()
    val effectiveExternalFacts = pendingMessage.externalFacts + runtimeExternalFacts
    finalMessage =
      annotateToolBackedTurn(
        message = normalizeFinalMessage(checkNotNull(finalMessage)),
        userMessages = userMessages,
        externalFacts = effectiveExternalFacts,
      )
    conversationRepository.updateMessage(finalMessage)

    if (finalMessage.status == MessageStatus.COMPLETED) {
      finalMessage =
        conversationRepository.acceptAssistantMessage(
          messageId = finalMessage.id,
          acceptedAt = System.currentTimeMillis(),
        ) ?: finalMessage.copy(
          accepted = true,
          isCanonical = true,
          updatedAt = System.currentTimeMillis(),
        )
      summarizeSessionUseCase(sessionId)
      val memorySourceUserMessage = userMessages.lastOrNull { it.kind == MessageKind.TEXT } ?: userMessages.last()
      if (effectiveExternalFacts.any { it.ephemeral }) {
        srmDebugLog(
          "skipping auto memory extraction for tool-augmented turn sessionId=$sessionId facts=${effectiveExternalFacts.size}",
        )
        appendToolMemoryGuardEvent(
          conversationRepository = conversationRepository,
          sessionId = sessionId,
          toolNames = effectiveExternalFacts.map { it.sourceToolName },
        )
      } else {
        extractMemoriesUseCase(
          session = session,
          role = role,
          userMessage = memorySourceUserMessage,
          assistantMessage = finalMessage,
        )
      }
      appendDriftEventIfNeeded(
        conversationRepository = conversationRepository,
        sessionId = sessionId,
        role = role,
        recentMessages = recentMessages,
        assistantMessage = finalMessage,
      )
    }

    return SendRoleplayMessageResult(
      assistantMessage = finalMessage,
      interrupted = finalMessage.status == MessageStatus.INTERRUPTED,
      errorMessage = finalMessage.errorMessage,
      toolInvocations = runtimeToolInvocations,
      externalFacts = runtimeExternalFacts,
    )
  }

  private fun runtimeHelperFor(model: Model): LlmModelHelper = runtimeHelperResolver(model)
}
