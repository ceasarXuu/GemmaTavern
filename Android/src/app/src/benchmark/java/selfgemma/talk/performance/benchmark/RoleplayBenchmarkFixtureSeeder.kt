package selfgemma.talk.performance.benchmark

import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.roleplay.db.RoleplayDatabase
import selfgemma.talk.data.roleplay.db.entity.MessageEntity
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.data.roleplay.db.entity.SessionSummaryEntity
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

private const val BENCHMARK_ACTIVE_MODEL_ID = "benchmark-model-placeholder"
private const val BUILT_IN_ROLE_COUNT = 8
private const val CUSTOM_ROLE_COUNT = 24
private const val STRESS_SESSION_COUNT = 18
private const val SESSION_TURN_COUNT = 8
private const val LONG_CHAT_TURN_COUNT = 56

@Singleton
class RoleplayBenchmarkFixtureSeeder @Inject constructor(
  private val database: RoleplayDatabase,
) {
  suspend fun seedScenario(scenario: String): String {
    return database.withTransaction {
      database.clearAllTables()
      when (scenario) {
        ROLEPLAY_BENCHMARK_STRESS_SCENARIO -> seedStressScenario()
        else -> error("Unknown roleplay benchmark scenario: $scenario")
      }
    }
  }

  private suspend fun seedStressScenario(): String {
    val now = System.currentTimeMillis()
    val roles = buildRoles(now)
    val sessions = mutableListOf<SessionEntity>()
    val messages = mutableListOf<MessageEntity>()
    val summaries = mutableListOf<SessionSummaryEntity>()

    roles.forEachIndexed { index, role ->
      if (index >= STRESS_SESSION_COUNT) {
        return@forEachIndexed
      }

      val sessionTimestamp = now - (index * 60_000L)
      val transcript = buildConversation(roleName = role.name, turnCount = SESSION_TURN_COUNT, longForm = false)
      val sessionId = "benchmark-session-${index + 1}"
      sessions +=
        SessionEntity(
          id = sessionId,
          roleId = role.id,
          title = "Benchmark Session ${index + 1}",
          activeModelId = BENCHMARK_ACTIVE_MODEL_ID,
          pinned = index < 2,
          createdAt = sessionTimestamp - 30_000L,
          updatedAt = sessionTimestamp,
          lastMessageAt = sessionTimestamp,
          lastSummary = "Summary for ${role.name}",
          lastUserMessageExcerpt = transcript.lastUserExcerpt,
          lastAssistantMessageExcerpt = transcript.lastAssistantExcerpt,
          turnCount = SESSION_TURN_COUNT,
          summaryVersion = 1,
        )
      messages += transcript.messages(sessionId = sessionId, baseTimestamp = sessionTimestamp)
      summaries +=
        SessionSummaryEntity(
          sessionId = sessionId,
          version = 1,
          coveredUntilSeq = SESSION_TURN_COUNT * 2,
          summaryText = "Summary for ${role.name}",
          tokenEstimate = 384,
          updatedAt = sessionTimestamp,
        )
    }

    val longChatRole = roles.first { it.name == ROLEPLAY_LONG_CHAT_ROLE_NAME }
    val longChatTimestamp = now + 120_000L
    val longTranscript =
      buildConversation(
        roleName = longChatRole.name,
        turnCount = LONG_CHAT_TURN_COUNT,
        longForm = true,
      )
    val longSessionId = "benchmark-session-long-chat"
    sessions +=
      SessionEntity(
        id = longSessionId,
        roleId = longChatRole.id,
        title = "Benchmark Long Conversation",
        activeModelId = BENCHMARK_ACTIVE_MODEL_ID,
        pinned = true,
        createdAt = longChatTimestamp - 60_000L,
        updatedAt = longChatTimestamp,
        lastMessageAt = longChatTimestamp,
        lastSummary = "Long benchmark summary",
        lastUserMessageExcerpt = longTranscript.lastUserExcerpt,
        lastAssistantMessageExcerpt = longTranscript.lastAssistantExcerpt,
        turnCount = LONG_CHAT_TURN_COUNT,
        summaryVersion = 2,
      )
    messages += longTranscript.messages(sessionId = longSessionId, baseTimestamp = longChatTimestamp)
    summaries +=
      SessionSummaryEntity(
        sessionId = longSessionId,
        version = 2,
        coveredUntilSeq = LONG_CHAT_TURN_COUNT * 2,
        summaryText = "Long benchmark summary",
        tokenEstimate = 4096,
        updatedAt = longChatTimestamp,
      )

    database.roleDao().upsertAll(roles)
    database.sessionDao().upsertAll(sessions.sortedByDescending { it.updatedAt })
    database.messageDao().insertAll(messages.sortedBy { it.createdAt })
    database.sessionSummaryDao().upsertAll(summaries)

    return "scenario=$ROLEPLAY_BENCHMARK_STRESS_SCENARIO roles=${roles.size} sessions=${sessions.size} messages=${messages.size}"
  }

  private fun buildRoles(now: Long): List<RoleEntity> {
    val builtInRoles =
      (1..BUILT_IN_ROLE_COUNT).map { index ->
        createRoleEntity(
          id = "benchmark-role-built-in-$index",
          name = "Benchmark Built-in Role $index",
          builtIn = true,
          updatedAt = now - (index * 10_000L),
          summary = "Built-in benchmark role $index for role catalog density and session list coverage.",
        )
      }
    val customRoles =
      (1..CUSTOM_ROLE_COUNT).map { index ->
        createRoleEntity(
          id = "benchmark-role-custom-$index",
          name = "Benchmark Custom Role $index",
          builtIn = false,
          updatedAt = now - (index * 15_000L),
          summary = "Custom benchmark role $index with enough metadata to stress role cards and sorting.",
        )
      }
    val longChatRole =
      createRoleEntity(
        id = "benchmark-role-long-chat",
        name = ROLEPLAY_LONG_CHAT_ROLE_NAME,
        builtIn = false,
        updatedAt = now + 120_000L,
        summary = "Dedicated long-chat benchmark role used to stress message rendering and scrolling.",
      )

    return builtInRoles + customRoles + longChatRole
  }

  private fun createRoleEntity(
    id: String,
    name: String,
    builtIn: Boolean,
    updatedAt: Long,
    summary: String,
  ): RoleEntity {
    return RoleEntity(
      id = id,
      name = name,
      summary = summary,
      systemPrompt =
        "Stay in character as $name. Reply with concrete detail, preserve continuity, and keep responses easy to scan.",
      personaDescription = "A benchmark persona that keeps structure, emotion, and memory consistency.",
      worldSettings = "A dense benchmark sandbox with multiple simultaneous conversations and long-form context.",
      openingLine = "Ready when you are.",
      exampleDialogues = listOf("User: Start.\nAssistant: Scene initialized."),
      safetyPolicy = "Stay grounded in the benchmark scenario and avoid contradictions.",
      defaultModelId = BENCHMARK_ACTIVE_MODEL_ID,
      enableThinking = false,
      tags = listOf("benchmark", if (builtIn) "built-in" else "custom"),
      builtIn = builtIn,
      createdAt = updatedAt - 5_000L,
      updatedAt = updatedAt,
    )
  }
}

