package selfgemma.talk.domain.cloudllm

data class CloudGenerationRequest(
  val config: CloudModelConfig,
  val apiKey: String,
  val messages: List<CloudMessage>,
  val tools: List<CloudToolSpec> = emptyList(),
  val stream: Boolean = true,
  val maxOutputTokens: Int? = null,
  val temperature: Float? = null,
)

data class CloudMessage(
  val role: CloudMessageRole,
  val parts: List<CloudContentPart>,
)

enum class CloudMessageRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

sealed interface CloudContentPart {
  data class Text(val text: String) : CloudContentPart

  data class Image(
    val mimeType: String,
    val dataBase64: String? = null,
    val localContextText: String? = null,
  ) : CloudContentPart

  data class AudioTranscript(
    val text: String,
    val mimeType: String? = null,
  ) : CloudContentPart
}

data class CloudToolSpec(
  val name: String,
  val description: String,
  val parametersJson: String,
)

data class CloudToolCall(
  val id: String,
  val name: String,
  val argumentsJson: String,
)

sealed interface CloudGenerationEvent {
  data class TextDelta(val text: String) : CloudGenerationEvent

  data class ToolCallDelta(val toolCall: CloudToolCall) : CloudGenerationEvent

  data class Failed(val error: CloudProviderError) : CloudGenerationEvent

  data object Completed : CloudGenerationEvent
}

data class CloudGenerationResult(
  val text: String = "",
  val toolCalls: List<CloudToolCall> = emptyList(),
  val error: CloudProviderError? = null,
)
