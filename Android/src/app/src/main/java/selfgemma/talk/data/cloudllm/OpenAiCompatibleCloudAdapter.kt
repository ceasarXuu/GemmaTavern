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

abstract class OpenAiCompatibleCloudAdapter(
  private val httpClient: CloudHttpClient,
  override val providerId: CloudProviderId,
  private val endpointUrl: String,
  private val capability: CloudModelCapability,
  private val extraHeaders: Map<String, String> = emptyMap(),
) : CloudLlmProviderAdapter {
  override fun defaultCapability(config: CloudModelConfig): CloudModelCapability = capability

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
    val response = httpClient.execute(buildHttpRequest(request))
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

  internal fun buildHttpRequest(request: CloudGenerationRequest): CloudHttpRequest {
    return CloudHttpRequest(
      url = endpointUrl,
      headers =
        buildMap {
          put("Authorization", "Bearer ${request.apiKey}")
          put("Content-Type", "application/json")
          putAll(extraHeaders)
        },
      body = buildRequestBody(request).toString(),
    )
  }

  private fun buildRequestBody(request: CloudGenerationRequest): JsonObject {
    return JsonObject().apply {
      addProperty("model", request.config.modelName)
      addProperty("stream", request.stream)
      request.maxOutputTokens?.let { addProperty("max_tokens", it) }
      request.temperature?.let { addProperty("temperature", it) }
      add("messages", JsonArray().also { array -> request.messages.forEach { array.add(it.toOpenAiJson()) } })
      if (request.tools.isNotEmpty()) {
        add("tools", JsonArray().also { array -> request.tools.forEach { array.add(it.toOpenAiJson()) } })
      }
    }
  }

  private suspend fun parseJsonResponse(
    body: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val root = body.parseJsonObjectOrNull() ?: return CloudGenerationResult(text = "")
    val choice = root.arrayOrNull("choices")?.firstOrNull()?.asJsonObject
    val message = choice?.objOrNull("message")
    val text = message?.stringOrNull("content").orEmpty()
    if (text.isNotBlank()) {
      onEvent(CloudGenerationEvent.TextDelta(text))
    }
    return CloudGenerationResult(
      text = text,
      toolCalls = message?.arrayOrNull("tool_calls").toOpenAiToolCalls(),
    )
  }

  private suspend fun parseStreamResponse(
    body: String,
    onEvent: suspend (CloudGenerationEvent) -> Unit,
  ): CloudGenerationResult {
    val text = StringBuilder()
    extractSseDataLines(body).forEach { data ->
      val delta =
        data
          .parseJsonObjectOrNull()
          ?.arrayOrNull("choices")
          ?.firstOrNull()
          ?.asJsonObject
          ?.objOrNull("delta")
          ?.stringOrNull("content")
      if (!delta.isNullOrEmpty()) {
        text.append(delta)
        onEvent(CloudGenerationEvent.TextDelta(delta))
      }
    }
    return CloudGenerationResult(text = text.toString())
  }

  private fun CloudMessage.toOpenAiJson(): JsonObject {
    return JsonObject().apply {
      addProperty("role", role.openAiRole())
      val hasStructuredParts = parts.any { part -> part is CloudContentPart.Image && part.dataBase64 != null }
      if (!hasStructuredParts) {
        addProperty("content", textContent())
      } else {
        add("content", JsonArray().also { array -> parts.forEach { array.add(it.toOpenAiContentPart()) } })
      }
    }
  }

  private fun CloudContentPart.toOpenAiContentPart(): JsonObject {
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
            addProperty("type", "image_url")
            add(
              "image_url",
              JsonObject().apply { addProperty("url", "data:$mimeType;base64,$dataBase64") },
            )
          }
        }
    }
  }

  private fun CloudToolSpec.toOpenAiJson(): JsonObject {
    return JsonObject().apply {
      addProperty("type", "function")
      add(
        "function",
        JsonObject().apply {
          addProperty("name", name)
          addProperty("description", description)
          add("parameters", parametersJson.parseJsonElementOrNull() ?: JsonObject())
        },
      )
    }
  }

  private fun JsonArray?.toOpenAiToolCalls(): List<CloudToolCall> {
    return this?.mapNotNull { element ->
      val obj = element.asJsonObject
      val function = obj.objOrNull("function") ?: return@mapNotNull null
      CloudToolCall(
        id = obj.stringOrNull("id").orEmpty(),
        name = function.stringOrNull("name").orEmpty(),
        argumentsJson = function.stringOrNull("arguments").orEmpty(),
      )
    } ?: emptyList()
  }
}

@Singleton
class OpenRouterAdapter @Inject constructor(httpClient: CloudHttpClient) :
  OpenAiCompatibleCloudAdapter(
    httpClient = httpClient,
    providerId = CloudProviderId.OPENROUTER,
    endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
    capability =
      CloudModelCapability(
        supportsToolCalling = true,
        supportsImageInput = true,
      ),
    extraHeaders =
      mapOf(
        "HTTP-Referer" to "https://github.com/ceasarXuu/GemmaTavern",
        "X-Title" to "GemmaTavern",
      ),
  )

@Singleton
class DeepSeekAdapter @Inject constructor(httpClient: CloudHttpClient) :
  OpenAiCompatibleCloudAdapter(
    httpClient = httpClient,
    providerId = CloudProviderId.DEEPSEEK,
    endpointUrl = "https://api.deepseek.com/chat/completions",
    capability =
      CloudModelCapability(
        supportsToolCalling = true,
        contextWindowTokens = 1_000_000,
      ),
  )
