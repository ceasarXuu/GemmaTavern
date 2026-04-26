package selfgemma.talk.domain.cloudllm

interface CloudLlmProviderAdapter {
  val providerId: CloudProviderId

  fun defaultCapability(config: CloudModelConfig): CloudModelCapability

  suspend fun testConnection(config: CloudModelConfig, apiKey: String): CloudConnectionTestResult

  suspend fun streamGenerate(
    request: CloudGenerationRequest,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult
}

data class CloudConnectionTestResult(
  val providerId: CloudProviderId,
  val success: Boolean,
  val modelName: String,
  val error: CloudProviderError? = null,
)
