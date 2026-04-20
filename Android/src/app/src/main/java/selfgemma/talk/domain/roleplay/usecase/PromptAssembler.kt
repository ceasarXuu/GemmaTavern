package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.StChatRuntimeSession
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.cardData
import selfgemma.talk.domain.roleplay.model.exampleDialoguesRaw
import selfgemma.talk.domain.roleplay.model.name
import selfgemma.talk.domain.roleplay.model.personaDescriptionForAuthorNote
import selfgemma.talk.domain.roleplay.model.personaDescriptionForDepthPrompt
import selfgemma.talk.domain.roleplay.model.personaDescription
import selfgemma.talk.domain.roleplay.model.summary
import selfgemma.talk.domain.roleplay.model.systemPrompt
import selfgemma.talk.domain.roleplay.model.tags
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.userPersonaDescription
import selfgemma.talk.domain.roleplay.model.worldSettings

private const val RECENT_DIALOGUE_TOKEN_BUDGET = 1800
private const val ST_DEFAULT_WORLD_INFO_SCAN_DEPTH = 4

class PromptAssembler @Inject constructor(private val tokenEstimator: TokenEstimator) {
  private val characterBookRuntime = StCharacterBookRuntime(tokenEstimator)
  private val materialBuilder = PromptMaterialBuilder(tokenEstimator)
  private val contextBudgetPlanner = ContextBudgetPlanner(tokenEstimator)

  fun assemble(
    role: RoleCard,
    summary: SessionSummary?,
    memories: List<MemoryItem>,
    recentMessages: List<Message>,
    runtimeStateSnapshot: RuntimeStateSnapshot? = null,
    openThreads: List<OpenThread> = emptyList(),
    memoryAtoms: List<MemoryAtom> = emptyList(),
    pendingUserInput: String = "",
    generationTrigger: String = "normal",
    userProfile: StUserProfile = StUserProfile(),
    contextProfile: ModelContextProfile? = null,
    budgetMode: PromptBudgetMode = PromptBudgetMode.FULL,
  ): String {
    return assembleForSession(
      role = role,
      summary = summary,
      memories = memories,
      recentMessages = recentMessages,
      runtimeStateSnapshot = runtimeStateSnapshot,
      openThreads = openThreads,
      memoryAtoms = memoryAtoms,
      pendingUserInput = pendingUserInput,
      generationTrigger = generationTrigger,
      userProfile = userProfile,
      chatMetadataJson = null,
      contextProfile = contextProfile,
      budgetMode = budgetMode,
    ).prompt
  }

  internal fun assembleForSession(
    role: RoleCard,
    summary: SessionSummary?,
    memories: List<MemoryItem>,
    recentMessages: List<Message>,
    runtimeStateSnapshot: RuntimeStateSnapshot? = null,
    openThreads: List<OpenThread> = emptyList(),
    memoryAtoms: List<MemoryAtom> = emptyList(),
    pendingUserInput: String = "",
    generationTrigger: String = "normal",
    userProfile: StUserProfile = StUserProfile(),
    chatMetadataJson: String? = null,
    contextProfile: ModelContextProfile? = null,
    budgetMode: PromptBudgetMode = PromptBudgetMode.FULL,
  ): PromptAssemblyResult {
    val runtimeRole = role.toStChatRuntimeRole(userProfile = userProfile)
    val runtimeSession =
      StChatRuntimeSession(
        chatMetadataJson = chatMetadataJson,
        generationTrigger = generationTrigger,
      )
    return assembleForSession(
      runtimeRole = runtimeRole,
      runtimeSession = runtimeSession,
      summary = summary,
      memories = memories,
      recentMessages = recentMessages,
      runtimeStateSnapshot = runtimeStateSnapshot,
      openThreads = openThreads,
      memoryAtoms = memoryAtoms,
      pendingUserInput = pendingUserInput,
      runtimeProfile = role.runtimeProfile,
      contextProfile = contextProfile,
      budgetMode = budgetMode,
    )
  }

