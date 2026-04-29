package selfgemma.talk.feature.roleplay.chat

import android.graphics.Bitmap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.usecase.StagedRoleplayTurn
import selfgemma.talk.ui.common.chat.ChatMessage
import selfgemma.talk.ui.common.chat.ChatMessageAudioClip
import selfgemma.talk.ui.common.chat.ChatMessageImage
import selfgemma.talk.ui.common.chat.ChatMessageText

private const val DEFAULT_BRANCH_ID = "main"

internal fun RoleplayChatViewModel.stagePendingUserMessages(messages: List<ChatMessage>): List<QueuedUserMessage> {
  val now = System.currentTimeMillis()
  var nextSeq = (uiState.value.messages.maxOfOrNull { it.seq } ?: 0) + 1
  val queuedMessages = mutableListOf<QueuedUserMessage>()

  messages.forEach { chatMessage ->
    when (chatMessage) {
      is ChatMessageText -> {
        val input = chatMessage.content.trim()
        if (input.isBlank()) {
          return@forEach
        }
        val userMessage =
          Message(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            seq = nextSeq++,
            branchId = DEFAULT_BRANCH_ID,
            side = MessageSide.USER,
            kind = MessageKind.TEXT,
            status = MessageStatus.COMPLETED,
            accepted = true,
            isCanonical = true,
            content = input,
            createdAt = now,
            updatedAt = now,
          )
        queuedMessages += QueuedUserMessage(message = userMessage)
        logDebug("queued text draft sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id}")
      }
      is ChatMessageImage -> {
        if (chatMessage.bitmaps.isEmpty()) {
          return@forEach
        }
        val messageId = UUID.randomUUID().toString()
        val payload = persistImagePayloadFor(messageId = messageId, bitmaps = chatMessage.bitmaps)
        val userMessage =
          Message(
            id = messageId,
            sessionId = sessionId,
            seq = nextSeq++,
            branchId = DEFAULT_BRANCH_ID,
            side = MessageSide.USER,
            kind = MessageKind.IMAGE,
            status = MessageStatus.COMPLETED,
            accepted = true,
            isCanonical = true,
            content = "Shared ${payload.attachments.size} image(s).",
            metadataJson = encodeRoleplayMessageMediaPayload(payload),
            createdAt = now,
            updatedAt = now,
          )
        queuedMessages += QueuedUserMessage(message = userMessage)
        logDebug(
          "queued image payload sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id} imageCount=${payload.attachments.size}",
        )
      }
      is ChatMessageAudioClip -> {
        val messageId = UUID.randomUUID().toString()
        val payload =
          persistAudioPayloadFor(
            messageId = messageId,
            audioData = chatMessage.audioData,
            sampleRate = chatMessage.sampleRate,
          )
        val userMessage =
          Message(
            id = messageId,
            sessionId = sessionId,
            seq = nextSeq++,
            branchId = DEFAULT_BRANCH_ID,
            side = MessageSide.USER,
            kind = MessageKind.AUDIO,
            status = MessageStatus.COMPLETED,
            accepted = true,
            isCanonical = true,
            content = "Shared an audio clip.",
            metadataJson = encodeRoleplayMessageMediaPayload(payload),
            createdAt = now,
            updatedAt = now,
          )
        queuedMessages += QueuedUserMessage(message = userMessage)
        logDebug(
          "queued audio payload sessionId=$sessionId seq=${userMessage.seq} messageId=${userMessage.id} sampleRate=${chatMessage.sampleRate}",
        )
      }
      else -> Unit
    }
  }

  return queuedMessages
}

internal fun RoleplayChatViewModel.stageDispatchTurn(userMessages: List<Message>, model: Model): StagedRoleplayTurn {
  val now = System.currentTimeMillis()
  val parentMessageId = userMessages.lastOrNull()?.id
  val assistantMessage =
    Message(
      id = UUID.randomUUID().toString(),
      sessionId = sessionId,
      seq = (userMessages.maxOfOrNull { it.seq } ?: 0) + 1,
      branchId = userMessages.lastOrNull()?.branchId ?: DEFAULT_BRANCH_ID,
      side = MessageSide.ASSISTANT,
      status = MessageStatus.STREAMING,
      accepted = false,
      isCanonical = false,
      content = "",
      accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = ""),
      parentMessageId = parentMessageId,
      regenerateGroupId = parentMessageId,
      createdAt = now,
      updatedAt = now,
    )
  logDebug(
    "dispatch turn staged sessionId=$sessionId userMessageCount=${userMessages.size} assistantMessageId=${assistantMessage.id}",
  )
  return StagedRoleplayTurn(
    userMessages = userMessages,
    assistantMessage = assistantMessage,
    combinedUserInput =
      userMessages
        .filter { it.kind == MessageKind.TEXT }
        .joinToString(separator = "\n\n") { it.content.trim() },
  )
}

internal fun RoleplayChatViewModel.persistImagePayloadFor(
  messageId: String,
  bitmaps: List<Bitmap>,
): RoleplayMessageMediaPayload =
  persistImagePayload(appContext = appContext, sessionId = sessionId, messageId = messageId, bitmaps = bitmaps)

internal fun RoleplayChatViewModel.persistAudioPayloadFor(
  messageId: String,
  audioData: ByteArray,
  sampleRate: Int,
): RoleplayMessageMediaPayload =
  persistAudioPayload(
    appContext = appContext,
    sessionId = sessionId,
    messageId = messageId,
    audioData = audioData,
    sampleRate = sampleRate,
  )

internal fun RoleplayChatViewModel.resolveAttachmentFileFor(messageId: String, fileName: String): File =
  resolveAttachmentFile(appContext = appContext, sessionId = sessionId, messageId = messageId, fileName = fileName)

internal fun RoleplayChatViewModel.mergeMessages(messages: List<Message>, queuedMessages: List<QueuedUserMessage>): List<Message> {
  val visiblePersistedMessages = messages.filter(::shouldDisplayMessage)
  val persistedIds = visiblePersistedMessages.mapTo(mutableSetOf()) { it.id }
  return (visiblePersistedMessages + queuedMessages.map { it.message }.filterNot { it.id in persistedIds })
    .sortedWith(compareBy<Message>({ it.seq }, { it.createdAt }, { it.id }))
}

internal fun shouldDisplayMessage(message: Message): Boolean {
  if (message.isCanonical) {
    return true
  }

  return when (message.status) {
    MessageStatus.PENDING,
    MessageStatus.STREAMING,
    MessageStatus.FAILED,
    -> true
    MessageStatus.INTERRUPTED -> message.content.isNotBlank()
    MessageStatus.COMPLETED -> false
  }
}

internal suspend fun RoleplayChatViewModel.retractActiveAssistantBubble() {
  val assistantMessageId = activeAssistantMessageId ?: return
  val message =
    conversationRepository.observeMessages(sessionId).first().lastOrNull { it.id == assistantMessageId }
      ?: return
  if (message.side != MessageSide.ASSISTANT) {
    return
  }
  conversationRepository.updateMessage(
    message.copy(
      content = "",
      status = MessageStatus.INTERRUPTED,
      errorMessage = null,
      updatedAt = System.currentTimeMillis(),
    )
  )
  logDebug("retracted streaming assistant bubble sessionId=$sessionId messageId=$assistantMessageId")
}