private data class ConversationSeed(
  val userMessages: List<String>,
  val assistantMessages: List<String>,
) {
  val lastUserExcerpt: String
    get() = userMessages.last().singleLineExcerpt()

  val lastAssistantExcerpt: String
    get() = assistantMessages.last().singleLineExcerpt()

  fun messages(sessionId: String, baseTimestamp: Long): List<MessageEntity> {
    val items = mutableListOf<MessageEntity>()
    var seq = 1
    userMessages.zip(assistantMessages).forEachIndexed { index, (userText, assistantText) ->
      val timestamp = baseTimestamp + (index * 1_000L)
      items +=
        MessageEntity(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          seq = seq++,
          side = MessageSide.USER,
          status = MessageStatus.COMPLETED,
          content = userText,
          createdAt = timestamp,
          updatedAt = timestamp,
        )
      items +=
        MessageEntity(
          id = UUID.randomUUID().toString(),
          sessionId = sessionId,
          seq = seq++,
          side = MessageSide.ASSISTANT,
          status = MessageStatus.COMPLETED,
          content = assistantText,
          isMarkdown = true,
          createdAt = timestamp + 300L,
          updatedAt = timestamp + 300L,
        )
    }
    return items
  }
}

private fun buildConversation(roleName: String, turnCount: Int, longForm: Boolean): ConversationSeed {
  val userMessages = mutableListOf<String>()
  val assistantMessages = mutableListOf<String>()

  repeat(turnCount) { index ->
    val turn = index + 1
    userMessages +=
      if (longForm) {
        "Turn $turn for $roleName: keep continuity across earlier scenes, maintain emotional detail, and carry forward every named object from previous turns.\n\nList the current constraints, the active subplot, and one unresolved tension before continuing."
      } else {
        "Turn $turn for $roleName: continue the benchmark dialogue and keep the answer concise but contextual."
      }

    assistantMessages +=
      if (longForm) {
        buildString {
          append("### $roleName response $turn\n\n")
          append("The room keeps the same arrangement as before, with every prop still in view. ")
          append("Momentum remains high, and the answer deliberately carries detail across turns to stress long text layout.\n\n")
          append("- Continuity anchor $turn\n")
          append("- Named object chain $turn\n")
          append("- Emotional callback $turn\n\n")
          append("Paragraph two expands on pacing, body language, and scene memory so the message stays long enough to exercise scrolling and rendering. ")
          append("Paragraph three adds one more reflective beat plus a concrete next action for the user to respond to.")
        }
      } else {
        "**$roleName** keeps the exchange moving on turn $turn with one compact paragraph and one explicit next step."
      }
  }

  return ConversationSeed(userMessages = userMessages, assistantMessages = assistantMessages)
}

private fun String.singleLineExcerpt(maxLength: Int = 120): String {
  return trim().replace("\n", " ").take(maxLength)
}