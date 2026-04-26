package selfgemma.talk.domain.cloudllm

data class CloudModelPreset(
  val providerId: CloudProviderId,
  val displayName: String,
  val modelName: String,
  val recommended: Boolean = false,
  val capability: CloudModelCapability = CloudModelCapability(),
)

object CloudModelPresets {
  val all: List<CloudModelPreset> =
    listOf(
      CloudModelPreset(
        providerId = CloudProviderId.DEEPSEEK,
        displayName = "DeepSeek V4 Flash",
        modelName = "deepseek-v4-flash",
        recommended = true,
        capability =
          CloudModelCapability(
            supportsToolCalling = true,
            contextWindowTokens = 1_000_000,
          ),
      ),
      CloudModelPreset(
        providerId = CloudProviderId.DEEPSEEK,
        displayName = "DeepSeek V4 Pro",
        modelName = "deepseek-v4-pro",
        capability =
          CloudModelCapability(
            supportsToolCalling = true,
            contextWindowTokens = 1_000_000,
          ),
      ),
      CloudModelPreset(
        providerId = CloudProviderId.CLAUDE,
        displayName = "Claude Sonnet 4.6",
        modelName = "claude-sonnet-4-6",
        recommended = true,
        capability =
          CloudModelCapability(
            supportsToolCalling = true,
            supportsImageInput = true,
          ),
      ),
      CloudModelPreset(
        providerId = CloudProviderId.CLAUDE,
        displayName = "Claude Opus 4.6",
        modelName = "claude-opus-4-6",
        capability =
          CloudModelCapability(
            supportsToolCalling = true,
            supportsImageInput = true,
          ),
      ),
      CloudModelPreset(
        providerId = CloudProviderId.CLAUDE,
        displayName = "Claude Haiku 4.5",
        modelName = "claude-haiku-4-5",
        capability =
          CloudModelCapability(
            supportsToolCalling = true,
            supportsImageInput = true,
          ),
      ),
    )

  fun forProvider(providerId: CloudProviderId): List<CloudModelPreset> {
    return all.filter { preset -> preset.providerId == providerId }
  }
}