  internal fun assembleForSession(
    runtimeRole: StChatRuntimeRole,
    runtimeSession: StChatRuntimeSession,
    summary: SessionSummary?,
    memories: List<MemoryItem>,
    recentMessages: List<Message>,
    runtimeStateSnapshot: RuntimeStateSnapshot? = null,
    openThreads: List<OpenThread> = emptyList(),
    memoryAtoms: List<MemoryAtom> = emptyList(),
    pendingUserInput: String = "",
    runtimeProfile: RoleRuntimeProfile? = null,
    contextProfile: ModelContextProfile? = null,
    budgetMode: PromptBudgetMode = PromptBudgetMode.FULL,
  ): PromptAssemblyResult {
    val dialogueWindow = selectRecentMessages(recentMessages)
    val macroContext = runtimeRole.toStMacroContext()
    val scanContext =
      buildStScanContext(
        runtimeRole = runtimeRole,
        summary = summary,
        memories = memories,
        runtimeStateSnapshot = runtimeStateSnapshot,
        openThreads = openThreads,
        memoryAtoms = memoryAtoms,
        dialogueWindow = dialogueWindow,
        pendingUserInput = pendingUserInput,
        generationTrigger = runtimeSession.generationTrigger,
        macroContext = macroContext,
      )
    val cardData = runtimeRole.cardData()
    val resolvedCharacterBook =
      characterBookRuntime.resolve(
        book = cardData.character_book,
        context = scanContext,
        macroContext = macroContext,
        chatMetadataJson = runtimeSession.chatMetadataJson,
        chatLength = recentMessages.count { it.kind == MessageKind.TEXT && it.side != MessageSide.SYSTEM },
      )
    val coreDepthPrompt = cardData.extensions.toDepthPrompt(macroContext)
    val combinedExampleDialogue =
        buildList {
          addAll(resolvedCharacterBook.exampleBefore)
          addAll(runtimeRole.exampleDialoguesRaw().split("\n\n").map(String::trim).filter(String::isNotBlank).map(macroContext::substitute).filter { it.isNotBlank() })
          addAll(resolvedCharacterBook.exampleAfter)
        }
        .joinToString("\n")
        .trim()
    val postHistoryBlock =
      buildList {
          if (runtimeRole.userProfile.personaDescriptionPosition == StPersonaDescriptionPosition.TOP_AN) {
            runtimeRole.userProfile.personaDescriptionForAuthorNote()?.let(::add)
          }
          addAll(resolvedCharacterBook.authorNoteBefore)
          cardData.post_history_instructions
            ?.let(macroContext::substitute)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
          addAll(resolvedCharacterBook.authorNoteAfter)
          if (runtimeRole.userProfile.personaDescriptionPosition == StPersonaDescriptionPosition.BOTTOM_AN) {
            runtimeRole.userProfile.personaDescriptionForAuthorNote()?.let(::add)
          }
        }
        .joinToString("\n")
        .trim()
    val depthPromptBlock =
      buildList {
          coreDepthPrompt?.toPromptSection()?.let(::add)
          runtimeRole.userProfile.personaDescriptionForDepthPrompt()
            ?.let(macroContext::substitute)
            ?.takeIf(String::isNotBlank)
            ?.let { personaDepthPrompt ->
              add(
                buildString {
                  appendLine("role=${runtimeRole.userProfile.personaDescriptionRole.toPromptRoleName()} depth=${runtimeRole.userProfile.personaDescriptionDepth}")
                  append(personaDepthPrompt)
                }
              )
            }
          addAll(resolvedCharacterBook.depthPrompts.map { it.toPromptSection() })
        }
        .joinToString("\n\n")
        .trim()

    val material =
      materialBuilder.build(
        runtimeRole = runtimeRole,
        runtimeProfile = runtimeProfile,
        summary = summary,
        memories = memories,
        runtimeStateSnapshot = runtimeStateSnapshot,
        openThreads = openThreads,
        memoryAtoms = memoryAtoms,
        recentMessages = recentMessages,
        macroContext = macroContext,
        resolvedCharacterBook = resolvedCharacterBook,
        postHistoryBlock = postHistoryBlock,
        depthPromptBlock = depthPromptBlock,
        combinedExampleDialogue = combinedExampleDialogue,
      )
    val plan =
      contextBudgetPlanner.plan(
        material = material,
        contextProfile = contextProfile,
        preferredMode = budgetMode,
      )
    return PromptAssemblyResult(
      prompt = plan.prompt,
      updatedChatMetadataJson = resolvedCharacterBook.updatedChatMetadataJson,
      budgetReport = plan.report,
      sections = plan.sections,
      dialogueWindow = dialogueWindow,
    )
  }

  private fun selectRecentMessages(messages: List<Message>): List<Message> {
    return selectPromptWindowMessages(
      messages = messages,
      tokenBudget = RECENT_DIALOGUE_TOKEN_BUDGET,
      tokenEstimator = tokenEstimator,
    )
  }

  private fun MessageSide.toSpeakerLabel(runtimeRole: StChatRuntimeRole): String {
    return when (this) {
      MessageSide.USER -> runtimeRole.userName
      MessageSide.ASSISTANT -> runtimeRole.name()
      MessageSide.SYSTEM -> "System"
    }
  }

