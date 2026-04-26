package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.data.cloudllm.CloudCredentialStore
import selfgemma.talk.data.cloudllm.CloudModelConfigRepository
import selfgemma.talk.domain.cloudllm.CloudLlmProviderAdapter
import selfgemma.talk.domain.cloudllm.CloudNetworkStatusProvider
import selfgemma.talk.domain.cloudllm.CloudProviderAdapterResolver
import selfgemma.talk.domain.cloudllm.CloudProviderHealthTracker
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.testing.FakeDataStoreRepository

internal fun disabledCloudInferenceCoordinator(
  conversationRepository: ConversationRepository,
): CloudRoleplayInferenceCoordinator {
  return CloudRoleplayInferenceCoordinator(
    configRepository = CloudModelConfigRepository(FakeDataStoreRepository(), InMemoryCloudCredentialStore()),
    adapterResolver =
      object : CloudProviderAdapterResolver {
        override fun adapterFor(providerId: CloudProviderId): CloudLlmProviderAdapter? = null
      },
    networkStatusProvider =
      object : CloudNetworkStatusProvider {
        override fun isNetworkAvailable(): Boolean = false
      },
    providerHealthTracker = CloudProviderHealthTracker(),
    conversationRepository = conversationRepository,
    eventLogger = CloudRoleplayEventLogger(conversationRepository),
  )
}

private class InMemoryCloudCredentialStore : CloudCredentialStore {
  private val secrets = mutableMapOf<String, String>()

  override fun saveSecret(secretName: String, value: String) {
    secrets[secretName] = value
  }

  override fun readSecret(secretName: String): String? = secrets[secretName]

  override fun deleteSecret(secretName: String) {
    secrets.remove(secretName)
  }
}
