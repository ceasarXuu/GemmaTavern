package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOverflowRecoveryTest {
  @Test
  fun shouldUseAggressiveModePreflight_onlyEscalatesWhenStillOverBudget() {
    assertTrue(
      ContextOverflowRecovery.shouldUseAggressiveModePreflight(
        PromptBudgetReport(
          usableInputTokens = 512,
          estimatedInputTokens = 640,
          mode = PromptBudgetMode.FULL,
        )
      )
    )
    assertFalse(
      ContextOverflowRecovery.shouldUseAggressiveModePreflight(
        PromptBudgetReport(
          usableInputTokens = 512,
          estimatedInputTokens = 640,
          mode = PromptBudgetMode.AGGRESSIVE,
        )
      )
    )
    assertFalse(
      ContextOverflowRecovery.shouldUseAggressiveModePreflight(
        PromptBudgetReport(
          usableInputTokens = 512,
          estimatedInputTokens = 480,
          mode = PromptBudgetMode.FULL,
        )
      )
    )
  }

  @Test
  fun isContextOverflow_matchesKnownOverflowShapes() {
    assertTrue(ContextOverflowRecovery.isContextOverflow("Error code 3: input token exceeds model limit"))
    assertTrue(ContextOverflowRecovery.isContextOverflow("input tokens exceed context window"))
    assertTrue(ContextOverflowRecovery.isContextOverflow("token limit reached"))
  }

  @Test
  fun isContextOverflow_ignoresUnrelatedRuntimeFailures() {
    assertFalse(ContextOverflowRecovery.isContextOverflow("Selected model failed to initialize."))
    assertFalse(ContextOverflowRecovery.isContextOverflow("network unavailable"))
    assertFalse(ContextOverflowRecovery.isContextOverflow(null))
  }
}
