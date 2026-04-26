package selfgemma.talk.data.cloudllm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.cloudllm.CloudContentPart
import selfgemma.talk.domain.cloudllm.CloudGenerationEvent
import selfgemma.talk.domain.cloudllm.CloudGenerationRequest
import selfgemma.talk.domain.cloudllm.CloudMessage
import selfgemma.talk.domain.cloudllm.CloudMessageRole
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderErrorType
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.cloudllm.CloudToolSpec

class CloudProviderAdaptersTest {
  @Test
  fun `openrouter sends openai compatible request and parses sse text`() = runTest {
    val httpClient =
      FakeCloudHttpClient(
        CloudHttpResponse(
          statusCode = 200,
          body =
            """
            data: {"choices":[{"delta":{"content":"Hel"}}]}
            data: {"choices":[{"delta":{"content":"lo"}}]}
            data: [DONE]
            """.trimIndent(),
        )
      )
    val adapter = OpenRouterAdapter(httpClient)
    val events = mutableListOf<CloudGenerationEvent>()

    val result = adapter.streamGenerate(sampleRequest(CloudProviderId.OPENROUTER), events::add)

    val captured = checkNotNull(httpClient.lastRequest)
    val body = checkNotNull(captured.body.parseJsonObjectOrNull())
    assertEquals("https://openrouter.ai/api/v1/chat/completions", captured.url)
    assertEquals("Bearer test-key", captured.headers["Authorization"])
    assertEquals("GemmaTavern", captured.headers["X-Title"])
    assertEquals("test-model", body.stringOrNull("model"))
    assertEquals("Hello", result.text)
    assertEquals(listOf("Hel", "lo"), events.filterIsInstance<CloudGenerationEvent.TextDelta>().map { it.text })
  }

  @Test
  fun `deepseek maps rate limit errors as retryable fallback candidates`() = runTest {
    val httpClient =
      FakeCloudHttpClient(
        CloudHttpResponse(
          statusCode = 429,
          body = """{"error":{"message":"rate limited"}}""",
        )
      )
    val adapter = DeepSeekAdapter(httpClient)
    val events = mutableListOf<CloudGenerationEvent>()

    val result = adapter.streamGenerate(sampleRequest(CloudProviderId.DEEPSEEK), events::add)

    val captured = checkNotNull(httpClient.lastRequest)
    assertEquals("https://api.deepseek.com/chat/completions", captured.url)
    assertNotNull(result.error)
    assertEquals(CloudProviderErrorType.RATE_LIMIT, result.error?.type)
    assertEquals(true, result.error?.retryable)
    assertTrue(events.first() is CloudGenerationEvent.Failed)
  }

  @Test
  fun `claude sends messages api request with system prompt and image content`() = runTest {
    val httpClient =
      FakeCloudHttpClient(
        CloudHttpResponse(
          statusCode = 200,
          body = """{"content":[{"type":"text","text":"Seen."}]}""",
        )
      )
    val adapter = ClaudeAdapter(httpClient)

    val result =
      adapter.streamGenerate(
        sampleRequest(CloudProviderId.CLAUDE)
          .copy(
            messages =
              listOf(
                CloudMessage(CloudMessageRole.SYSTEM, listOf(CloudContentPart.Text("Stay in character."))),
                CloudMessage(
                  CloudMessageRole.USER,
                  listOf(
                    CloudContentPart.Text("What is in this image?"),
                    CloudContentPart.Image(mimeType = "image/png", dataBase64 = "abc123"),
                  ),
                ),
              )
          ),
        onEvent = {},
      )

    val captured = checkNotNull(httpClient.lastRequest)
    val body = checkNotNull(captured.body.parseJsonObjectOrNull())
    val messages = checkNotNull(body.arrayOrNull("messages"))
    val content = messages[0].asJsonObject.arrayOrNull("content")
    assertEquals("https://api.anthropic.com/v1/messages", captured.url)
    assertEquals("test-key", captured.headers["x-api-key"])
    assertEquals("Stay in character.", body.stringOrNull("system"))
    assertEquals("image", content?.get(1)?.asJsonObject?.stringOrNull("type"))
    assertEquals("Seen.", result.text)
  }

  @Test
  fun `claude parses tool use blocks into internal tool calls`() = runTest {
    val httpClient =
      FakeCloudHttpClient(
        CloudHttpResponse(
          statusCode = 200,
          body =
            """
            {"content":[
              {"type":"text","text":"Checking."},
              {"type":"tool_use","id":"tool-1","name":"getTime","input":{"zone":"local"}}
            ]}
            """.trimIndent(),
        )
      )
    val adapter = ClaudeAdapter(httpClient)

    val result = adapter.streamGenerate(sampleRequest(CloudProviderId.CLAUDE), onEvent = {})

    assertEquals("Checking.", result.text)
    assertEquals("tool-1", result.toolCalls.single().id)
    assertEquals("getTime", result.toolCalls.single().name)
    assertEquals("""{"zone":"local"}""", result.toolCalls.single().argumentsJson)
  }

  private fun sampleRequest(providerId: CloudProviderId): CloudGenerationRequest {
    return CloudGenerationRequest(
      config = CloudModelConfig(enabled = true, providerId = providerId, modelName = "test-model"),
      apiKey = "test-key",
      stream = true,
      messages =
        listOf(
          CloudMessage(
            role = CloudMessageRole.USER,
            parts = listOf(CloudContentPart.Text("Hello")),
          )
        ),
      tools =
        listOf(
          CloudToolSpec(
            name = "getTime",
            description = "Reads local time.",
            parametersJson = """{"type":"object","properties":{}}""",
          )
        ),
    )
  }
}

private class FakeCloudHttpClient(private val response: CloudHttpResponse) : CloudHttpClient {
  var lastRequest: CloudHttpRequest? = null

  override suspend fun execute(request: CloudHttpRequest): CloudHttpResponse {
    lastRequest = request
    return response
  }
}
