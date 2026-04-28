package selfgemma.talk.data.cloudllm

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.cloudllm.CloudConnectionTestResult
import selfgemma.talk.domain.cloudllm.CloudContentPart
import selfgemma.talk.domain.cloudllm.CloudGenerationEvent
import selfgemma.talk.domain.cloudllm.CloudGenerationRequest
import selfgemma.talk.domain.cloudllm.CloudGenerationResult
import selfgemma.talk.domain.cloudllm.CloudLlmProviderAdapter
import selfgemma.talk.domain.cloudllm.CloudMessage
import selfgemma.talk.domain.cloudllm.CloudMessageRole
import selfgemma.talk.domain.cloudllm.CloudModelCapability
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderError
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.cloudllm.CloudToolCall
import selfgemma.talk.domain.cloudllm.CloudToolSpec

@Singleton
class ClaudeAdapter @Inject constructor(private val httpClient: CloudHttpClient) : CloudLlmProviderAdapter {
  override val providerId: CloudProviderId = CloudProviderId.CLAUDE

  override fun defaultCapability(config: CloudModelConfig): CloudModelCapability {
    return CloudModelCapability(
      supportsStreaming = true,
      supportsToolCalling = true,
      supportsImageInput = true,
    )
  }

  override fun mapError(statusCode: Int, body: String?): CloudProviderError {
    return commonError(providerId = providerId, statusCode = statusCode, body = body)
  }

  override suspend fun testConnection(
    config: CloudModelConfig,
    apiKey: String,
  ): CloudConnectionTestResult {
    val result =
      streamGenerate(
        request =
          CloudGenerationRequest(
            config = config,
            apiKey = apiKey,
            stream = false,
            maxOutputTokens = 1,
            messages =
              listOf(
                CloudMessage(
                  role = CloudMessageRole.USER,
                  parts = listOf(CloudContentPart.Text("ping")),
                )
              ),
          ),
        onEvent = {},
      )
    return CloudConnectionTestResult(
      providerId = providerId,
      success = result.error == null,
      modelName = config.modelName,
      error = result.error,
    )
  }

  override suspend fun streamGenerate(
    request: CloudGenerationRequest,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val httpRequest = buildHttpRequest(request)
    if (request.stream) {
      return streamSseResponse(httpRequest, onEvent)
    }
    val response = httpClient.execute(httpRequest)
    if (response.statusCode !in 200..299) {
      val error = mapError(response.statusCode, response.body)
      onEvent(CloudGenerationEvent.Failed(error))
      return CloudGenerationResult(error = error)
    }
    val result =
      if (response.body.contains("data:")) {
        parseStreamResponse(response.body, onEvent)
      } else {
        parseJsonResponse(response.body, onEvent)
      }
    onEvent(CloudGenerationEvent.Completed)
    return result
  }

  private suspend fun streamSseResponse(
    request: CloudHttpRequest,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val text = StringBuilder()
    val response =
      httpClient.stream(request) { line ->
        parseStreamLine(line, onEvent)?.let { delta -> text.append(delta) }
      }
    if (response.statusCode !in 200..299) {
      val error = mapError(response.statusCode, response.body)
      onEvent(CloudGenerationEvent.Failed(error))
      return CloudGenerationResult(error = error)
    }
    val result =
      if (text.isNotBlank() || response.body.contains("data:")) {
        CloudGenerationResult(text = text.toString())
      } else {
        parseJsonResponse(response.body, onEvent)
      }
    onEvent(CloudGenerationEvent.Completed)
    return result
  }

  internal fun buildHttpRequest(request: CloudGenerationRequest): CloudHttpRequest {
    return CloudHttpRequest(
      url = ENDPOINT_URL,
      headers =
        mapOf(
          "x-api-key" to request.apiKey,
          "anthropic-version" to ANTHROPIC_VERSION,
          "content-type" to "application/json",
        ),
      body = buildRequestBody(request).toString(),
    )
  }

