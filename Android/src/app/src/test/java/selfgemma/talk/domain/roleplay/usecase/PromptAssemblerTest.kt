package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonArray
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.CharacterKernel
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StCharacterBook
import selfgemma.talk.domain.roleplay.model.StCharacterBookEntry
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.ModelContextProfile

class PromptAssemblerTest {
  private val assembler = PromptAssembler(TokenEstimator())

  @Test
  fun assemble_includesSummaryMemoryAndRecentConversation() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-1",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            openingLine = "The case file is already open.",
            cardCore =
              StCharacterCard(
                name = "Iris Vale",
                data =
                  StCharacterCardData(
                    name = "Iris Vale",
                    creator_notes = "The creator note mentions a sealed dossier.",
                    post_history_instructions = "Keep responses terse after the history block.",
                    extensions =
                      JsonObject().apply {
                        add(
                          "depth_prompt",
                          JsonObject().apply {
                            addProperty("prompt", "Reveal the hidden motive only after enough pressure.")
                            addProperty("depth", 3)
                            addProperty("role", "system")
                          },
                        )
                      },
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("lower station"),
                              content = "The lower station smells like coolant and wet rust.",
                              position = "before_char",
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("forged pass"),
                              content = "If the forged pass comes up, Iris should suspect internal sabotage.",
                              position = "after_char",
                            ),
                            StCharacterBookEntry(
                              id = 3,
                              keys = listOf("sealed dossier"),
                              content = "Treat the sealed dossier as evidence that someone inside the precinct is compromised.",
                              extensions =
                                JsonObject().apply {
                                  addProperty("match_creator_notes", true)
                                  addProperty("position", 2)
                                },
                            ),
                            StCharacterBookEntry(
                              id = 4,
                              keys = listOf("lower"),
                              secondary_keys = listOf("station"),
                              content = "Ask precise follow-up questions when the lower station is discussed.",
                              selective = true,
                              extensions =
                                JsonObject().apply {
                                  addProperty("selectiveLogic", 3)
                                  addProperty("position", 4)
                                  addProperty("depth", 2)
                                  addProperty("role", 2)
                                },
                            ),
                          )
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary =
          SessionSummary(
            sessionId = "session-1",
            version = 2,
            coveredUntilSeq = 5,
            summaryText = "The pair discovered a forged transit pass.",
            tokenEstimate = 12,
            updatedAt = now,
          ),
        memories =
          listOf(
            MemoryItem(
              id = "memory-1",
              roleId = "role-1",
              sessionId = "session-1",
              category = MemoryCategory.PLOT,
              content = "The suspect fled toward the lower station.",
              normalizedHash = "hash-1",
              pinned = true,
              createdAt = now,
              updatedAt = now,
            )
          ),
        recentMessages =
          listOf(
            Message(
              id = "message-1",
              sessionId = "session-1",
              seq = 1,
              side = MessageSide.USER,
              content = "We should check the lower station next.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-2",
              sessionId = "session-1",
              seq = 2,
              side = MessageSide.ASSISTANT,
              content = "Agreed. The forged pass narrows the route.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "Could the forged pass point to someone inside the lower station?",
      )

    assertTrue(prompt.contains("[Lorebook]"))
    assertTrue(prompt.contains("The lower station smells like coolant and wet rust."))
    assertTrue(prompt.contains("If the forged pass comes up, Iris should suspect internal sabotage."))
    assertTrue(prompt.contains("Treat the sealed dossier as evidence that someone inside the precinct is compromised."))
    assertTrue(prompt.contains("[Post-History Instructions]"))
    assertTrue(prompt.contains("Keep responses terse after the history block."))
    assertTrue(prompt.contains("[Depth Prompt]"))
    assertTrue(prompt.contains("Reveal the hidden motive only after enough pressure."))
    assertTrue(prompt.contains("role=assistant depth=2"))
    assertTrue(prompt.contains("Ask precise follow-up questions when the lower station is discussed."))
    assertTrue(prompt.contains("[Session Summary]"))
    assertTrue(prompt.contains("The pair discovered a forged transit pass."))
    assertTrue(prompt.contains("[Relevant Memory]"))
    assertTrue(prompt.contains("plot: The suspect fled toward the lower station."))
    assertTrue(prompt.contains("User: We should check the lower station next."))
    assertTrue(prompt.contains("Iris Vale: Agreed. The forged pass narrows the route."))
    assertFalse(prompt.contains("[Suggested Opening Tone]"))
    assertFalse(prompt.contains("The case file is already open."))
  }

