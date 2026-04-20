package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.runtime.isContextOverflowError
import selfgemma.talk.runtime.toUserFacingContextOverflowMessage

internal object ContextOverflowRecovery {
  const val MAX_OVERFLOW_RETRIES = 1

  fun shouldUseAggressiveModePreflight(report: PromptBudgetReport?): Boolean {
    return report != null && report.estimatedInputTokens > report.usableInputTokens && report.mode != PromptBudgetMode.AGGRESSIVE
  }

  fun isContextOverflow(message: String?): Boolean {
    return isContextOverflowError(message)
  }

  fun toUserFacingError(message: String?): String {
    return toUserFacingContextOverflowMessage(message)
  }
}