  private fun buildRequestBody(request: CloudGenerationRequest): JsonObject {
    val systemText =
      request.messages
        .filter { message -> message.role == CloudMessageRole.SYSTEM }
        .joinToString(separator = "\n") { message -> message.textContent() }
    return JsonObject().apply {
      addProperty("model", request.config.modelName)
      addProperty("stream", request.stream)
      addProperty("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
      if (systemText.isNotBlank()) {
        addProperty("system", systemText)
      }
      add(
        "messages",
        JsonArray().also { array ->
          request.messages
            .filterNot { message -> message.role == CloudMessageRole.SYSTEM }
            .forEach { message -> array.add(message.toClaudeJson()) }
        },
      )
      if (request.tools.isNotEmpty()) {
        add("tools", JsonArray().also { array -> request.tools.forEach { array.add(it.toClaudeJson()) } })
      }
    }
  }

  private suspend fun parseJsonResponse(
    body: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val root = body.parseJsonObjectOrNull() ?: return CloudGenerationResult(text = "")
    val text = StringBuilder()
    val toolCalls = mutableListOf<CloudToolCall>()
    root.arrayOrNull("content")?.forEach { element ->
      val obj = element.asJsonObject
      when (obj.stringOrNull("type")) {
        "text" -> text.append(obj.stringOrNull("text").orEmpty())
        "tool_use" ->
          toolCalls +=
            CloudToolCall(
              id = obj.stringOrNull("id").orEmpty(),
              name = obj.stringOrNull("name").orEmpty(),
              argumentsJson = obj.objOrNull("input")?.toString().orEmpty(),
            )
      }
    }
    if (text.isNotBlank()) {
      onEvent(CloudGenerationEvent.TextDelta(text.toString()))
    }
    return CloudGenerationResult(text = text.toString(), toolCalls = toolCalls)
  }

  private suspend fun parseStreamResponse(
    body: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val text = StringBuilder()
    extractSseDataLines(body).forEach { data ->
      parseClaudeStreamData(data, onEvent)?.let { delta -> text.append(delta) }
    }
    return CloudGenerationResult(text = text.toString())
  }

  private suspend fun parseStreamLine(
    line: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): String? {
    val data = extractSseDataLine(line) ?: return null
    return parseClaudeStreamData(data, onEvent)
  }

  private suspend fun parseClaudeStreamData(
    data: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): String? {
    val obj = data.parseJsonObjectOrNull() ?: return null
    val delta =
      obj
        .takeIf { it.stringOrNull("type") == "content_block_delta" }
        ?.objOrNull("delta")
        ?.stringOrNull("text")
    if (delta.isNullOrBlank()) {
      return null
    }
    onEvent(CloudGenerationEvent.TextDelta(delta))
    return delta
  }

  private fun CloudMessage.toClaudeJson(): JsonObject {
    return JsonObject().apply {
      addProperty("role", role.claudeRole())
      add("content", JsonArray().also { array -> parts.forEach { part -> array.add(part.toClaudePart()) } })
    }
  }

  private fun CloudContentPart.toClaudePart(): JsonObject {
    return when (this) {
      is CloudContentPart.Text -> JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
      }
      is CloudContentPart.AudioTranscript -> JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
      }
      is CloudContentPart.Image ->
        if (dataBase64 == null) {
          JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", localContextText.orEmpty())
          }
        } else {
          JsonObject().apply {
            addProperty("type", "image")
            add(
              "source",
              JsonObject().apply {
                addProperty("type", "base64")
                addProperty("media_type", mimeType)
                addProperty("data", dataBase64)
              },
            )
          }
        }
    }
  }

  private fun CloudToolSpec.toClaudeJson(): JsonObject {
    return JsonObject().apply {
      addProperty("name", name)
      addProperty("description", description)
      add("input_schema", parametersJson.parseJsonElementOrNull() ?: JsonObject())
    }
  }

  private companion object {
    const val ENDPOINT_URL = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val DEFAULT_MAX_TOKENS = 1024
  }
}
