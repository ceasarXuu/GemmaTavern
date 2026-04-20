package selfgemma.talk.ui.llmchat

import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.domain.roleplay.usecase.TokenEstimator
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatMessageType
import selfgemma.talk.ui.common.chat.ChatSide
import com.google.ai.edge.litertlm.Contents

private const val PER_IMAGE_TOKEN_RESERVE = 256
private const val PER_AUDIO_TOKEN_RESERVE = 192
private const val HISTORY_LINE_MAX_CHARS = 240
private const val SUMMARY_LINE_MAX_CHARS = 140

internal enum class LlmChatContextMode {
  FULL,
  AGGRESSIVE,
}

internal data class LlmChatContextReport(
  val usableInputTokens: Int,
  val reservedForCurrentTurnTokens: Int,
  val availableInstructionTokens: Int,
  val estimatedInstructionTokens: Int,
  val currentTurnOverflowDetected: Boolean,
  val mode: LlmChatContextMode,
  val recentLineCount: Int,
  val summaryLineCount: Int,
  val droppedLineCount: Int,
  val systemPromptTrimmed: Boolean,
)

internal data class LlmChatContextPlan(
  val systemInstruction: Contents?,
  val report: LlmChatContextReport,
)

internal class LlmChatContextManager(
  private val tokenEstimator: TokenEstimator = TokenEstimator(),
) {
  fun buildPlan(
    baseSystemPrompt: String,
    historyMessages: List<ChatMessage>,
    pendingInput: String,
    pendingImageCount: Int,
    pendingAudioCount: Int,
    contextProfile: ModelContextProfile,
    preferredMode: LlmChatContextMode = LlmChatContextMode.FULL,
  ): LlmChatContextPlan {
    val historyLines = historyMessages.mapNotNull(::toHistoryLine)
    val pendingInputTokens =
      tokenEstimator.estimate(pendingInput) +
        (pendingImageCount * PER_IMAGE_TOKEN_RESERVE) +
        (pendingAudioCount * PER_AUDIO_TOKEN_RESERVE)
    val currentTurnOverflowDetected = pendingInputTokens > contextProfile.usableInputTokens
    val availableInstructionTokens =
      (contextProfile.usableInputTokens - pendingInputTokens).coerceAtLeast(0)

    val promptBudget =
      when (preferredMode) {
        LlmChatContextMode.FULL -> availableInstructionTokens
        LlmChatContextMode.AGGRESSIVE -> availableInstructionTokens
      }
    val normalizedBasePrompt = baseSystemPrompt.trim()
    val basePrompt = fitToBudget(normalizedBasePrompt, budgetTokens = promptBudget / 2)
    val systemPromptTrimmed =
      normalizedBasePrompt.isNotBlank() &&
        tokenEstimator.estimate(basePrompt) < tokenEstimator.estimate(normalizedBasePrompt)
    var remainingBudget = (promptBudget - tokenEstimator.estimate(basePrompt)).coerceAtLeast(0)

    val recentBudgetShare =
      when (preferredMode) {
        LlmChatContextMode.FULL -> 0.65
        LlmChatContextMode.AGGRESSIVE -> 0.45
      }
    val recentBudget = (remainingBudget * recentBudgetShare).toInt().coerceAtLeast(0)
    val summaryBudget = (remainingBudget - recentBudget).coerceAtLeast(0)

    val recentLines = selectRecentLines(historyLines = historyLines, budgetTokens = recentBudget)
    val olderLines = historyLines.dropLast(recentLines.size)
    val summaryLines =
      buildSummaryLines(
        olderLines = olderLines,
        budgetTokens = summaryBudget,
        preferredMode = preferredMode,
      )

    val prompt =
      buildPrompt(
        baseSystemPrompt = basePrompt,
        summaryLines = summaryLines,
        recentLines = recentLines,
      )
    val fittedPrompt = fitToBudget(prompt, budgetTokens = promptBudget)
    val estimatedTokens = tokenEstimator.estimate(fittedPrompt)
    val droppedLineCount =
      (historyLines.size - recentLines.size - summaryLines.size).coerceAtLeast(0)

    return LlmChatContextPlan(
      systemInstruction = fittedPrompt.takeIf { it.isNotBlank() }?.let(Contents::of),
      report =
        LlmChatContextReport(
          usableInputTokens = contextProfile.usableInputTokens,
          reservedForCurrentTurnTokens = pendingInputTokens,
          availableInstructionTokens = availableInstructionTokens,
          estimatedInstructionTokens = estimatedTokens,
          currentTurnOverflowDetected = currentTurnOverflowDetected,
          mode =
            when {
              currentTurnOverflowDetected -> LlmChatContextMode.AGGRESSIVE
              droppedLineCount > 0 || summaryLines.isEmpty() && olderLines.isNotEmpty() ->
                LlmChatContextMode.AGGRESSIVE
              else -> preferredMode
            },
          recentLineCount = recentLines.size,
          summaryLineCount = summaryLines.size,
          droppedLineCount = droppedLineCount,
          systemPromptTrimmed = systemPromptTrimmed,
        ),
    )
  }

  private fun selectRecentLines(historyLines: List<String>, budgetTokens: Int): List<String> {
    if (historyLines.isEmpty() || budgetTokens <= 0) {
      return emptyList()
    }

    val selected = mutableListOf<String>()
    var usedTokens = 0
    for (line in historyLines.asReversed()) {
      val lineTokens = tokenEstimator.estimate(line)
      if (usedTokens + lineTokens > budgetTokens) {
        break
      }
      selected += line
      usedTokens += lineTokens
    }
    return selected.asReversed()
  }

  private fun buildSummaryLines(
    olderLines: List<String>,
    budgetTokens: Int,
    preferredMode: LlmChatContextMode,
  ): List<String> {
    if (olderLines.isEmpty() || budgetTokens <= 0) {
      return emptyList()
    }

    val cappedOlderLines =
      when (preferredMode) {
        LlmChatContextMode.FULL -> olderLines.takeLast(8)
        LlmChatContextMode.AGGRESSIVE -> olderLines.takeLast(4)
      }

    val summaryLines = mutableListOf<String>()
    var usedTokens = 0
    cappedOlderLines.forEach { line ->
      val summaryLine = "- ${line.take(SUMMARY_LINE_MAX_CHARS)}"
      val summaryTokens = tokenEstimator.estimate(summaryLine)
      if (usedTokens + summaryTokens > budgetTokens) {
        return@forEach
      }
      summaryLines += summaryLine
      usedTokens += summaryTokens
    }
    return summaryLines
  }

  private fun fitToBudget(text: String, budgetTokens: Int): String {
    if (text.isBlank() || budgetTokens <= 0) {
      return ""
    }
    val normalized = text.replace(WHITESPACE_REGEX, " ").trim()
    if (tokenEstimator.estimate(normalized) <= budgetTokens) {
      return normalized
    }
    val maxChars = (budgetTokens * 4).coerceAtLeast(0)
    if (maxChars == 0) {
      return ""
    }
    return normalized.take(maxChars).trim()
  }

  private fun buildPrompt(
    baseSystemPrompt: String,
    summaryLines: List<String>,
    recentLines: List<String>,
  ): String {
    return buildString {
      if (baseSystemPrompt.isNotBlank()) {
        appendLine(baseSystemPrompt)
      }
      if (summaryLines.isNotEmpty() || recentLines.isNotEmpty()) {
        if (isNotEmpty()) {
          appendLine()
        }
        appendLine("Use the following compressed conversation memory to stay consistent with the ongoing chat.")
      }
      if (summaryLines.isNotEmpty()) {
        appendLine()
        appendLine("Earlier turns summary:")
        summaryLines.forEach(::appendLine)
      }
      if (recentLines.isNotEmpty()) {
        appendLine()
        appendLine("Recent turns:")
        recentLines.forEach(::appendLine)
      }
    }
      .trim()
  }

  private fun toHistoryLine(message: ChatMessage): String? {
    return when (message) {
      is ChatMessageText ->
        message.content
          .trim()
          .takeIf { it.isNotBlank() }
          ?.replace(WHITESPACE_REGEX, " ")
          ?.take(HISTORY_LINE_MAX_CHARS)
          ?.let { "${message.side.toHistorySpeaker()}: $it" }
      is ChatMessageImage ->
        when (message.side) {
          ChatSide.USER -> "User shared ${message.bitmaps.size} image(s)."
          ChatSide.AGENT -> "Assistant shared image output."
          ChatSide.SYSTEM -> null
        }
      is ChatMessageAudioClip ->
        when (message.side) {
          ChatSide.USER -> "User shared an audio clip."
          ChatSide.AGENT -> "Assistant shared audio output."
          ChatSide.SYSTEM -> null
        }
      else ->
        when (message.type) {
          ChatMessageType.LOADING,
          ChatMessageType.ERROR,
          ChatMessageType.WARNING,
          ChatMessageType.THINKING,
          ChatMessageType.PROMPT_TEMPLATES,
          ChatMessageType.CONFIG_VALUES_CHANGE,
          ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
          ChatMessageType.BENCHMARK_RESULT,
          ChatMessageType.BENCHMARK_LLM_RESULT,
          ChatMessageType.CLASSIFICATION,
          ChatMessageType.WEBVIEW,
          ChatMessageType.IMAGE_WITH_HISTORY,
          ChatMessageType.INFO -> null
          ChatMessageType.TEXT,
          ChatMessageType.IMAGE,
          ChatMessageType.AUDIO_CLIP -> null
        }
    }
  }

  private fun ChatSide.toHistorySpeaker(): String {
    return when (this) {
      ChatSide.USER -> "User"
      ChatSide.AGENT -> "Assistant"
      ChatSide.SYSTEM -> "System"
    }
  }

  private companion object {
    val WHITESPACE_REGEX = Regex("\\s+")
  }
}
