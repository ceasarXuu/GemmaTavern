package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType

/**
 * Pure helpers extracted from [SendRoleplayMessageUseCase] for drift detection and style repair
 * directive composition. Behavior preserved verbatim from the original instance methods.
 */

internal fun analyzeDrift(
  role: RoleCard,
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

internal fun averageSentenceLength(content: String): Int {
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

internal fun looksAssistantLike(content: String): Boolean {
  return ASSISTANT_META_PATTERNS.any { pattern -> pattern.containsMatchIn(content) }
}

internal fun buildStyleRepairDirective(
  role: RoleCard,
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

internal fun buildStyleRepairPrompt(
  role: RoleCard,
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

internal fun String.toStyleRepairSample(): String {
  return replace(MULTI_SPACE_REGEX, " ").trim().trim('"').take(STYLE_REPAIR_SAMPLE_MAX_CHARS)
}

internal fun String.containsPhraseIgnoreCase(phrase: String): Boolean {
  val normalizedText = replace(MULTI_SPACE_REGEX, " ").trim()
  val normalizedPhrase = phrase.replace(MULTI_SPACE_REGEX, " ").trim()
  if (normalizedText.isBlank() || normalizedPhrase.isBlank()) {
    return false
  }
  return normalizedText.contains(normalizedPhrase, ignoreCase = true)
}

internal fun List<String>.toJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toJsonArray.forEach(::add)
  }
}
