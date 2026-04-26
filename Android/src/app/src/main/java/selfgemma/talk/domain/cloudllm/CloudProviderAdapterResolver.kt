package selfgemma.talk.domain.cloudllm

interface CloudProviderAdapterResolver {
  fun adapterFor(providerId: CloudProviderId): CloudLlmProviderAdapter?
}
