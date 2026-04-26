package selfgemma.talk.domain.cloudllm

import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudProviderHealthTracker @Inject constructor() {
  private val cooldownUntilMs = EnumMap<CloudProviderId, Long>(CloudProviderId::class.java)

  @Synchronized
  fun isHealthy(providerId: CloudProviderId): Boolean {
    val cooldownUntil = cooldownUntilMs[providerId] ?: return true
    if (System.currentTimeMillis() >= cooldownUntil) {
      cooldownUntilMs.remove(providerId)
      return true
    }
    return false
  }

  @Synchronized
  fun recordSuccess(providerId: CloudProviderId) {
    cooldownUntilMs.remove(providerId)
  }

  @Synchronized
  fun recordFailure(error: CloudProviderError) {
    if (!error.retryable && error.type != CloudProviderErrorType.AUTHENTICATION) {
      return
    }
    val cooldownMs =
      when (error.type) {
        CloudProviderErrorType.AUTHENTICATION -> AUTH_COOLDOWN_MS
        CloudProviderErrorType.RATE_LIMIT -> RATE_LIMIT_COOLDOWN_MS
        else -> RETRYABLE_COOLDOWN_MS
      }
    cooldownUntilMs[error.providerId] = System.currentTimeMillis() + cooldownMs
  }

  private companion object {
    const val RETRYABLE_COOLDOWN_MS = 60_000L
    const val RATE_LIMIT_COOLDOWN_MS = 120_000L
    const val AUTH_COOLDOWN_MS = 30_000L
  }
}