  private fun buildStScanContext(
    runtimeRole: StChatRuntimeRole,
    summary: SessionSummary?,
    memories: List<MemoryItem>,
    runtimeStateSnapshot: RuntimeStateSnapshot?,
    openThreads: List<OpenThread>,
    memoryAtoms: List<MemoryAtom>,
    dialogueWindow: List<Message>,
    pendingUserInput: String,
    generationTrigger: String,
    macroContext: StMacroContext,
  ): StWorldScanContext {
    val core = runtimeRole.card
    val data = runtimeRole.cardData()
    val recentMessagesNewestFirst =
      buildList {
        pendingUserInput.trim().takeIf(String::isNotBlank)?.let(::add)
        dialogueWindow
          .asReversed()
          .mapTo(this) { message ->
            "${message.side.toSpeakerLabel(runtimeRole)}: ${message.content.trim()}"
          }
      }
    val structuredMemoryLines =
      buildList {
        addAll(runtimeStateSnapshot.toScanMemoryLines())
        addAll(openThreads.map { thread -> "Open thread: ${thread.content.trim()}" })
        addAll(memoryAtoms.map { atom -> "${atom.subject.trim()} ${atom.predicate.trim()}: ${atom.objectValue.trim()}" })
        addAll(memories.map { it.content.trim() })
      }

    return StWorldScanContext(
      roleName = runtimeRole.name(),
      roleTags = runtimeRole.tags(),
      generationTrigger = generationTrigger,
      recentMessagesNewestFirst = recentMessagesNewestFirst,
      userPersonaDescription = macroContext.substitute(runtimeRole.userPersonaDescription()),
      characterDescription = macroContext.substitute(runtimeRole.summary()),
      characterPersonality =
        macroContext.substitute(
          data.personality.orEmpty().ifBlank { core.personality.orEmpty().ifBlank { runtimeRole.personaDescription() } }
        ),
      characterDepthPrompt = data.extensions.toDepthPrompt(macroContext)?.prompt.orEmpty(),
      scenario =
        macroContext.substitute(
          data.scenario.orEmpty().ifBlank { core.scenario.orEmpty().ifBlank { runtimeRole.worldSettings() } }
        ),
      creatorNotes = macroContext.substitute(data.creator_notes.orEmpty().ifBlank { core.creatorcomment.orEmpty() }),
      sessionSummary =
        listOfNotNull(summary?.summaryText?.trim(), runtimeStateSnapshot.toScanSummaryLine())
          .filter(String::isNotBlank)
          .joinToString(separator = "\n"),
      memories = structuredMemoryLines.filter(String::isNotBlank),
    )
  }

  private fun RuntimeStateSnapshot?.toScanSummaryLine(): String? {
    val snapshot = this ?: return null
    return snapshot.sceneJson.toJsonSummaryOrNull("recentAction")
      ?: snapshot.sceneJson.toJsonSummaryOrNull("activeTopic")
      ?: snapshot.relationshipJson.toJsonSummaryOrNull("currentMood")
  }

  private fun RuntimeStateSnapshot?.toScanMemoryLines(): List<String> {
    val snapshot = this ?: return emptyList()
    return buildList {
      snapshot.sceneJson.toJsonSummaryOrNull("location")?.let { add("Scene location: $it") }
      snapshot.sceneJson.toJsonSummaryOrNull("currentGoal", "goal")?.let { add("Scene goal: $it") }
      snapshot.sceneJson.toJsonSummaryOrNull("dangerLevel", "hazards")?.let { add("Scene danger: $it") }
      snapshot.sceneJson.toJsonSummaryOrNull("activeTopic")?.let { add("Active topic: $it") }
      snapshot.sceneJson.toJsonSummaryOrNull("recentAction")?.let { add("Recent action: $it") }
      snapshot.relationshipJson.toJsonSummaryOrNull("currentMood")?.let { add("Relationship mood: $it") }
    }
  }

  private fun String?.toJsonSummaryOrNull(vararg keys: String): String? {
    if (this.isNullOrBlank()) {
      return null
    }
    val jsonObject = runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull() ?: return null
    keys.forEach { key ->
      val value = jsonObject.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
      if (value != null) {
        return value
      }
    }
    return null
  }

  private fun JsonObject?.toDepthPrompt(macroContext: StMacroContext): DepthPromptInsertion? {
    val depthPrompt = this?.getAsJsonObject("depth_prompt") ?: return null
    val prompt =
      macroContext.substitute(depthPrompt.get("prompt")?.takeIf { it.isJsonPrimitive }?.asString).trim()
    if (prompt.isBlank()) {
      return null
    }
    return DepthPromptInsertion(
      prompt = prompt,
      depth = depthPrompt.get("depth")?.takeIf { it.isJsonPrimitive }?.asInt ?: ST_DEFAULT_WORLD_INFO_SCAN_DEPTH,
      role =
        depthPrompt.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
          ?: "system",
    )
  }

  private fun DepthPromptInsertion.toPromptSection(): String {
    return buildString {
      appendLine("role=$role depth=$depth")
      append(prompt)
    }
  }

  private fun StRuntimeDepthPromptInsertion.toPromptSection(): String {
    return buildString {
      appendLine("role=$role depth=$depth")
      append(prompts.joinToString("\n"))
    }
  }

  private fun Int?.toPromptRoleName(): String {
    return when (this) {
      1 -> "user"
      2 -> "assistant"
      else -> "system"
    }
  }

  companion object {
  }
}

private data class DepthPromptInsertion(
  val prompt: String,
  val depth: Int,
  val role: String,
)
