package selfgemma.talk.domain.cloudllm

data class CloudModelCapability(
  val supportsText: Boolean = true,
  val supportsStreaming: Boolean = true,
  val supportsToolCalling: Boolean = false,
  val supportsImageInput: Boolean = false,
  val supportsAudioInput: Boolean = false,
  val contextWindowTokens: Int? = null,
  val maxOutputTokens: Int? = null,
)
