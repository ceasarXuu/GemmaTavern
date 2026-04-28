package selfgemma.talk.domain.roleplay.usecase

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.cloudllm.CloudCredentialStore
import selfgemma.talk.data.cloudllm.CloudModelConfigRepository
import selfgemma.talk.domain.cloudllm.CloudConnectionTestResult
import selfgemma.talk.domain.cloudllm.CloudContentPart
import selfgemma.talk.domain.cloudllm.CloudGenerationEvent
import selfgemma.talk.domain.cloudllm.CloudGenerationRequest
import selfgemma.talk.domain.cloudllm.CloudGenerationResult
import selfgemma.talk.domain.cloudllm.CloudLlmProviderAdapter
import selfgemma.talk.domain.cloudllm.CloudMessage
import selfgemma.talk.domain.cloudllm.CloudModelCapability
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderAdapterResolver
import selfgemma.talk.domain.cloudllm.CloudProviderError
import selfgemma.talk.domain.cloudllm.CloudProviderErrorType
import selfgemma.talk.domain.cloudllm.CloudProviderHealthTracker
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.cloudllm.CloudToolCall
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.RoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.testing.FakeDataStoreRepository

class CloudRoleplayInferenceCoordinatorTest {
  @Test
  fun tryGenerate_whenCloudDisabledDoesNotCallProvider() =
    runBlocking {
      val fixture = cloudFixture(config = CloudModelConfig(enabled = false))

      val outcome = fixture.coordinator.tryGenerate(fixture.request())

      assertEquals(CloudRoleplayInferenceOutcome.NotAttempted, outcome)
      assertTrue(fixture.adapter.requests.isEmpty())
      assertTrue(fixture.conversationRepository.events.isEmpty())
    }

