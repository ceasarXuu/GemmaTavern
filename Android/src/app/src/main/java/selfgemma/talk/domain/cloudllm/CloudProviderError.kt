package selfgemma.talk.domain.cloudllm

enum class CloudProviderErrorType {
  AUTHENTICATION,
  RATE_LIMIT,
  TIMEOUT,
  SERVER,
  NETWORK,
  CAPABILITY_MISMATCH,
  INVALID_REQUEST,
  UNKNOWN,
}

data class CloudProviderError(
  val type: CloudProviderErrorType,
  val providerId: CloudProviderId,
  val statusCode: Int? = null,
  val retryable: Boolean = false,
  val message: String = "",
)
