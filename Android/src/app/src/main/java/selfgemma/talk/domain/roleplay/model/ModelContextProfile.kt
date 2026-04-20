package selfgemma.talk.domain.roleplay.model

import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model

private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 4096
private const val DEFAULT_RESERVED_OUTPUT_TOKENS = 768
private const val DEFAULT_RESERVED_THINKING_TOKENS = 256
private const val DEFAULT_CONTEXT_SAFETY_MARGIN_TOKENS = 256
private const val ABSOLUTE_MIN_CONTEXT_WINDOW_TOKENS = 1024
private const val DEFAULT_MIN_USABLE_INPUT_TOKENS = 512
private const val DEFAULT_MIN_RESERVED_OUTPUT_TOKENS = 256

data class ModelContextProfile(
  val contextWindowTokens: Int,
  val reservedOutputTokens: Int,
  val reservedThinkingTokens: Int,
  val safetyMarginTokens: Int,
) {
  val usableInputTokens: Int
    get() = (contextWindowTokens - reservedOutputTokens - reservedThinkingTokens - safetyMarginTokens).coerceAtLeast(0)
}

fun Model.toModelContextProfile(): ModelContextProfile {
  val contextWindowTokens =
    getIntConfigValue(
        key = ConfigKeys.CONTEXT_WINDOW_TOKENS,
        defaultValue = llmMaxToken.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS,
      )
      .coerceAtLeast(ABSOLUTE_MIN_CONTEXT_WINDOW_TOKENS)
  val safetyMarginTokens =
    getIntConfigValue(
        key = ConfigKeys.CONTEXT_SAFETY_MARGIN_TOKENS,
        defaultValue = DEFAULT_CONTEXT_SAFETY_MARGIN_TOKENS,
      )
      .coerceAtLeast(0)
  val minimumUsableInputTokens =
    minOf(DEFAULT_MIN_USABLE_INPUT_TOKENS, (contextWindowTokens / 2).coerceAtLeast(128))
  val minimumReservedOutputTokens =
    minOf(DEFAULT_MIN_RESERVED_OUTPUT_TOKENS, (contextWindowTokens / 4).coerceAtLeast(64))
  var reservedOutputTokens =
    getIntConfigValue(
        key = ConfigKeys.MAX_TOKENS,
        defaultValue = DEFAULT_RESERVED_OUTPUT_TOKENS,
      )
      .coerceAtLeast(minimumReservedOutputTokens)
  var reservedThinkingTokens =
    if (getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)) {
      getIntConfigValue(
          key = ConfigKeys.RESERVED_THINKING_TOKENS,
          defaultValue = DEFAULT_RESERVED_THINKING_TOKENS,
        )
        .coerceAtLeast(0)
    } else {
      0
    }
  var normalizedSafetyMarginTokens = safetyMarginTokens
  val maxReservedTokens = (contextWindowTokens - minimumUsableInputTokens).coerceAtLeast(0)
  var overflow = reservedOutputTokens + reservedThinkingTokens + normalizedSafetyMarginTokens - maxReservedTokens

  if (overflow > 0) {
    val thinkingReduction = minOf(overflow, reservedThinkingTokens)
    reservedThinkingTokens -= thinkingReduction
    overflow -= thinkingReduction
  }
  if (overflow > 0) {
    val reducibleOutputTokens = (reservedOutputTokens - minimumReservedOutputTokens).coerceAtLeast(0)
    val outputReduction = minOf(overflow, reducibleOutputTokens)
    reservedOutputTokens -= outputReduction
    overflow -= outputReduction
  }
  if (overflow > 0) {
    val safetyReduction = minOf(overflow, normalizedSafetyMarginTokens)
    normalizedSafetyMarginTokens -= safetyReduction
  }

  return ModelContextProfile(
    contextWindowTokens = contextWindowTokens,
    reservedOutputTokens = reservedOutputTokens,
    reservedThinkingTokens = reservedThinkingTokens,
    safetyMarginTokens = normalizedSafetyMarginTokens,
  )
}
