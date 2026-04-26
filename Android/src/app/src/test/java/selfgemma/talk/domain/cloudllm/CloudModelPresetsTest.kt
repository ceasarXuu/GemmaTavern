package selfgemma.talk.domain.cloudllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelPresetsTest {
  @Test
  fun `deepseek exposes flash and pro presets with flash recommended`() {
    val presets = CloudModelPresets.forProvider(CloudProviderId.DEEPSEEK)

    assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), presets.map { it.modelName })
    assertTrue(presets.first { it.modelName == "deepseek-v4-flash" }.recommended)
    assertFalse(presets.first { it.modelName == "deepseek-v4-pro" }.recommended)
  }

  @Test
  fun `claude exposes current haiku sonnet and opus presets`() {
    val presets = CloudModelPresets.forProvider(CloudProviderId.CLAUDE)

    assertEquals(
      listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5"),
      presets.map { it.modelName },
    )
    assertTrue(presets.first { it.modelName == "claude-sonnet-4-6" }.recommended)
  }

  @Test
  fun `openrouter intentionally has no in-app model catalog`() {
    assertTrue(CloudModelPresets.forProvider(CloudProviderId.OPENROUTER).isEmpty())
  }

  @Test
  fun `cloud config derives provider scoped secret names`() {
    assertEquals(
      "cloud_llm_api_key_deepseek",
      CloudModelConfig.apiKeySecretName(CloudProviderId.DEEPSEEK),
    )
    assertEquals(
      "cloud_llm_api_key_claude",
      CloudModelConfig.apiKeySecretName(CloudProviderId.CLAUDE),
    )
  }
}
