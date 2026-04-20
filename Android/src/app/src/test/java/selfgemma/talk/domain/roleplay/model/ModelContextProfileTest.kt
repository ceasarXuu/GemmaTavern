package selfgemma.talk.domain.roleplay.model

import org.junit.Assert.assertEquals
import org.junit.Test
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model

class ModelContextProfileTest {
  @Test
  fun toModelContextProfile_usesConservativeDefaultsFor4kModel() {
    val model = Model(name = "gemma-4k", llmMaxToken = 4096)

    val profile = model.toModelContextProfile()

    assertEquals(4096, profile.contextWindowTokens)
    assertEquals(768, profile.reservedOutputTokens)
    assertEquals(0, profile.reservedThinkingTokens)
    assertEquals(256, profile.safetyMarginTokens)
    assertEquals(3072, profile.usableInputTokens)
  }

  @Test
  fun toModelContextProfile_respectsExplicitConfigOverrides() {
    val model =
      Model(name = "gemma-custom", llmMaxToken = 4096).apply {
        configValues =
          mapOf(
            ConfigKeys.CONTEXT_WINDOW_TOKENS.label to 6144,
            ConfigKeys.MAX_TOKENS.label to 1024,
            ConfigKeys.ENABLE_THINKING.label to true,
            ConfigKeys.RESERVED_THINKING_TOKENS.label to 384,
            ConfigKeys.CONTEXT_SAFETY_MARGIN_TOKENS.label to 320,
          )
      }

    val profile = model.toModelContextProfile()

    assertEquals(6144, profile.contextWindowTokens)
    assertEquals(1024, profile.reservedOutputTokens)
    assertEquals(384, profile.reservedThinkingTokens)
    assertEquals(320, profile.safetyMarginTokens)
    assertEquals(4416, profile.usableInputTokens)
  }

  @Test
  fun toModelContextProfile_shrinksReservedTokensToPreserveInputRoom() {
    val model =
      Model(name = "gemma-tight", llmMaxToken = 2048).apply {
        configValues =
          mapOf(
            ConfigKeys.MAX_TOKENS.label to 1600,
            ConfigKeys.ENABLE_THINKING.label to true,
            ConfigKeys.RESERVED_THINKING_TOKENS.label to 400,
            ConfigKeys.CONTEXT_SAFETY_MARGIN_TOKENS.label to 300,
          )
      }

    val profile = model.toModelContextProfile()

    assertEquals(2048, profile.contextWindowTokens)
    assertEquals(1236, profile.reservedOutputTokens)
    assertEquals(0, profile.reservedThinkingTokens)
    assertEquals(300, profile.safetyMarginTokens)
    assertEquals(512, profile.usableInputTokens)
  }
}