  @Test
  fun tryGenerate_completesWithCloudTextAndRouteEvents() =
    runBlocking {
      val fixture = cloudFixture()
      fixture.adapter.results += CloudGenerationResult(text = "Cloud reply.")

      val outcome = fixture.coordinator.tryGenerate(fixture.request())

      val completed = outcome as CloudRoleplayInferenceOutcome.Completed
      assertEquals(MessageStatus.COMPLETED, completed.message.status)
      assertEquals("Cloud reply.", completed.message.content)
      assertEquals(1, fixture.adapter.requests.size)
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_ROUTE_STARTED })
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_ROUTE_COMPLETED })
    }

  @Test
  fun tryGenerate_providerErrorFallsBackAndRecordsError() =
    runBlocking {
      val fixture = cloudFixture()
      val error =
        CloudProviderError(
          type = CloudProviderErrorType.RATE_LIMIT,
          providerId = CloudProviderId.DEEPSEEK,
          statusCode = 429,
          retryable = true,
          message = "rate limited",
        )
      fixture.adapter.results += CloudGenerationResult(error = error)

      val outcome = fixture.coordinator.tryGenerate(fixture.request())

      assertTrue(outcome is CloudRoleplayInferenceOutcome.Fallback)
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_PROVIDER_ERROR })
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_ROUTE_FALLBACK })
    }

  @Test
  fun tryGenerate_cloudToolCallExecutesLocalToolThenContinuesCloud() =
    runBlocking {
      val fixture = cloudFixture()
      fixture.adapter.results +=
        CloudGenerationResult(
          toolCalls =
            listOf(
              CloudToolCall(
                id = "tool-1",
                name = "echoSignal",
                argumentsJson = """{"value":"north"}""",
              )
            )
        )
      fixture.adapter.results += CloudGenerationResult(text = "Tool-aware reply.")

      val outcome =
        fixture.coordinator.tryGenerate(
          fixture.request(
            tools =
              listOf(
                tool(EchoToolSet())
              )
          )
        )

      val completed = outcome as CloudRoleplayInferenceOutcome.Completed
      assertEquals("Tool-aware reply.", completed.message.content)
      assertEquals(2, fixture.adapter.requests.size)
      assertTrue(fixture.adapter.requests.first().tools.isNotEmpty())
      assertTrue(fixture.adapter.requests.last().tools.isEmpty())
      assertTrue(fixture.adapter.requests.last().messages.textContent().contains("Local tool results"))
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_LOCAL_TOOL_BRIDGE_USED })
    }

  @Test
  fun tryGenerate_audioContextBridgeAllowsCloudWithoutRawAudioInput() =
    runBlocking {
      val fixture = cloudFixture()
      fixture.adapter.results += CloudGenerationResult(text = "I heard the password.")
      val now = System.currentTimeMillis()
      val audioMessage =
        Message(
          id = "audio-1",
          sessionId = "session-1",
          seq = 1,
          side = MessageSide.USER,
          kind = MessageKind.AUDIO,
          status = MessageStatus.COMPLETED,
          content = "Voice note",
          metadataJson =
            encodeRoleplayMessageMediaPayload(
              RoleplayMessageMediaPayload(
                attachments =
                  listOf(
                    RoleplayMessageAttachment(
                      type = RoleplayMessageAttachmentType.AUDIO,
                      filePath = "/tmp/audio.pcm",
                      mimeType = "audio/raw",
                      contextText = "The user whispers the north gate password.",
                      sampleRate = 16000,
                    )
                  )
              )
            ),
          createdAt = now,
          updatedAt = now,
        )

      val outcome =
        fixture.coordinator.tryGenerate(
          fixture.request(
            userMessages = listOf(audioMessage),
            currentTurnMedia = CurrentTurnMedia(audioClips = listOf(byteArrayOf(1)), currentAudioCount = 1),
          )
        )

      val completed = outcome as CloudRoleplayInferenceOutcome.Completed
      assertEquals("I heard the password.", completed.message.content)
      assertEquals(1, fixture.adapter.requests.size)
      assertTrue(fixture.adapter.requests.single().messages.textContent().contains("Local media bridge"))
      assertTrue(fixture.adapter.requests.single().messages.textContent().contains("north gate password"))
      assertTrue(fixture.conversationRepository.events.any { it.eventType == SessionEventType.CLOUD_MEDIA_BRIDGE_USED })
    }

  @Test
  fun tryGenerate_rethrowsCancellationWithoutProviderFallback() =
    runBlocking {
      val fixture = cloudFixture()
      fixture.adapter.exception = CancellationException("turn cancelled")

      var cancelled = false
      try {
        fixture.coordinator.tryGenerate(fixture.request())
      } catch (exception: CancellationException) {
        cancelled = true
      }

      assertTrue(cancelled)
      assertTrue(fixture.conversationRepository.events.none { it.eventType == SessionEventType.CLOUD_PROVIDER_ERROR })
      assertTrue(fixture.conversationRepository.events.none { it.eventType == SessionEventType.CLOUD_ROUTE_FALLBACK })
    }

  private fun cloudFixture(
    config: CloudModelConfig =
      CloudModelConfig(
        enabled = true,
        providerId = CloudProviderId.DEEPSEEK,
        modelName = "deepseek-v4-flash",
      ),
  ): CloudFixture {
    val dataStoreRepository = FakeDataStoreRepository()
    val credentialStore = MutableCloudCredentialStore()
    val configRepository = CloudModelConfigRepository(dataStoreRepository, credentialStore)
    configRepository.saveConfig(config)
    configRepository.saveApiKey(config.providerId, "test-key")
    val adapter = FakeCloudAdapter()
    val conversationRepository = CloudConversationRepository()
    val coordinator =
      CloudRoleplayInferenceCoordinator(
        configRepository = configRepository,
        adapterResolver = FixedCloudAdapterResolver(adapter),
        networkStatusProvider =
          object : selfgemma.talk.domain.cloudllm.CloudNetworkStatusProvider {
            override fun isNetworkAvailable(): Boolean = true
          },
        providerHealthTracker = CloudProviderHealthTracker(),
        conversationRepository = conversationRepository,
        eventLogger = CloudRoleplayEventLogger(conversationRepository),
      )
    return CloudFixture(coordinator, adapter, conversationRepository)
  }

  private data class CloudFixture(
    val coordinator: CloudRoleplayInferenceCoordinator,
    val adapter: FakeCloudAdapter,
    val conversationRepository: CloudConversationRepository,
  ) {
    fun request(
      tools: List<com.google.ai.edge.litertlm.ToolProvider> = emptyList(),
      userMessages: List<Message>? = null,
      currentTurnMedia: CurrentTurnMedia = CurrentTurnMedia(),
    ): CloudRoleplayInferenceRequest {
      val now = System.currentTimeMillis()
      val userMessage =
        Message(
          id = "user-1",
          sessionId = "session-1",
          seq = 1,
          side = MessageSide.USER,
          status = MessageStatus.COMPLETED,
          content = "Move north.",
          createdAt = now,
          updatedAt = now,
        )
      return CloudRoleplayInferenceRequest(
        sessionId = "session-1",
        assistantSeed =
          Message(
            id = "assistant-2",
            sessionId = "session-1",
            seq = 2,
            side = MessageSide.ASSISTANT,
            status = MessageStatus.STREAMING,
            accepted = false,
            isCanonical = false,
            createdAt = now,
            updatedAt = now,
          ),
        promptAssembly = PromptAssemblyResult(prompt = "Stay in character."),
        input = userMessage.content,
        userMessages = userMessages ?: listOf(userMessage),
        currentTurnMedia = currentTurnMedia,
        turnToolContext =
          RoleplayPreparedToolContext(
            tools = tools,
            collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2"),
          ),
        enableStreamingOutput = false,
        isStopRequested = { false },
      )
    }
  }

  private class EchoToolSet : ToolSet {
    @Tool(description = "Echoes a signal.")
    fun echoSignal(
      @ToolParam(description = "Signal value.") value: String
    ): Map<String, String> = mapOf("echo" to value)
  }

  private class FakeCloudAdapter : CloudLlmProviderAdapter {
    override val providerId: CloudProviderId = CloudProviderId.DEEPSEEK
    val requests = mutableListOf<CloudGenerationRequest>()
    val results = ArrayDeque<CloudGenerationResult>()
    var exception: Exception? = null

    override fun defaultCapability(config: CloudModelConfig): CloudModelCapability {
      return CloudModelCapability(supportsToolCalling = true)
    }

    override fun mapError(statusCode: Int, body: String?): CloudProviderError {
      return CloudProviderError(CloudProviderErrorType.UNKNOWN, providerId, statusCode)
    }

    override suspend fun testConnection(config: CloudModelConfig, apiKey: String): CloudConnectionTestResult {
      return CloudConnectionTestResult(providerId, success = true, modelName = config.modelName)
    }

    override suspend fun streamGenerate(
      request: CloudGenerationRequest,
      onEvent: suspend (CloudGenerationEvent) -> Unit,
    ): CloudGenerationResult {
      exception?.let { throw it }
      requests += request
      val result = results.removeFirst()
      if (result.text.isNotBlank()) {
        onEvent(CloudGenerationEvent.TextDelta(result.text))
      }
      result.error?.let { onEvent(CloudGenerationEvent.Failed(it)) }
      onEvent(CloudGenerationEvent.Completed)
      return result
    }
  }

  private class FixedCloudAdapterResolver(private val adapter: CloudLlmProviderAdapter) :
    CloudProviderAdapterResolver {
    override fun adapterFor(providerId: CloudProviderId): CloudLlmProviderAdapter? = adapter
  }

  private class MutableCloudCredentialStore : CloudCredentialStore {
    private val secrets = mutableMapOf<String, String>()

    override fun saveSecret(secretName: String, value: String) {
      secrets[secretName] = value
    }

    override fun readSecret(secretName: String): String? = secrets[secretName]

    override fun deleteSecret(secretName: String) {
      secrets.remove(secretName)
    }
  }

  private class CloudConversationRepository : ConversationRepository {
    val events = mutableListOf<SessionEvent>()
    val messages = mutableListOf<Message>()

    override fun observeSessions(): Flow<List<Session>> = flowOf(emptyList())
    override fun observeMessages(sessionId: String): Flow<List<Message>> = flowOf(messages)
    override suspend fun listMessages(sessionId: String): List<Message> = messages
    override suspend fun listCanonicalMessages(sessionId: String): List<Message> = messages
    override suspend fun getMessage(messageId: String): Message? = messages.firstOrNull { it.id == messageId }
    override suspend fun getSession(sessionId: String): Session? = null
    override suspend fun createSession(roleId: String, modelId: String, userProfile: StUserProfile?): Session {
      error("Not used")
    }
    override suspend fun updateSession(session: Session) = Unit
    override suspend fun archiveSession(sessionId: String) = Unit
    override suspend fun deleteSession(sessionId: String) = Unit
    override suspend fun appendMessage(message: Message) {
      messages += message
    }
    override suspend fun updateMessage(message: Message) {
      messages.removeAll { it.id == message.id }
      messages += message
    }
    override suspend fun acceptAssistantMessage(messageId: String, acceptedAt: Long): Message? = null
    override suspend fun rollbackToMessage(
      sessionId: String,
      targetMessageId: String,
      rollbackBranchId: String,
      updatedAt: Long,
    ): Int = 0
    override suspend fun rollbackFromMessageInclusive(
      sessionId: String,
      targetMessageId: String,
      rollbackBranchId: String,
      updatedAt: Long,
    ): Int = 0
    override suspend fun replaceMessages(sessionId: String, messages: List<Message>) = Unit
    override suspend fun nextMessageSeq(sessionId: String): Int = 1
    override suspend fun getSummary(sessionId: String): SessionSummary? = null
    override suspend fun upsertSummary(summary: SessionSummary) = Unit
    override suspend fun deleteSummary(sessionId: String) = Unit
    override suspend fun listEvents(sessionId: String): List<SessionEvent> = events
    override suspend fun appendEvent(event: SessionEvent) {
      events += event
    }
  }
}

private fun List<CloudMessage>.textContent(): String {
  return flatMap(CloudMessage::parts)
    .filterIsInstance<CloudContentPart.Text>()
    .joinToString(separator = "\n", transform = CloudContentPart.Text::text)
}
