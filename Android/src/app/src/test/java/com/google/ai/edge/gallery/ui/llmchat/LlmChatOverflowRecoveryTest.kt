package selfgemma.talk.ui.llmchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChatOverflowRecoveryTest {
  @Test
  fun shouldUseAggressiveModePreflight_onlyEscalatesWhenStillOverBudget() {
    assertTrue(
      LlmChatOverflowRecovery.shouldUseAggressiveModePreflight(
        LlmChatContextReport(
          usableInputTokens = 1024,
          reservedForCurrentTurnTokens = 128,
          availableInstructionTokens = 896,
          estimatedInstructionTokens = 1040,
          currentTurnOverflowDetected = false,
          mode = LlmChatContextMode.FULL,
          recentLineCount = 4,
          summaryLineCount = 2,
          droppedLineCount = 3,
          systemPromptTrimmed = false,
        )
      )
    )
    assertFalse(
      LlmChatOverflowRecovery.shouldUseAggressiveModePreflight(
        LlmChatContextReport(
          usableInputTokens = 1024,
          reservedForCurrentTurnTokens = 128,
          availableInstructionTokens = 896,
          estimatedInstructionTokens = 1040,
          currentTurnOverflowDetected = false,
          mode = LlmChatContextMode.AGGRESSIVE,
          recentLineCount = 4,
          summaryLineCount = 2,
          droppedLineCount = 3,
          systemPromptTrimmed = true,
        )
      )
    )
  }

  @Test
  fun isContextOverflow_matchesKnownOverflowShapes() {
    assertTrue(LlmChatOverflowRecovery.isContextOverflow("Error code 3: input token exceeds model limit"))
    assertTrue(LlmChatOverflowRecovery.isContextOverflow("input tokens exceed context window"))
    assertFalse(LlmChatOverflowRecovery.isContextOverflow("Selected model failed to initialize."))
  }

  @Test
  fun shouldUseAggressiveModePreflight_ignoresCurrentTurnOverflow() {
    assertFalse(
      LlmChatOverflowRecovery.shouldUseAggressiveModePreflight(
        LlmChatContextReport(
          usableInputTokens = 512,
          reservedForCurrentTurnTokens = 640,
          availableInstructionTokens = 0,
          estimatedInstructionTokens = 0,
          currentTurnOverflowDetected = true,
          mode = LlmChatContextMode.AGGRESSIVE,
          recentLineCount = 0,
          summaryLineCount = 0,
          droppedLineCount = 0,
          systemPromptTrimmed = false,
        )
      )
    )
  }
}
