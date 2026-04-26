package selfgemma.talk.data.cloudllm

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderId

@Singleton
class CloudModelConfigRepository
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val credentialStore: CloudCredentialStore,
) {
  fun getConfig(): CloudModelConfig {
    return dataStoreRepository.getCloudModelConfig()
  }

  fun saveConfig(config: CloudModelConfig) {
    dataStoreRepository.setCloudModelConfig(config)
  }

  fun saveApiKey(providerId: CloudProviderId, apiKey: String) {
    val trimmed = apiKey.trim()
    if (trimmed.isBlank()) {
      deleteApiKey(providerId)
      return
    }
    credentialStore.saveSecret(CloudModelConfig.apiKeySecretName(providerId), trimmed)
  }

  fun getApiKey(providerId: CloudProviderId): String? {
    return credentialStore
      .readSecret(CloudModelConfig.apiKeySecretName(providerId))
      ?.takeIf(String::isNotBlank)
  }

  fun deleteApiKey(providerId: CloudProviderId) {
    credentialStore.deleteSecret(CloudModelConfig.apiKeySecretName(providerId))
  }
}