  @Test
  fun assemble_includesExternalToolFactsInDedicatedSection() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-1",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        pendingUserInput = "现在几点？",
        externalFacts =
          listOf(
            RoleplayExternalFact(
              id = "fact-1",
              sourceToolName = "getDeviceSystemTime",
              title = "Device system time",
              content = "Real-world device system time is 2026-04-22 18:07 in Asia/Shanghai. The lunar date is 三月初六.",
            )
          ),
      )

    assertTrue(prompt.contains("[External Tool Facts]"))
    assertTrue(prompt.contains("Device system time"))
    assertTrue(prompt.contains("2026-04-22 18:07"))
    assertTrue(prompt.contains("三月初六"))
  }

  @Test
  fun assemble_instructsModelToDecideToolUsageWhenRuntimeToolsAreAvailable() {
    val now = System.currentTimeMillis()
    val promptWithTools =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-1",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        pendingUserInput = "现在现实时间是多少？",
        hasRuntimeTools = true,
      )
    val promptWithoutTools =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-1",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        pendingUserInput = "现在现实时间是多少？",
        hasRuntimeTools = false,
      )

    val toolDecisionInstruction =
      "When the user needs real-world device facts or actions, decide yourself whether to call an available tool instead of guessing."
    assertTrue(promptWithTools.contains(toolDecisionInstruction))
    assertFalse(promptWithoutTools.contains(toolDecisionInstruction))
  }

  @Test
  fun assemble_keepsMultimodalHistoryPlaceholdersInRecentConversation() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-media",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-image",
              sessionId = "session-media",
              seq = 1,
              side = MessageSide.USER,
              kind = MessageKind.IMAGE,
              content = "Shared 2 image(s).",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-audio",
              sessionId = "session-media",
              seq = 2,
              side = MessageSide.USER,
              kind = MessageKind.AUDIO,
              content = "Shared an audio clip.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "What do you notice from them?",
      )

    assertTrue(prompt.contains("Shared 2 image(s)."))
    assertTrue(prompt.contains("Shared an audio clip."))
    assertTrue(prompt.contains("Image attachment remains available in multimodal context."))
    assertTrue(prompt.contains("Audio attachment remains available in multimodal context."))
  }

  @Test
  fun assembleForSession_exposesDialogueWindowForMediaReplay() {
    val now = System.currentTimeMillis()
    val result =
      assembler.assembleForSession(
        role =
          RoleCard(
            id = "role-window",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-text",
              sessionId = "session-window",
              seq = 1,
              side = MessageSide.USER,
              content = "Keep this clue in mind.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-image",
              sessionId = "session-window",
              seq = 2,
              side = MessageSide.USER,
              kind = MessageKind.IMAGE,
              content = "Shared 1 image(s).",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "What changed?",
      )

    assertTrue(result.dialogueWindow.map { it.id }.contains("message-text"))
    assertTrue(result.dialogueWindow.map { it.id }.contains("message-image"))
  }

  @Test
  fun assembleForSession_prefersCharacterKernelWhenAggressiveBudgetCompactsRoleSections() {
    val now = System.currentTimeMillis()
    val result =
      assembler.assembleForSession(
        role =
          RoleCard(
            id = "role-kernel",
            name = "Iris Vale",
            summary = "A dry-witted investigator who hates wasted motion.",
            systemPrompt = (1..40).joinToString(" ") { "Always stay in character and maintain the case tone $it." },
            personaDescription = (1..30).joinToString(" ") { "skeptical-$it" },
            worldSettings = (1..30).joinToString(" ") { "storm-city-$it" },
            runtimeProfile =
              RoleRuntimeProfile(
                characterKernel =
                  CharacterKernel(
                    roleId = "role-kernel",
                    version = 2,
                    identityJson =
                      """{"name":"Iris Vale","role":"Field investigator","core_motive":"Pin the truth before the city buries it","worldview":"Trust evidence over rumor"}""",
                    speechStyleJson =
                      """{"tone":"dry and surgical","sentence_length":"short","directness":"direct","taboo_words":["generic reassurance"],"recurring_patterns":["cut to evidence"]}""",
                    invariantsJson =
                      """{"rules":["never breaks character","never becomes generic assistant","prioritize in-character response"]}""",
                    microExemplar = "Iris Vale: Cut to evidence. Then we talk.",
                    tokenBudget = 96,
                    compiledAt = now,
                  ),
                compiledCorePrompt = "STALE CORE SHOULD NOT SURVIVE",
                compiledPersonaPrompt = "STALE PERSONA SHOULD NOT SURVIVE",
                compiledWorldPrompt = "STALE WORLD SHOULD NOT SURVIVE",
                compiledExampleDigest = "STALE EXAMPLE SHOULD NOT SURVIVE",
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-1",
              sessionId = "session-kernel",
              seq = 1,
              side = MessageSide.USER,
              content = "We do not have much room in context.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "Stay sharp.",
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 256,
            reservedOutputTokens = 96,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 48,
          ),
        budgetMode = PromptBudgetMode.AGGRESSIVE,
      )

    assertTrue(result.prompt.contains("Role: Field investigator"))
    assertTrue(result.prompt.contains("Core motive: Pin the truth before the city buries it"))
    assertFalse(result.prompt.contains("STALE CORE SHOULD NOT SURVIVE"))
    assertFalse(result.prompt.contains("STALE PERSONA SHOULD NOT SURVIVE"))
  }

  @Test
  fun assemble_includesExpandedRuntimeStateFields() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-runtime",
            name = "Iris Vale",
            summary = "A dry-witted investigator.",
            systemPrompt = "Always stay in character.",
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        runtimeStateSnapshot =
          RuntimeStateSnapshot(
            sessionId = "session-runtime",
            sceneJson =
              """{"location":"Observatory roof","time":"before dawn","currentGoal":"Return the beacon key","dangerLevel":"high","importantItems":["beacon key","forged pass"],"activeTopic":"Who opened the hatch?","recentAction":"Iris checked the hatch and scanned the skyline."}""",
            relationshipJson =
              """{"trust":3,"intimacy":1,"tension":2,"dependence":1,"initiative":3,"respect":3,"fear":2,"currentMood":"tense"}""",
            activeEntitiesJson =
              """{"present":["user","Iris Vale","Dockmaster Harlan"],"focus":["user","Iris Vale","beacon key"]}""",
            updatedAt = now,
            sourceMessageId = "assistant-runtime",
          ),
        pendingUserInput = "What do you make of it?",
      )

    assertTrue(prompt.contains("[Runtime State]"))
    assertTrue(prompt.contains("Scene current goal: Return the beacon key"))
    assertTrue(prompt.contains("Scene danger level: high"))
    assertTrue(prompt.contains("Scene important items: beacon key, forged pass"))
    assertTrue(prompt.contains("Relationship initiative: 3"))
    assertTrue(prompt.contains("Relationship respect: 3"))
    assertTrue(prompt.contains("Active entities: user, Iris Vale, Dockmaster Harlan"))
  }

  @Test
  fun assemble_respects_case_sensitive_and_whole_word_matching() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-2",
            name = "Casey",
            summary = "A test role.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Casey",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("Key"),
                              content = "Case-sensitive match should trigger.",
                              extensions = JsonObject().apply { addProperty("case_sensitive", true) },
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("cat"),
                              content = "Whole-word match should not trigger for scatter.",
                              extensions = JsonObject().apply { addProperty("match_whole_words", true) },
                            ),
                          )
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-3",
              sessionId = "session-2",
              seq = 1,
              side = MessageSide.USER,
              content = "The Key is here, but the scatter is unrelated.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("Case-sensitive match should trigger."))
    assertFalse(prompt.contains("Whole-word match should not trigger for scatter."))
  }

  @Test
  fun assemble_prefersCanonicalStCardProjectionOverLegacyFields() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-3",
            name = "Legacy Name",
            summary = "Legacy summary",
            systemPrompt = "Legacy prompt",
            personaDescription = "Legacy persona",
            worldSettings = "Legacy world",
            exampleDialogues = listOf("Legacy example"),
            cardCore =
              StCharacterCard(
                name = "Canonical Name",
                data =
                  StCharacterCardData(
                    name = "Canonical Name",
                    description = "Canonical summary",
                    personality = "Canonical persona",
                    scenario = "Canonical world",
                    mes_example = "Canonical example",
                    system_prompt = "Canonical prompt",
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("You are roleplaying as Canonical Name."))
    assertTrue(prompt.contains("[Core Character]\nCanonical prompt"))
    assertTrue(prompt.contains("[Character Summary]\nCanonical summary"))
    assertTrue(prompt.contains("[Personality]\nCanonical persona"))
    assertFalse(prompt.contains("[Persona]\nCanonical persona"))
    assertTrue(prompt.contains("[World]\nCanonical world"))
    assertTrue(prompt.contains("[Example Dialogue]\nCanonical example"))
    assertFalse(prompt.contains("Legacy summary"))
    assertFalse(prompt.contains("Legacy prompt"))
  }

  @Test
  fun assemble_substitutesStMacrosAcrossPromptAndLorebook() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-4",
            name = "Catty",
            summary = "{{user}} adopted {{char}}.",
            systemPrompt = "Protect {{user}} and remember {{creatorNotes}}.",
            personaDescription = "{{char}} is playful.",
            worldSettings = "{{scenario}}",
            cardCore =
              StCharacterCard(
                name = "Catty",
                scenario = "legacy world",
                data =
                  StCharacterCardData(
                    scenario = "{{user}} and {{char}} share an apartment.",
                    creator_notes = "{{user}} rescued {{char}} from a shelter.",
                    mes_example = "{{user}}: Hi\n{{char}}: Hey.",
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("Catty"),
                              content = "{{char}} trusts {{user}}.",
                              position = "before_char",
                            )
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-4",
              sessionId = "session-4",
              seq = 1,
              side = MessageSide.USER,
              content = "Catty is here.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("Protect User and remember User rescued Catty from a shelter.."))
    assertTrue(prompt.contains("[Character Summary]\nUser adopted Catty."))
    assertTrue(prompt.contains("[Personality]\nCatty is playful."))
    assertTrue(prompt.contains("[World]\nUser and Catty share an apartment."))
    assertTrue(prompt.contains("[Example Dialogue]\nUser: Hi\nCatty: Hey."))
    assertTrue(prompt.contains("Catty trusts User."))
    assertFalse(prompt.contains("{{char}}"))
    assertFalse(prompt.contains("{{user}}"))
    assertFalse(prompt.contains("<USER>"))
  }

  @Test
  fun assemble_injects_user_persona_only_when_profile_is_in_prompt() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-14",
            name = "Guide",
            summary = "Helpful guide.",
            systemPrompt = "Stay focused.",
            cardCore =
              StCharacterCard(
                name = "Guide",
                data = StCharacterCardData(personality = "Patient and observant."),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages = emptyList(),
        pendingUserInput = "",
        userProfile =
          selfgemma.talk.domain.roleplay.model.StUserProfile().withActivePersona(
            name = "Alex",
            description = "{{user}} is a cautious negotiator.",
          ),
      )

    assertTrue(prompt.contains("[Persona]\nAlex is a cautious negotiator."))
    assertTrue(prompt.contains("[Personality]\nPatient and observant."))
  }

  @Test
  fun assemble_supports_regex_budget_and_recursive_world_info() {
    val now = System.currentTimeMillis()
    val result =
      assembler.assembleForSession(
        role =
          RoleCard(
            id = "role-5",
            name = "Regex Tester",
            summary = "Budget test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Regex Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        token_budget = 7,
                        recursive_scanning = true,
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("/secret [0-9]+/i"),
                              content = "alpha beta gamma delta",
                              extensions = JsonObject().apply { addProperty("ignore_budget", true) },
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("gamma"),
                              content = "recursive trigger content",
                              position = "before_char",
                            ),
                            StCharacterBookEntry(
                              id = 3,
                              keys = listOf("recursive"),
                              content = "this should be dropped by token budget overflow",
                              position = "before_char",
                            ),
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-5",
              sessionId = "session-5",
              seq = 1,
              side = MessageSide.USER,
              content = "I found secret 42 in the budget report.",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
        chatMetadataJson = null,
      )

    assertTrue(result.prompt, result.prompt.contains("alpha beta gamma delta"))
    assertTrue(result.prompt, result.prompt.contains("recursive trigger content"))
    assertFalse(result.prompt, result.prompt.contains("this should be dropped by token budget overflow"))
  }

  @Test
  fun assemble_persists_sticky_entries_through_chat_metadata() {
    val now = System.currentTimeMillis()
    val role =
      RoleCard(
        id = "role-6",
        name = "Sticky Tester",
        summary = "Sticky test.",
        systemPrompt = "",
        cardCore =
          StCharacterCard(
            name = "Sticky Tester",
            data =
              StCharacterCardData(
                character_book =
                  StCharacterBook(
                    entries =
                      listOf(
                        StCharacterBookEntry(
                          id = 1,
                          keys = listOf("sticky"),
                          content = "sticky lore entry",
                          position = "before_char",
                          extensions =
                            JsonObject().apply {
                              addProperty("sticky", 2)
                              addProperty("cooldown", 2)
                            },
                        )
                      ),
                  ),
              ),
          ),
        createdAt = now,
        updatedAt = now,
      )

    val first =
      assembler.assembleForSession(
        role = role,
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-6",
              sessionId = "session-6",
              seq = 1,
              side = MessageSide.USER,
              content = "sticky",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
        chatMetadataJson = null,
      )
    val second =
      assembler.assembleForSession(
        role = role,
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-7",
              sessionId = "session-6",
              seq = 1,
              side = MessageSide.USER,
              content = "no key now",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-8",
              sessionId = "session-6",
              seq = 2,
              side = MessageSide.ASSISTANT,
              content = "reply",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "",
        chatMetadataJson = first.updatedChatMetadataJson,
      )

    assertTrue(first.prompt.contains("sticky lore entry"))
    assertTrue(second.prompt.contains("sticky lore entry"))
    assertTrue(first.updatedChatMetadataJson != null)
  }

  @Test
  fun assemble_honors_decorators_and_character_filter() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-7",
            name = "Filter Tester",
            summary = "Filter test.",
            systemPrompt = "",
            tags = listOf("catboy"),
            cardCore =
              StCharacterCard(
                name = "Filter Tester",
                data =
                  StCharacterCardData(
                    tags = listOf("catboy"),
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("missing"),
                              content = "@@activate\nforced activation",
                              position = "before_char",
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("Filter"),
                              content = "@@dont_activate\nblocked activation",
                              position = "before_char",
                            ),
                            StCharacterBookEntry(
                              id = 3,
                              keys = listOf("Filter"),
                              content = "character filter pass",
                              position = "before_char",
                              character_filter =
                                JsonObject().apply {
                                  add("names", JsonArray().apply { add("Filter Tester") })
                                },
                            ),
                            StCharacterBookEntry(
                              id = 4,
                              keys = listOf("Filter"),
                              content = "character filter fail",
                              position = "before_char",
                              character_filter =
                                JsonObject().apply {
                                  add("tags", JsonArray().apply { add("dog") })
                                },
                            ),
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-9",
              sessionId = "session-7",
              seq = 1,
              side = MessageSide.USER,
              content = "Filter",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("forced activation"))
    assertTrue(prompt.contains("character filter pass"))
    assertFalse(prompt.contains("blocked activation"))
    assertFalse(prompt.contains("character filter fail"))
  }

  @Test
  fun assemble_honors_generation_type_triggers() {
    val now = System.currentTimeMillis()
    val role =
      RoleCard(
        id = "role-8",
        name = "Trigger Tester",
        summary = "Trigger test.",
        systemPrompt = "",
        cardCore =
          StCharacterCard(
            name = "Trigger Tester",
            data =
              StCharacterCardData(
                character_book =
                  StCharacterBook(
                    entries =
                      listOf(
                        StCharacterBookEntry(
                          id = 1,
                          keys = listOf("hello"),
                          content = "normal trigger lore",
                          position = "before_char",
                          extensions =
                            JsonObject().apply {
                              add(
                                "triggers",
                                JsonArray().apply {
                                  add("normal")
                                },
                              )
                            },
                        ),
                        StCharacterBookEntry(
                          id = 2,
                          keys = listOf("hello"),
                          content = "quiet trigger lore",
                          position = "before_char",
                          extensions =
                            JsonObject().apply {
                              add(
                                "triggers",
                                JsonArray().apply {
                                  add("quiet")
                                },
                              )
                            },
                        ),
                      ),
                  ),
              ),
          ),
        createdAt = now,
        updatedAt = now,
      )

    val normalPrompt =
      assembler.assemble(
        role = role,
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-10",
              sessionId = "session-8",
              seq = 1,
              side = MessageSide.USER,
              content = "hello",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
        generationTrigger = "normal",
      )
    val quietPrompt =
      assembler.assemble(
        role = role,
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-11",
              sessionId = "session-8",
              seq = 1,
              side = MessageSide.USER,
              content = "hello",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
        generationTrigger = "quiet",
      )

    assertTrue(normalPrompt.contains("normal trigger lore"))
    assertFalse(normalPrompt.contains("quiet trigger lore"))
    assertTrue(quietPrompt.contains("quiet trigger lore"))
    assertFalse(quietPrompt.contains("normal trigger lore"))
  }

  @Test
  fun assemble_prefers_highest_scored_group_entry_and_merges_depth_prompts() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-9",
            name = "Score Tester",
            summary = "Score test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Score Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("alpha", "beta"),
                              content = "high score entry",
                              position = "before_char",
                              extensions =
                                JsonObject().apply {
                                  addProperty("group", "score")
                                  addProperty("use_group_scoring", true)
                                },
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("alpha"),
                              content = "low score entry",
                              position = "before_char",
                              extensions =
                                JsonObject().apply {
                                  addProperty("group", "score")
                                  addProperty("use_group_scoring", true)
                                },
                            ),
                            StCharacterBookEntry(
                              id = 3,
                              keys = listOf("alpha"),
                              content = "depth one",
                              position = "before_char",
                              extensions =
                                JsonObject().apply {
                                  addProperty("position", 4)
                                  addProperty("depth", 3)
                                  addProperty("role", 2)
                                },
                            ),
                            StCharacterBookEntry(
                              id = 4,
                              keys = listOf("beta"),
                              content = "depth two",
                              position = "before_char",
                              extensions =
                                JsonObject().apply {
                                  addProperty("position", 4)
                                  addProperty("depth", 3)
                                  addProperty("role", 2)
                                },
                            ),
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-12",
              sessionId = "session-9",
              seq = 1,
              side = MessageSide.USER,
              content = "alpha beta",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("high score entry"))
    assertFalse(prompt.contains("low score entry"))
    assertTrue(prompt.contains("role=assistant depth=3\ndepth one\ndepth two"))
  }

  @Test
  fun assemble_reads_styled_timed_world_info_metadata_by_entry_id() {
    val now = System.currentTimeMillis()
    val result =
      assembler.assembleForSession(
        role =
          RoleCard(
            id = "role-10",
            name = "Timed Tester",
            summary = "Timed test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Timed Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("missing"),
                              content = "sticky by imported metadata",
                              position = "before_char",
                              extensions = JsonObject().apply { addProperty("sticky", 2) },
                            )
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-13",
              sessionId = "session-10",
              seq = 1,
              side = MessageSide.USER,
              content = "no keyword",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-14",
              sessionId = "session-10",
              seq = 2,
              side = MessageSide.ASSISTANT,
              content = "reply",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "",
        chatMetadataJson = """{"timedWorldInfo":{"sticky":{"1":{"hash":"1","start":0,"end":4,"protected":false}}}}""",
      )

    assertTrue(result.prompt.contains("sticky by imported metadata"))
  }

  @Test
  fun assemble_advances_scan_depth_to_meet_min_activations() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-11",
            name = "Depth Skew Tester",
            summary = "Depth skew test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Depth Skew Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        scan_depth = 1,
                        extensions =
                          JsonObject().apply {
                            addProperty("min_activations", 1)
                            addProperty("min_activations_depth_max", 3)
                          },
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("first clue"),
                              content = "min activation lore",
                              position = "before_char",
                            )
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-15",
              sessionId = "session-11",
              seq = 1,
              side = MessageSide.USER,
              content = "first clue",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-16",
              sessionId = "session-11",
              seq = 2,
              side = MessageSide.ASSISTANT,
              content = "middle line",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
            Message(
              id = "message-17",
              sessionId = "session-11",
              seq = 3,
              side = MessageSide.USER,
              content = "latest line",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            ),
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("min activation lore"))
  }

  @Test
  fun assemble_does_not_use_summary_or_memories_for_lore_scanning() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-12",
            name = "Scan Buffer Tester",
            summary = "Buffer test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Scan Buffer Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("memory-only-key"),
                              content = "should not activate from app memory",
                              position = "before_char",
                            )
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary =
          SessionSummary(
            sessionId = "session-12",
            version = 1,
            coveredUntilSeq = 0,
            summaryText = "memory-only-key appears in summary",
            tokenEstimate = 5,
            updatedAt = now,
          ),
        memories =
          listOf(
            MemoryItem(
              id = "memory-2",
              roleId = "role-12",
              sessionId = "session-12",
              category = MemoryCategory.PLOT,
              content = "memory-only-key appears in memory",
              normalizedHash = "hash-2",
              pinned = true,
              createdAt = now,
              updatedAt = now,
            )
          ),
        recentMessages =
          listOf(
            Message(
              id = "message-18",
              sessionId = "session-12",
              seq = 1,
              side = MessageSide.USER,
              content = "ordinary chat",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertFalse(prompt.contains("should not activate from app memory"))
  }

  @Test
  fun assemble_respects_max_recursion_steps() {
    val now = System.currentTimeMillis()
    val prompt =
      assembler.assemble(
        role =
          RoleCard(
            id = "role-13",
            name = "Recursion Limit Tester",
            summary = "Recursion limit test.",
            systemPrompt = "",
            cardCore =
              StCharacterCard(
                name = "Recursion Limit Tester",
                data =
                  StCharacterCardData(
                    character_book =
                      StCharacterBook(
                        recursive_scanning = true,
                        extensions =
                          JsonObject().apply {
                            addProperty("max_recursion_steps", 1)
                          },
                        entries =
                          listOf(
                            StCharacterBookEntry(
                              id = 1,
                              keys = listOf("seed"),
                              content = "recursive-one",
                              position = "before_char",
                            ),
                            StCharacterBookEntry(
                              id = 2,
                              keys = listOf("recursive-one"),
                              content = "recursive-two",
                              position = "before_char",
                            ),
                          ),
                      ),
                  ),
              ),
            createdAt = now,
            updatedAt = now,
          ),
        summary = null,
        memories = emptyList(),
        recentMessages =
          listOf(
            Message(
              id = "message-19",
              sessionId = "session-13",
              seq = 1,
              side = MessageSide.USER,
              content = "seed",
              status = MessageStatus.COMPLETED,
              createdAt = now,
              updatedAt = now,
            )
          ),
        pendingUserInput = "",
      )

    assertTrue(prompt.contains("recursive-one"))
    assertFalse(prompt.contains("recursive-two"))
  }
}
