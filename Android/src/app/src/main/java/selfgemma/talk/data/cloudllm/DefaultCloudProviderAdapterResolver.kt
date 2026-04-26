package selfgemma.talk.data.cloudllm

import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.cloudllm.CloudLlmProviderAdapter
import selfgemma.talk.domain.cloudllm.CloudProviderAdapterResolver
import selfgemma.talk.domain.cloudllm.CloudProviderId

@Singleton
class DefaultCloudProviderAdapterResolver
@Inject
constructor(
  private val openRouterAdapter: OpenRouterAdapter,
  private val deepSeekAdapter: DeepSeekAdapter,
  private val claudeAdapter: ClaudeAdapter,
) : CloudProviderAdapterResolver {
  override fun adapterFor(providerId: CloudProviderId): CloudLlmProviderAdapter? {
    return when (providerId) {
      CloudProviderId.OPENROUTER -> openRouterAdapter
      CloudProviderId.DEEPSEEK -> deepSeekAdapter
      CloudProviderId.CLAUDE -> claudeAdapter
    }
  }
}
