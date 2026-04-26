package selfgemma.talk.domain.roleplay.usecase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.pcm16MonoToWav
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
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

private data class ModelReadinessResult(
  val ready: Boolean,
  val interrupted: Boolean = false,
  val errorMessage: String? = null,
)

private data class InferenceAttemptResult(
  val message: Message,
  val overflowDetected: Boolean,
)

private data class ConversationPreparationResult(
  val failureMessage: Message? = null,
  val overflowDetected: Boolean = false,
)

private data class DriftAnalysis(
  val signals: List<String>,
  val tabooMatches: List<String>,
  val currentAverageSentenceLength: Int,
  val recentAverageSentenceLength: Int,
)

private data class StyleRepairDirective(
  val sourceMessageId: String,
  val driftEventCreatedAt: Long,
  val signals: List<String>,
  val tabooMatches: List<String>,
  val prompt: String,
)

internal data class CurrentTurnMedia(
  val images: List<Bitmap> = emptyList(),
  val audioClips: List<ByteArray> = emptyList(),
  val historicalImageCount: Int = 0,
  val currentImageCount: Int = 0,
  val historicalAudioCount: Int = 0,
  val currentAudioCount: Int = 0,
  val overflowText: String = "",
)

private const val TAG = "SendRoleplayMessage"
private const val DEFAULT_BRANCH_ID = "main"
private const val IMAGE_CONTEXT_TEXT_MAX_CHARS = 240
private const val STYLE_REPAIR_PROMPT_MAX_CHARS = 420
private const val STYLE_REPAIR_SAMPLE_MAX_CHARS = 72
private const val DRIFT_BASELINE_ASSISTANT_COUNT = 3
private const val DRIFT_MIN_BASELINE_SENTENCE_LENGTH = 18
private const val DRIFT_MIN_SENTENCE_DELTA = 18
private const val IMAGE_CONTEXT_SYSTEM_PROMPT =
  "You are extracting persistent visual memory for a continuing roleplay chat. " +
    "Describe only what is visibly present so the chat can remember the image later even when the raw image is not resent."
private const val IMAGE_CONTEXT_USER_PROMPT =
  "Return one short plain-text sentence with the key visible details, including any readable text or numbers. " +
    "Do not use markdown, bullets, or speculation."
private val ASSISTANT_META_PATTERNS =
  listOf(
    Regex("\\bas an ai\\b", RegexOption.IGNORE_CASE),
    Regex("\\bas (?:your )?assistant\\b", RegexOption.IGNORE_CASE),
    Regex("\\blanguage model\\b", RegexOption.IGNORE_CASE),
    Regex("\\bi(?:'m| am) here to help\\b", RegexOption.IGNORE_CASE),
    Regex("\\bhow can i (?:help|assist)\\b", RegexOption.IGNORE_CASE),
  )
private val SYSTEM_EXPLANATION_PATTERNS =
  listOf(
    Regex("\\bsystem prompt\\b", RegexOption.IGNORE_CASE),
    Regex("\\bdeveloper (?:message|instruction)s?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bcontext window\\b", RegexOption.IGNORE_CASE),
    Regex("\\bconversation history\\b", RegexOption.IGNORE_CASE),
    Regex("\\btoken(?:s)?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bprompt injection\\b", RegexOption.IGNORE_CASE),
  )
private val OOC_PATTERNS =
  listOf(
    Regex("\\[ooc\\]", RegexOption.IGNORE_CASE),
    Regex("\\booc\\s*:", RegexOption.IGNORE_CASE),
    Regex("\\bout of character\\b", RegexOption.IGNORE_CASE),
    Regex("\\bmeta note\\b", RegexOption.IGNORE_CASE),
  )
