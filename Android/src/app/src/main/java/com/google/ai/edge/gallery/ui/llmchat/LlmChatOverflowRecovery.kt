package selfgemma.talk.ui.llmchat

import selfgemma.talk.runtime.isContextOverflowError
import selfgemma.talk.runtime.toUserFacingContextOverflowMessage

internal object LlmChatOverflowRecovery {
  const val MAX_OVERFLOW_RETRIES = 1

  fun shouldUseAggressiveModePreflight(report: LlmChatContextReport): Boolean {
    if (report.currentTurnOverflowDetected) {
      return false
    }
    return report.estimatedInstructionTokens > report.availableInstructionTokens &&
      report.mode != LlmChatContextMode.AGGRESSIVE
  }

  fun isContextOverflow(message: String?): Boolean {
    return isContextOverflowError(message)
  }

  fun toUserMessage(message: String?): String {
    return toUserFacingContextOverflowMessage(message)
  }
}
