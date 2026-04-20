package selfgemma.talk.ui.llmchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.ModelContextProfile
import selfgemma.talk.ui.common.chat.ChatMessageText
import selfgemma.talk.ui.common.chat.ChatSide

class LlmChatContextManagerTest {
  private val contextManager = LlmChatContextManager()

  @Test
  fun buildPlan_compactsHistoryWhenBudgetIsTight() {
    val history =
      buildList {
        repeat(10) { index ->
          add(
            ChatMessageText(
              content = "User turn $index " + "detail ".repeat(20),
              side = ChatSide.USER,
            )
          )
          add(
            ChatMessageText(
              content = "Assistant turn $index " + "reply ".repeat(20),
              side = ChatSide.AGENT,
            )
          )
        }
      }

    val plan =
      contextManager.buildPlan(
        baseSystemPrompt = "You are a helpful assistant. " + "policy ".repeat(80),
        historyMessages = history,
        pendingInput = "Summarize the conversation so far.",
        pendingImageCount = 0,
        pendingAudioCount = 0,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 1024,
            reservedOutputTokens = 256,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 256,
          ),
      )

    assertTrue(plan.report.mode == LlmChatContextMode.AGGRESSIVE)
    assertTrue(plan.report.summaryLineCount > 0)
    assertTrue(plan.report.droppedLineCount > 0)
    assertTrue(plan.report.recentLineCount > 0)
  }

  @Test
  fun buildPlan_reservesTokensForCurrentMultimodalTurn() {
    val plan =
      contextManager.buildPlan(
        baseSystemPrompt = "",
        historyMessages =
          listOf(
            ChatMessageText(content = "Previous user turn", side = ChatSide.USER),
            ChatMessageText(content = "Previous assistant turn", side = ChatSide.AGENT),
          ),
        pendingInput = "What is in this image and audio clip?",
        pendingImageCount = 1,
        pendingAudioCount = 1,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 2048,
            reservedOutputTokens = 512,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 256,
          ),
      )

    assertEquals(1280, plan.report.usableInputTokens)
    assertTrue(plan.report.reservedForCurrentTurnTokens >= 256 + 192)
    assertTrue(plan.report.availableInstructionTokens < plan.report.usableInputTokens)
  }

  @Test
  fun buildPlan_marksCurrentTurnOverflowWhenPendingInputAlreadyExceedsBudget() {
    val longInput = "overflow ".repeat(400)

    val plan =
      contextManager.buildPlan(
        baseSystemPrompt = "You are a concise assistant.",
        historyMessages = emptyList(),
        pendingInput = longInput,
        pendingImageCount = 0,
        pendingAudioCount = 0,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 1024,
            reservedOutputTokens = 256,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 256,
          ),
      )

    assertTrue(plan.report.currentTurnOverflowDetected)
    assertEquals(0, plan.report.availableInstructionTokens)
  }

  @Test
  fun buildPlan_keepsInstructionEstimateWithinTinyBudget() {
    val history =
      listOf(
        ChatMessageText(content = "User " + "detail ".repeat(40), side = ChatSide.USER),
        ChatMessageText(content = "Assistant " + "reply ".repeat(40), side = ChatSide.AGENT),
      )

    val plan =
      contextManager.buildPlan(
        baseSystemPrompt = "Rule ".repeat(40),
        historyMessages = history,
        pendingInput = "Short question",
        pendingImageCount = 0,
        pendingAudioCount = 0,
        contextProfile =
          ModelContextProfile(
            contextWindowTokens = 512,
            reservedOutputTokens = 256,
            reservedThinkingTokens = 0,
            safetyMarginTokens = 200,
          ),
      )

    assertFalse(plan.report.currentTurnOverflowDetected)
    assertTrue(plan.report.estimatedInstructionTokens <= plan.report.availableInstructionTokens)
  }
}
