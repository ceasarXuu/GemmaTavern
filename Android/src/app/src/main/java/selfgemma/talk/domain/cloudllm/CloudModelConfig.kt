package selfgemma.talk.domain.cloudllm

data class CloudModelConfig(
  val enabled: Boolean = false,
  val providerId: CloudProviderId = CloudProviderId.DEEPSEEK,
  val modelName: String = "",
  val allowRawMediaUpload: Boolean = false,
) {
  val readyForConnection: Boolean
    get() = enabled && modelName.isNotBlank()

  val apiKeySecretName: String
    get() = apiKeySecretName(providerId)

  companion object {
    fun apiKeySecretName(providerId: CloudProviderId): String {
      return "cloud_llm_api_key_${providerId.storageId}"
    }
  }
}