private val SENTENCE_BREAK_REGEX = Regex("[.!?。！？\\n]+")
private val MULTI_SPACE_REGEX = Regex("\\s+")

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
  companion object {
    private const val MODEL_READY_TIMEOUT_MS = 60_000L
    private const val MODEL_READY_POLL_INTERVAL_MS = 50L
  }

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
      stagedTurn ?: createStagedTurn(sessionId = sessionId, model = model, userInputs = listOf(userInput))
    val pendingMessage =
      enqueuePendingMessage(
        sessionId = sessionId,
        stagedTurn = resolvedTurn,
      ) ?: return SendRoleplayMessageResult(errorMessage = "Session no longer exists.")

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
    val startTime = safeElapsedRealtime()
    val trimmedInput = stagedTurn.combinedUserInput.trim()
    val hasMediaInput =
      stagedTurn.userMessages.any { it.kind == MessageKind.IMAGE || it.kind == MessageKind.AUDIO }
    if (trimmedInput.isBlank() && !hasMediaInput) {
      return null
    }

    val session = conversationRepository.getSession(sessionId)
    if (session == null) {
      return null
    }
    debugLog("queue session loaded after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId")

    val userMessages = stagedTurn.userMessages.map { it.copy(content = it.content.trim()) }
    userMessages
      .filterNot { message -> message.id in persistedUserMessageIds }
      .forEach { userMessage ->
        conversationRepository.appendMessage(userMessage)
        debugLog("queued user message after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId messageId=${userMessage.id}")
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
    debugLog("queued assistant seed after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId messageId=${assistantSeed.id} userMessageCount=${userMessages.size}")

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
    val startTime = safeElapsedRealtime()
    val sessionId = pendingMessage.session.id
    val session = pendingMessage.session
    val turnToolContext =
      prepareTurnToolContext(
        pendingMessage = pendingMessage,
        model = model,
        enableStreamingOutput = enableStreamingOutput,
        isStopRequested = isStopRequested,
      )
    var userMessages = pendingMessage.userMessages
    val assistantSeed = pendingMessage.assistantSeed
    var effectiveInput = pendingMessage.combinedUserInput
    val modelReadiness = awaitModelReady(model = model, isStopRequested = isStopRequested)
    debugLog(
      "model readiness resolved after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId ready=${modelReadiness.ready} interrupted=${modelReadiness.interrupted}",
    )
    if (!modelReadiness.ready) {
      val pendingMessage =
        assistantSeed.copy(
          status = if (modelReadiness.interrupted) MessageStatus.INTERRUPTED else MessageStatus.FAILED,
          errorMessage = modelReadiness.errorMessage,
          updatedAt = System.currentTimeMillis(),
        )
      conversationRepository.updateMessage(pendingMessage)
      return SendRoleplayMessageResult(
        assistantMessage = pendingMessage,
        interrupted = modelReadiness.interrupted,
        errorMessage = pendingMessage.errorMessage,
      )
    }

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
    role = ensureCompiledRoleRuntimeProfile(role)

    userMessages =
      ensureCurrentImageAttachmentContextTexts(
        userMessages = userMessages,
        model = model,
        sessionId = sessionId,
        isStopRequested = isStopRequested,
      )

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
    val promptRole = buildStyleRepairRole(sessionId = sessionId, role = role, recentMessages = recentMessages)
    var attemptMode = PromptBudgetMode.FULL
    var memoryContext =
      compileMemoryContext(
        session = session,
        role = role,
        recentMessages = recentMessages,
        pendingUserInput = effectiveInput,
        contextProfile = contextProfile,
        budgetMode = attemptMode,
      )
    var promptAssembly =
      assemblePrompt(
        runtimeRole = runtimeRole,
        runtimeSession = runtimeSession,
        memoryContext = memoryContext,
        recentMessages = recentMessages,
        trimmedInput = effectiveInput,
        externalFacts = pendingMessage.externalFacts,
        hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
        role = promptRole,
        contextProfile = contextProfile,
        budgetMode = attemptMode,
      )
    var currentTurnMedia =
      loadConversationMedia(
        dialogueWindow = promptAssembly.dialogueWindow,
        currentMessages = userMessages,
      )
    val overflowAwareInput = mergeUserInputWithOverflowText(effectiveInput, currentTurnMedia.overflowText)
    if (overflowAwareInput != effectiveInput) {
      debugLog(
        "applying overflow media context text sessionId=$sessionId overflowChars=${currentTurnMedia.overflowText.length}",
      )
      effectiveInput = overflowAwareInput
      memoryContext =
        compileMemoryContext(
          session = session,
          role = role,
          recentMessages = recentMessages,
          pendingUserInput = effectiveInput,
          contextProfile = contextProfile,
          budgetMode = attemptMode,
        )
      promptAssembly =
        assemblePrompt(
          runtimeRole = runtimeRole,
          runtimeSession = runtimeSession,
          memoryContext = memoryContext,
          recentMessages = recentMessages,
          trimmedInput = effectiveInput,
          externalFacts = pendingMessage.externalFacts,
          hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
          role = promptRole,
          contextProfile = contextProfile,
          budgetMode = attemptMode,
        )
      currentTurnMedia =
        loadConversationMedia(
          dialogueWindow = promptAssembly.dialogueWindow,
          currentMessages = userMessages,
        )
    }
    if (ContextOverflowRecovery.shouldUseAggressiveModePreflight(promptAssembly.budgetReport)) {
      attemptMode = PromptBudgetMode.AGGRESSIVE
      warnLog(
        "prompt preflight overflow sessionId=$sessionId estimatedTokens=${promptAssembly.budgetReport?.estimatedInputTokens} usableTokens=${promptAssembly.budgetReport?.usableInputTokens} switchingTo=$attemptMode",
      )
      memoryContext =
        compileMemoryContext(
          session = session,
          role = role,
          recentMessages = recentMessages,
          pendingUserInput = effectiveInput,
          contextProfile = contextProfile,
          budgetMode = attemptMode,
        )
      promptAssembly =
        assemblePrompt(
          runtimeRole = runtimeRole,
          runtimeSession = runtimeSession,
          memoryContext = memoryContext,
          recentMessages = recentMessages,
          trimmedInput = effectiveInput,
          externalFacts = pendingMessage.externalFacts,
          hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
          role = promptRole,
          contextProfile = contextProfile,
          budgetMode = attemptMode,
        )
      currentTurnMedia =
        loadConversationMedia(
          dialogueWindow = promptAssembly.dialogueWindow,
          currentMessages = userMessages,
        )
    }
    appendBudgetEventIfNeeded(sessionId = sessionId, report = promptAssembly.budgetReport)
    applyUpdatedChatMetadata(session = session, promptAssembly = promptAssembly)

    var finalMessage: Message? =
      when (
        val cloudOutcome =
          cloudInferenceCoordinator.tryGenerate(
            CloudRoleplayInferenceRequest(
              sessionId = sessionId,
              assistantSeed = assistantSeed,
              promptAssembly = promptAssembly,
              input = effectiveInput,
              userMessages = userMessages,
              currentTurnMedia = currentTurnMedia,
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
      var overflowRetries = 0
      while (true) {
        applyUpdatedChatMetadata(session = session, promptAssembly = promptAssembly)
        val preparationResult =
          prepareConversation(
            assistantSeed = assistantSeed,
            model = model,
            promptAssembly = promptAssembly,
            turnToolContext = turnToolContext,
            currentTurnMedia = currentTurnMedia,
            sessionId = sessionId,
            recentMessages = recentMessages,
            memoryContext = memoryContext,
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
            warnLog(
              "context overflow during reset sessionId=$sessionId retry=$overflowRetries message=${preparationResult.failureMessage.errorMessage}",
            )
            appendOverflowRecoveryEvent(
              sessionId = sessionId,
              stage = "reset",
              retry = overflowRetries,
              report = promptAssembly.budgetReport,
            )
            memoryContext =
              compileMemoryContext(
                session = session,
                role = role,
                recentMessages = recentMessages,
                pendingUserInput = effectiveInput,
                contextProfile = contextProfile,
                budgetMode = attemptMode,
              )
            promptAssembly =
              assemblePrompt(
                runtimeRole = runtimeRole,
                runtimeSession = runtimeSession,
                memoryContext = memoryContext,
                recentMessages = recentMessages,
                trimmedInput = effectiveInput,
                externalFacts = pendingMessage.externalFacts,
                hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
                role = promptRole,
                contextProfile = contextProfile,
                budgetMode = attemptMode,
              )
            currentTurnMedia =
              loadConversationMedia(
                dialogueWindow = promptAssembly.dialogueWindow,
                currentMessages = userMessages,
              )
            appendBudgetEventIfNeeded(sessionId = sessionId, report = promptAssembly.budgetReport)
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
            assistantSeed = assistantSeed,
            model = model,
            input = effectiveInput,
            currentTurnMedia = currentTurnMedia,
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
        warnLog(
          "context overflow retry sessionId=$sessionId retry=$overflowRetries message=${finalMessage.errorMessage}",
        )
        appendOverflowRecoveryEvent(
          sessionId = sessionId,
          stage = "inference",
          retry = overflowRetries,
          report = promptAssembly.budgetReport,
        )
        memoryContext =
          compileMemoryContext(
            session = session,
            role = role,
            recentMessages = recentMessages,
            pendingUserInput = effectiveInput,
            contextProfile = contextProfile,
            budgetMode = attemptMode,
          )
        promptAssembly =
          assemblePrompt(
            runtimeRole = runtimeRole,
            runtimeSession = runtimeSession,
            memoryContext = memoryContext,
            recentMessages = recentMessages,
            trimmedInput = effectiveInput,
            externalFacts = pendingMessage.externalFacts,
            hasRuntimeTools = turnToolContext.tools.isNotEmpty(),
            role = promptRole,
            contextProfile = contextProfile,
            budgetMode = attemptMode,
          )
        currentTurnMedia =
          loadConversationMedia(
            dialogueWindow = promptAssembly.dialogueWindow,
            currentMessages = userMessages,
          )
        appendBudgetEventIfNeeded(sessionId = sessionId, report = promptAssembly.budgetReport)
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
        debugLog(
          "skipping auto memory extraction for tool-augmented turn sessionId=$sessionId facts=${effectiveExternalFacts.size}",
        )
        appendToolMemoryGuardEvent(
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

  private suspend fun assemblePrompt(
    runtimeRole: selfgemma.talk.domain.roleplay.model.StChatRuntimeRole,
    runtimeSession: selfgemma.talk.domain.roleplay.model.StChatRuntimeSession,
    memoryContext: RoleplayMemoryContextPack,
    recentMessages: List<Message>,
    trimmedInput: String,
    externalFacts: List<RoleplayExternalFact>,
    hasRuntimeTools: Boolean,
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    contextProfile: selfgemma.talk.domain.roleplay.model.ModelContextProfile,
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

  private fun annotateToolBackedTurn(
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

  private suspend fun compileMemoryContext(
    session: Session,
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
    pendingUserInput: String,
    contextProfile: selfgemma.talk.domain.roleplay.model.ModelContextProfile,
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

  private suspend fun applyUpdatedChatMetadata(session: Session, promptAssembly: PromptAssemblyResult) {
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

  private suspend fun ensureCompiledRoleRuntimeProfile(
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
  ): selfgemma.talk.domain.roleplay.model.RoleCard {
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

  private suspend fun appendBudgetEventIfNeeded(sessionId: String, report: PromptBudgetReport?) {
    if (
      report == null ||
        report.mode == PromptBudgetMode.FULL ||
        (report.compactedSectionIds.isEmpty() && report.droppedSectionIds.isEmpty())
    ) {
      return
    }
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.CONTEXT_BUDGET_APPLIED,
        payloadJson =
          """{"mode":"${report.mode.name}","estimatedInputTokens":${report.estimatedInputTokens},"usableInputTokens":${report.usableInputTokens},"compactedSectionCount":${report.compactedSectionIds.size},"droppedSectionCount":${report.droppedSectionIds.size}}""",
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun appendToolMemoryGuardEvent(sessionId: String, toolNames: List<String>) {
    val payload =
      JsonObject().apply {
        addProperty("reason", "ephemeral_tool_fact_guard")
        addProperty("toolCount", toolNames.size)
        add(
          "toolNames",
          JsonArray().apply {
            toolNames.distinct().forEach(::add)
          },
        )
      }
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.MEMORY_OP_REJECTED,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun appendToolPreparationFailureEvent(
    sessionId: String,
    turnId: String,
    errorMessage: String,
  ) {
    val payload =
      JsonObject().apply {
        addProperty("turnId", turnId)
        addProperty("toolName", "__tool_context__")
        addProperty("status", "FAILED")
        addProperty("stepIndex", -1)
        addProperty("errorMessage", errorMessage)
      }
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.TOOL_CALL_FAILED,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun appendOverflowRecoveryEvent(
    sessionId: String,
    stage: String,
    retry: Int,
    report: PromptBudgetReport?,
  ) {
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.CONTEXT_OVERFLOW_RECOVERED,
        payloadJson =
          """{"stage":"$stage","retry":$retry,"mode":"${report?.mode?.name ?: PromptBudgetMode.AGGRESSIVE.name}","estimatedInputTokens":${report?.estimatedInputTokens ?: -1},"usableInputTokens":${report?.usableInputTokens ?: -1}}""",
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private suspend fun buildStyleRepairRole(
    sessionId: String,
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
  ): selfgemma.talk.domain.roleplay.model.RoleCard {
    val runtimeProfile = role.runtimeProfile ?: return role
    val events = conversationRepository.listEvents(sessionId)
    val directive =
      buildStyleRepairDirective(
        role = role,
        recentMessages = recentMessages,
        events = events,
      )
    val updatedProfile =
      runtimeProfile.copy(
        styleRepairPrompt = directive?.prompt.orEmpty(),
      )
    if (directive == null) {
      return if (updatedProfile == runtimeProfile) role else role.copy(runtimeProfile = updatedProfile)
    }

    val repairAlreadyLogged =
      events.any { event ->
        event.eventType == SessionEventType.ROLE_STYLE_REPAIR_APPLIED &&
          event.createdAt >= directive.driftEventCreatedAt &&
          event.payloadJson
            .toJsonObjectOrNull()
            ?.get("sourceMessageId")
            ?.asString
            ?.trim() == directive.sourceMessageId
      }
    if (!repairAlreadyLogged) {
      appendRoleplayEvent(
        sessionId = sessionId,
        eventType = SessionEventType.ROLE_STYLE_REPAIR_APPLIED,
        payload =
          JsonObject().apply {
            addProperty("sourceMessageId", directive.sourceMessageId)
            add("signals", directive.signals.toJsonArray())
            add("tabooMatches", directive.tabooMatches.toJsonArray())
            addProperty("prompt", directive.prompt)
          },
      )
    }
    return role.copy(runtimeProfile = updatedProfile)
  }

  private fun buildStyleRepairDirective(
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
    events: List<SessionEvent>,
  ): StyleRepairDirective? {
    val driftEvent =
      events.lastOrNull { event -> event.eventType == SessionEventType.ROLE_DRIFT_DETECTED } ?: return null
    val driftPayload = driftEvent.payloadJson.toJsonObjectOrNull() ?: return null
    val sourceMessageId = driftPayload.get("sourceMessageId")?.asString?.trim().orEmpty()
    if (sourceMessageId.isBlank()) {
      return null
    }
    val sourceMessage =
      recentMessages.lastOrNull { message ->
        message.id == sourceMessageId && message.side == MessageSide.ASSISTANT
      } ?: return null
    val newerAssistantExists =
      recentMessages.any { message ->
        message.side == MessageSide.ASSISTANT && message.seq > sourceMessage.seq
      }
    if (newerAssistantExists) {
      return null
    }

    val signals = driftPayload.getAsJsonArray("signals").toStringList()
    val tabooMatches = driftPayload.getAsJsonArray("tabooMatches").toStringList()
    val prompt =
      buildStyleRepairPrompt(
        role = role,
        recentMessages = recentMessages,
        sourceMessageId = sourceMessageId,
        signals = signals,
        tabooMatches = tabooMatches,
      )
    if (prompt.isBlank()) {
      return null
    }
    return StyleRepairDirective(
      sourceMessageId = sourceMessageId,
      driftEventCreatedAt = driftEvent.createdAt,
      signals = signals,
      tabooMatches = tabooMatches,
      prompt = prompt,
    )
  }

  private fun buildStyleRepairPrompt(
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
    sourceMessageId: String,
    signals: List<String>,
    tabooMatches: List<String>,
  ): String {
    val speechStyle = role.runtimeProfile?.characterKernel?.speechStyleJson?.toJsonObjectOrNull()
    val tone = speechStyle?.get("tone")?.asString?.trim().orEmpty()
    val directness = speechStyle?.get("directness")?.asString?.trim().orEmpty()
    val recurringPatterns = speechStyle?.getAsJsonArray("recurring_patterns").toStringList().take(2)
    val avoidPhrases =
      (tabooMatches + speechStyle?.getAsJsonArray("taboo_words").toStringList())
        .distinct()
        .take(4)
    val styleSamples =
      recentMessages
        .filter { message -> message.side == MessageSide.ASSISTANT && message.id != sourceMessageId }
        .takeLast(2)
        .map { message -> message.content.toStyleRepairSample() }
        .filter(String::isNotBlank)
    return buildList {
      add("Style repair: stay fully in character and match the established voice.")
      if (signals.any { signal -> signal == "assistant_meta" || signal == "system_meta" || signal == "ooc_marker" }) {
        add("Do not mention being an AI, assistant, model, system, prompt, context, or out-of-character note.")
      }
      if (signals.contains("cadence_shift")) {
        add("Return to the established sentence cadence from recent in-character replies.")
      }
      if (tone.isNotBlank()) {
        add("Tone: $tone.")
      }
      if (directness.isNotBlank()) {
        add("Directness: $directness.")
      }
      if (recurringPatterns.isNotEmpty()) {
        add("Reuse patterns: ${recurringPatterns.joinToString(" | ")}.")
      }
      if (avoidPhrases.isNotEmpty()) {
        add("Avoid: ${avoidPhrases.joinToString(", ")}.")
      }
      if (styleSamples.isNotEmpty()) {
        add("Voice samples: ${styleSamples.joinToString(" / ")}")
      }
    }.joinToString(separator = "\n").take(STYLE_REPAIR_PROMPT_MAX_CHARS).trim()
  }

  private suspend fun appendDriftEventIfNeeded(
    sessionId: String,
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
    assistantMessage: Message,
  ) {
    val analysis =
      analyzeDrift(
        role = role,
        recentMessages = recentMessages,
        assistantMessage = assistantMessage,
      ) ?: return
    appendRoleplayEvent(
      sessionId = sessionId,
      eventType = SessionEventType.ROLE_DRIFT_DETECTED,
      payload =
        JsonObject().apply {
          addProperty("sourceMessageId", assistantMessage.id)
          add("signals", analysis.signals.toJsonArray())
          add("tabooMatches", analysis.tabooMatches.toJsonArray())
          addProperty("currentAverageSentenceLength", analysis.currentAverageSentenceLength)
          addProperty("recentAverageSentenceLength", analysis.recentAverageSentenceLength)
        },
    )
  }

  private fun analyzeDrift(
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    recentMessages: List<Message>,
    assistantMessage: Message,
  ): DriftAnalysis? {
    if (assistantMessage.side != MessageSide.ASSISTANT) {
      return null
    }
    val assistantText = assistantMessage.content.trim()
    if (assistantText.isBlank()) {
      return null
    }

    val signals = mutableListOf<String>()
    if (looksAssistantLike(assistantText)) {
      signals += "assistant_meta"
    }
    if (SYSTEM_EXPLANATION_PATTERNS.any { pattern -> pattern.containsMatchIn(assistantText) }) {
      signals += "system_meta"
    }
    if (OOC_PATTERNS.any { pattern -> pattern.containsMatchIn(assistantText) }) {
      signals += "ooc_marker"
    }

    val tabooMatches =
      role.runtimeProfile
        ?.characterKernel
        ?.speechStyleJson
        ?.toJsonObjectOrNull()
        ?.getAsJsonArray("taboo_words")
        .toStringList()
        .filter { tabooWord -> assistantText.containsPhraseIgnoreCase(tabooWord) }
        .distinct()
        .orEmpty()
    if (tabooMatches.isNotEmpty()) {
      signals += "taboo_phrase"
    }

    val baselineMessages =
      recentMessages
        .filter { message -> message.side == MessageSide.ASSISTANT }
        .map(Message::content)
        .map(String::trim)
        .filter(String::isNotBlank)
        .takeLast(DRIFT_BASELINE_ASSISTANT_COUNT)
    val currentAverageSentenceLength = averageSentenceLength(assistantText)
    val recentAverageSentenceLength =
      if (baselineMessages.isEmpty()) {
        0
      } else {
        baselineMessages.sumOf(::averageSentenceLength) / baselineMessages.size
      }
    val hasCadenceShift =
      baselineMessages.size >= 2 &&
        recentAverageSentenceLength >= DRIFT_MIN_BASELINE_SENTENCE_LENGTH &&
        currentAverageSentenceLength > 0 &&
        (
          (
            currentAverageSentenceLength >= recentAverageSentenceLength * 2 &&
              currentAverageSentenceLength - recentAverageSentenceLength >= DRIFT_MIN_SENTENCE_DELTA
          ) ||
            (
              recentAverageSentenceLength >= currentAverageSentenceLength * 2 &&
                recentAverageSentenceLength - currentAverageSentenceLength >= DRIFT_MIN_SENTENCE_DELTA
            )
        )
    if (hasCadenceShift) {
      signals += "cadence_shift"
    }

    if (signals.isEmpty()) {
      return null
    }
    return DriftAnalysis(
      signals = signals.distinct(),
      tabooMatches = tabooMatches,
      currentAverageSentenceLength = currentAverageSentenceLength,
      recentAverageSentenceLength = recentAverageSentenceLength,
    )
  }

  private fun averageSentenceLength(content: String): Int {
    val sentences =
      content
        .split(SENTENCE_BREAK_REGEX)
        .map(String::trim)
        .filter(String::isNotBlank)
    if (sentences.isEmpty()) {
      return 0
    }
    return sentences.sumOf { sentence ->
      sentence.replace(MULTI_SPACE_REGEX, "").length
    } / sentences.size
  }

  private fun looksAssistantLike(content: String): Boolean {
    return ASSISTANT_META_PATTERNS.any { pattern -> pattern.containsMatchIn(content) }
  }

  private suspend fun appendRoleplayEvent(
    sessionId: String,
    eventType: SessionEventType,
    payload: JsonObject,
  ) {
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = eventType,
        payloadJson = payload.toString(),
        createdAt = System.currentTimeMillis(),
      )
    )
  }

  private fun normalizeFinalMessage(message: Message): Message {
    if (message.status != MessageStatus.FAILED || !ContextOverflowRecovery.isContextOverflow(message.errorMessage)) {
      return message
    }
    return message.copy(errorMessage = ContextOverflowRecovery.toUserFacingError(message.errorMessage))
  }

  private suspend fun prepareTurnToolContext(
    pendingMessage: PendingRoleplayMessage,
    model: Model,
    enableStreamingOutput: Boolean,
    isStopRequested: () -> Boolean,
  ): RoleplayPreparedToolContext {
    return runCatching {
      toolOrchestrator.prepareTurnContext(
        RoleplayToolPreparationRequest(
          pendingMessage = pendingMessage,
          model = model,
          enableStreamingOutput = enableStreamingOutput,
          isStopRequested = isStopRequested,
        )
      )
    }.getOrElse { error ->
      warnLog(
        "failed to prepare roleplay tool context sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id}",
        error,
      )
      appendToolPreparationFailureEvent(
        sessionId = pendingMessage.session.id,
        turnId = pendingMessage.assistantSeed.id,
        errorMessage = error.message ?: "Failed to prepare roleplay tool context.",
      )
      RoleplayPreparedToolContext(
        collector =
          RoleplayToolTraceCollector(
            sessionId = pendingMessage.session.id,
            turnId = pendingMessage.assistantSeed.id,
          )
      )
    }
  }

  private fun prepareConversation(
    assistantSeed: Message,
    model: Model,
    promptAssembly: PromptAssemblyResult,
    turnToolContext: RoleplayPreparedToolContext,
    currentTurnMedia: CurrentTurnMedia,
    sessionId: String,
    recentMessages: List<Message>,
    memoryContext: RoleplayMemoryContextPack,
    trigger: String,
    startTime: Long,
  ): ConversationPreparationResult {
    val systemInstruction = Contents.of(promptAssembly.prompt)
    val sectionTokenSummary =
      promptAssembly.sections.joinToString(separator = ",") { section ->
        "${section.id.name}:${section.tokenEstimate}"
      }
    debugLog(
      "assembled prompt sessionId=$sessionId trigger=$trigger recentMessages=${recentMessages.size} atoms=${memoryContext.memoryAtoms.size} openThreads=${memoryContext.openThreads.size} fallbackMemories=${memoryContext.fallbackMemories.size} promptChars=${systemInstruction.toString().length} estimatedTokens=${promptAssembly.budgetReport?.estimatedInputTokens} usableTokens=${promptAssembly.budgetReport?.usableInputTokens} budgetMode=${promptAssembly.budgetReport?.mode} sectionTokens=$sectionTokenSummary",
    )

    return try {
      runtimeHelperFor(model).resetConversation(
        model = model,
        supportImage = currentTurnMedia.images.isNotEmpty(),
        supportAudio = currentTurnMedia.audioClips.isNotEmpty(),
        systemInstruction = systemInstruction,
        tools = turnToolContext.tools,
      )
      debugLog(
        "conversation reset after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId tools=${turnToolContext.tools.size} images=${currentTurnMedia.images.size} audioClips=${currentTurnMedia.audioClips.size} historicalImages=${currentTurnMedia.historicalImageCount} currentImages=${currentTurnMedia.currentImageCount} historicalAudioClips=${currentTurnMedia.historicalAudioCount} currentAudioClips=${currentTurnMedia.currentAudioCount}",
      )
      ConversationPreparationResult()
    } catch (exception: Exception) {
      val errorMessage = exception.message ?: "Failed to prepare the chat session."
      ConversationPreparationResult(
        failureMessage =
          assistantSeed.copy(
            status = MessageStatus.FAILED,
            errorMessage = ContextOverflowRecovery.toUserFacingError(errorMessage),
            updatedAt = System.currentTimeMillis(),
          ),
        overflowDetected = ContextOverflowRecovery.isContextOverflow(errorMessage),
      )
    }
  }

  private suspend fun runInferenceAttempt(
    assistantSeed: Message,
    model: Model,
    input: String,
    currentTurnMedia: CurrentTurnMedia,
    role: selfgemma.talk.domain.roleplay.model.RoleCard,
    sessionId: String,
    startTime: Long,
    enableStreamingOutput: Boolean,
    isStopRequested: () -> Boolean,
  ): InferenceAttemptResult {
    val callbackScope = CoroutineScope(Dispatchers.IO)
    val partialContent = StringBuilder()
    val completed = AtomicBoolean(false)
    val inferenceStart = System.currentTimeMillis()
    val hasLoggedStreamingUpdate = AtomicBoolean(false)

    return try {
      suspendCancellableCoroutine { continuation ->
        fun finish(status: MessageStatus, errorMessage: String? = null) {
          if (!completed.compareAndSet(false, true)) {
            return
          }
          val updatedMessage =
            assistantSeed.copy(
              content = partialContent.toString().trim(),
              status = status,
              errorMessage = errorMessage,
              latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
              updatedAt = System.currentTimeMillis(),
            )
          if (continuation.isActive) {
            continuation.resume(
              InferenceAttemptResult(
                message = updatedMessage,
                overflowDetected = status == MessageStatus.FAILED && ContextOverflowRecovery.isContextOverflow(errorMessage),
              )
            )
          }
        }

        try {
          runtimeHelperFor(model).runInference(
            model = model,
            input = input,
            resultListener = { partialResult, done, _ ->
              if (!partialResult.startsWith("<ctrl") && partialResult.isNotEmpty()) {
                partialContent.append(partialResult)

                if (enableStreamingOutput && !isStopRequested()) {
                  if (hasLoggedStreamingUpdate.compareAndSet(false, true)) {
                    debugLog(
                      "streaming content updates enabled sessionId=$sessionId assistantMessageId=${assistantSeed.id}",
                    )
                  }
                  val streamingMessage =
                    assistantSeed.copy(
                      content = partialContent.toString(),
                      status = MessageStatus.STREAMING,
                      updatedAt = System.currentTimeMillis(),
                    )
                  callbackScope.launch { conversationRepository.updateMessage(streamingMessage) }
                }
              }

              if (done) {
                debugLog(
                  "inference callback done after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId",
                )
                finish(
                  status =
                    if (isStopRequested()) {
                      MessageStatus.INTERRUPTED
                    } else {
                      MessageStatus.COMPLETED
                    }
                )
              }
            },
            cleanUpListener = {},
            onError = { message ->
              debugLog(
                "inference error after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId message=$message",
              )
              finish(
                status =
                  if (isStopRequested()) {
                    MessageStatus.INTERRUPTED
                  } else {
                    MessageStatus.FAILED
                  },
                errorMessage = if (isStopRequested()) null else message,
              )
            },
            images = currentTurnMedia.images,
            audioClips = currentTurnMedia.audioClips,
            extraContext =
              if (
                role.enableThinking &&
                  model.getBooleanConfigValue(
                    key = ConfigKeys.ENABLE_THINKING,
                    defaultValue = false,
                  )
              ) {
                mapOf("enable_thinking" to "true")
              } else {
                null
              },
          )
          debugLog("runInference dispatched after ${safeElapsedRealtime() - startTime}ms sessionId=$sessionId")
        } catch (exception: Exception) {
          finish(
            status = MessageStatus.FAILED,
            errorMessage = exception.message ?: "Failed to generate a reply.",
          )
        }

        continuation.invokeOnCancellation {
          if (!completed.get()) {
            runtimeHelperFor(model).stopResponse(model)
          }
        }
      }
    } catch (exception: Exception) {
      InferenceAttemptResult(
        message =
          assistantSeed.copy(
            content = partialContent.toString().trim(),
            status = MessageStatus.FAILED,
            errorMessage = exception.message ?: "Failed to generate a reply.",
            latencyMs = (System.currentTimeMillis() - inferenceStart).toDouble(),
            updatedAt = System.currentTimeMillis(),
          ),
        overflowDetected = ContextOverflowRecovery.isContextOverflow(exception.message),
      )
    }
  }

  private fun loadConversationMedia(
    dialogueWindow: List<Message>,
    currentMessages: List<Message>,
  ): CurrentTurnMedia {
    val selectedAttachments =
      selectConversationMediaAttachments(
        dialogueWindow = dialogueWindow,
        currentMessages = currentMessages,
      )
    val historicalMedia =
      loadMediaFromAttachments(
        selectedAttachments.historicalImages + selectedAttachments.historicalAudioClips
      )
    val currentMedia =
      loadMediaFromAttachments(
        selectedAttachments.currentImages + selectedAttachments.currentAudioClips
      )
    val overflowText =
      buildOverflowMediaText(
        currentMessages = currentMessages,
        selectedAttachments = selectedAttachments,
      ).currentTurnText
    val mergedMedia =
      CurrentTurnMedia(
        images = historicalMedia.images + currentMedia.images,
        audioClips = historicalMedia.audioClips + currentMedia.audioClips,
        historicalImageCount = historicalMedia.images.size,
        currentImageCount = currentMedia.images.size,
        historicalAudioCount = historicalMedia.audioClips.size,
        currentAudioCount = currentMedia.audioClips.size,
        overflowText = overflowText,
      )
    val rawCurrentImageAttachmentCount =
      countRoleplayAttachments(currentMessages, RoleplayMessageAttachmentType.IMAGE)
    val rawCurrentAudioAttachmentCount =
      countRoleplayAttachments(currentMessages, RoleplayMessageAttachmentType.AUDIO)
    val rawHistoricalImageAttachmentCount =
      countRoleplayAttachments(dialogueWindow, RoleplayMessageAttachmentType.IMAGE)
    val rawHistoricalAudioAttachmentCount =
      countRoleplayAttachments(dialogueWindow, RoleplayMessageAttachmentType.AUDIO)
    if (
      rawCurrentImageAttachmentCount > selectedAttachments.currentImages.size ||
        rawCurrentAudioAttachmentCount > selectedAttachments.currentAudioClips.size ||
        rawHistoricalImageAttachmentCount > selectedAttachments.historicalImages.size ||
        rawHistoricalAudioAttachmentCount > selectedAttachments.historicalAudioClips.size
    ) {
      warnLog(
        "capped roleplay multimodal context rawCurrentImages=$rawCurrentImageAttachmentCount rawCurrentAudioClips=$rawCurrentAudioAttachmentCount rawHistoricalImages=$rawHistoricalImageAttachmentCount rawHistoricalAudioClips=$rawHistoricalAudioAttachmentCount selectedImages=${mergedMedia.images.size} selectedAudioClips=${mergedMedia.audioClips.size} maxImages=$MAX_ROLEPLAY_CONTEXT_IMAGE_ATTACHMENTS maxAudioClips=$MAX_ROLEPLAY_CONTEXT_AUDIO_ATTACHMENTS",
      )
    }
    debugLog(
      "loaded multimodal context dialogueWindowMessages=${dialogueWindow.size} currentMessages=${currentMessages.size} images=${mergedMedia.images.size} audioClips=${mergedMedia.audioClips.size} historicalImages=${mergedMedia.historicalImageCount} currentImages=${mergedMedia.currentImageCount} historicalAudioClips=${mergedMedia.historicalAudioCount} currentAudioClips=${mergedMedia.currentAudioCount}",
    )
    return mergedMedia
  }

  private suspend fun ensureCurrentImageAttachmentContextTexts(
    userMessages: List<Message>,
    model: Model,
    sessionId: String,
    isStopRequested: () -> Boolean,
  ): List<Message> {
    var updatedAny = false
    val updatedMessages =
      userMessages.map { message ->
        if (message.kind != MessageKind.IMAGE || isStopRequested()) {
          return@map message
        }
        val payload = message.roleplayMessageMediaPayload() ?: return@map message
        if (
          payload.attachments.none { attachment ->
            attachment.type == RoleplayMessageAttachmentType.IMAGE && attachment.contextText.isNullOrBlank()
          }
        ) {
          return@map message
        }

        val updatedAttachments =
          payload.attachments.mapIndexed { index, attachment ->
            if (
              attachment.type != RoleplayMessageAttachmentType.IMAGE ||
                attachment.contextText?.trim()?.isNotEmpty() == true
            ) {
              attachment
            } else {
              val bitmap = BitmapFactory.decodeFile(attachment.filePath)
              if (bitmap == null) {
                warnLog(
                  "failed to decode roleplay image attachment for context text sessionId=$sessionId messageId=${message.id} path=${attachment.filePath}",
                )
                attachment
              } else {
                val generatedContextText =
                  describeImageAttachmentContext(
                    model = model,
                    bitmap = bitmap,
                    sessionId = sessionId,
                    messageId = message.id,
                    attachmentIndex = index,
                    isStopRequested = isStopRequested,
                  )
                if (generatedContextText.isNullOrBlank()) {
                  attachment
                } else {
                  attachment.copy(contextText = generatedContextText)
                }
              }
            }
          }

        if (updatedAttachments == payload.attachments) {
          message
        } else {
          updatedAny = true
          message.copy(
            metadataJson =
              encodeRoleplayMessageMediaPayload(
                payload.copy(attachments = updatedAttachments)
              ),
            updatedAt = System.currentTimeMillis(),
          )
        }
      }

    if (!updatedAny) {
      return userMessages
    }

    updatedMessages
      .filter { updated -> userMessages.any { original -> original.id == updated.id && original != updated } }
      .forEach { updatedMessage ->
        conversationRepository.updateMessage(updatedMessage)
      }
    debugLog(
      "persisted image context text sessionId=$sessionId updatedMessages=${updatedMessages.count { updated -> userMessages.any { original -> original.id == updated.id && original != updated } }}",
    )
    return updatedMessages
  }

  private suspend fun describeImageAttachmentContext(
    model: Model,
    bitmap: Bitmap,
    sessionId: String,
    messageId: String,
    attachmentIndex: Int,
    isStopRequested: () -> Boolean,
  ): String? {
    if (isStopRequested()) {
      return null
    }

    return try {
      runtimeHelperFor(model).resetConversation(
        model = model,
        supportImage = true,
        supportAudio = false,
        systemInstruction = Contents.of(IMAGE_CONTEXT_SYSTEM_PROMPT),
      )
      suspendCancellableCoroutine { continuation ->
        val partialContent = StringBuilder()
        val completed = AtomicBoolean(false)

        fun finish(value: String?) {
          if (!completed.compareAndSet(false, true)) {
            return
          }
          if (continuation.isActive) {
            continuation.resume(value)
          }
        }

        try {
          runtimeHelperFor(model).runInference(
            model = model,
            input = IMAGE_CONTEXT_USER_PROMPT,
            resultListener = { partialResult, done, _ ->
              if (!partialResult.startsWith("<ctrl") && partialResult.isNotEmpty()) {
                partialContent.append(partialResult)
              }
              if (done) {
                finish(sanitizeAttachmentContextText(partialContent.toString()))
              }
            },
            cleanUpListener = {},
            onError = { message ->
              warnLog(
                "image context generation failed sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex message=$message",
              )
              finish(null)
            },
            images = listOf(bitmap),
          )
        } catch (exception: Exception) {
          warnLog(
            "image context generation threw sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
            exception,
          )
          finish(null)
        }

        continuation.invokeOnCancellation {
          if (!completed.get()) {
            runtimeHelperFor(model).stopResponse(model)
          }
        }
      }
    } catch (exception: Exception) {
      warnLog(
        "failed to reset conversation for image context generation sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
        exception,
      )
      null
    }
  }

  private fun sanitizeAttachmentContextText(value: String): String? {
    val sanitized =
      value
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('"')
        .take(IMAGE_CONTEXT_TEXT_MAX_CHARS)
    return sanitized.ifBlank { null }
  }

  private fun mergeUserInputWithOverflowText(input: String, overflowText: String): String {
    val trimmedInput = input.trim()
    val trimmedOverflowText = overflowText.trim()
    if (trimmedOverflowText.isBlank()) {
      return trimmedInput
    }
    return buildString {
      if (trimmedInput.isNotBlank()) {
        append(trimmedInput)
        append("\n\n")
      }
      append(trimmedOverflowText)
    }.trim()
  }

  private fun String.toStyleRepairSample(): String {
    return replace(MULTI_SPACE_REGEX, " ").trim().trim('"').take(STYLE_REPAIR_SAMPLE_MAX_CHARS)
  }

  private fun String.containsPhraseIgnoreCase(phrase: String): Boolean {
    val normalizedText = replace(MULTI_SPACE_REGEX, " ").trim()
    val normalizedPhrase = phrase.replace(MULTI_SPACE_REGEX, " ").trim()
    if (normalizedText.isBlank() || normalizedPhrase.isBlank()) {
      return false
    }
    return normalizedText.contains(normalizedPhrase, ignoreCase = true)
  }

  private fun String.toJsonObjectOrNull(): JsonObject? {
    return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
  }

  private fun JsonArray?.toStringList(): List<String> {
    return this
      ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }
      .orEmpty()
  }

  private fun List<String>.toJsonArray(): JsonArray {
    return JsonArray().apply {
      this@toJsonArray.forEach(::add)
    }
  }

  private fun loadMediaFromAttachments(attachments: List<RoleplayMessageAttachment>): CurrentTurnMedia {
    val images = mutableListOf<Bitmap>()
    val audioClips = mutableListOf<ByteArray>()

    attachments.forEach { attachment ->
      when (attachment.type) {
        RoleplayMessageAttachmentType.IMAGE -> {
          val bitmap = BitmapFactory.decodeFile(attachment.filePath)
          if (bitmap != null) {
            images += bitmap
          } else {
            warnLog(
              "failed to decode roleplay image attachment path=${attachment.filePath}",
            )
          }
        }
        RoleplayMessageAttachmentType.AUDIO -> {
          val sampleRate = attachment.sampleRate
          val file = File(attachment.filePath)
          if (sampleRate == null || !file.exists()) {
            warnLog(
              "failed to load roleplay audio attachment sampleRate=$sampleRate path=${attachment.filePath}",
            )
          } else {
            audioClips += pcm16MonoToWav(file.readBytes(), sampleRate)
          }
        }
      }
    }

    return CurrentTurnMedia(images = images, audioClips = audioClips)
  }

  private suspend fun awaitModelReady(
    model: Model,
    isStopRequested: () -> Boolean,
  ): ModelReadinessResult {
    val startTime = System.currentTimeMillis()
    var sawInitialization = model.initializing

    while (model.instance == null) {
      if (isStopRequested()) {
        return ModelReadinessResult(ready = false, interrupted = true)
      }

      sawInitialization = sawInitialization || model.initializing
      if (sawInitialization && !model.initializing) {
        return ModelReadinessResult(
          ready = false,
          errorMessage = "Selected model failed to initialize.",
        )
      }

      if (System.currentTimeMillis() - startTime >= MODEL_READY_TIMEOUT_MS) {
        return ModelReadinessResult(
          ready = false,
          errorMessage = "Selected model is still preparing.",
        )
      }

      delay(MODEL_READY_POLL_INTERVAL_MS)
    }

    return ModelReadinessResult(ready = true)
  }

  private suspend fun createStagedTurn(
    sessionId: String,
    model: Model,
    userInputs: List<String>,
  ): StagedRoleplayTurn {
    val now = System.currentTimeMillis()
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    val firstSeq = conversationRepository.nextMessageSeq(sessionId)
    debugLog("queue next seq resolved sessionId=$sessionId seq=$firstSeq")
    val sanitizedInputs = userInputs.map(String::trim).filter(String::isNotBlank)
    val userMessages =
      sanitizedInputs.mapIndexed { index, input ->
        Message(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          seq = firstSeq + index,
          branchId = DEFAULT_BRANCH_ID,
          side = MessageSide.USER,
          status = MessageStatus.COMPLETED,
          accepted = true,
          isCanonical = true,
          content = input,
          createdAt = now,
          updatedAt = now,
        )
      }
    val parentMessageId = userMessages.lastOrNull()?.id
    return StagedRoleplayTurn(
      userMessages = userMessages,
      assistantMessage =
        Message(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          seq = firstSeq + sanitizedInputs.size,
          branchId = DEFAULT_BRANCH_ID,
          side = MessageSide.ASSISTANT,
          status = MessageStatus.STREAMING,
          accepted = false,
          isCanonical = false,
          content = "",
          accelerator = accelerator,
          parentMessageId = parentMessageId,
          regenerateGroupId = parentMessageId,
          createdAt = now,
          updatedAt = now,
        ),
      combinedUserInput = sanitizedInputs.joinToString(separator = "\n\n"),
    )
  }

  private fun debugLog(message: String) {
    runCatching {
      Log.d(TAG, message)
    }
  }

  private fun warnLog(message: String, throwable: Throwable? = null) {
    runCatching {
      if (throwable == null) {
        Log.w(TAG, message)
      } else {
        Log.w(TAG, message, throwable)
      }
    }
  }

  private fun runtimeHelperFor(model: Model): LlmModelHelper = runtimeHelperResolver(model)

  private fun safeElapsedRealtime(): Long {
    return runCatching {
      SystemClock.elapsedRealtime()
    }.getOrDefault(0L)
  }
}
