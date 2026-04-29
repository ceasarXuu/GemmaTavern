package selfgemma.talk.domain.roleplay.usecase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.edge.litertlm.Contents
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import selfgemma.talk.data.Model
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.pcm16MonoToWav
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.runtime.LlmModelHelper

internal fun sanitizeAttachmentContextText(value: String): String? {
  val sanitized =
    value
      .replace(Regex("\\s+"), " ")
      .trim()
      .trim('"')
      .take(IMAGE_CONTEXT_TEXT_MAX_CHARS)
  return sanitized.ifBlank { null }
}

internal fun mergeUserInputWithOverflowText(input: String, overflowText: String): String {
  val trimmedInput = input.trim()
  val trimmedOverflowText = overflowText.trim()
  if (trimmedOverflowText.isBlank()) {
    return trimmedInput
  }
  return buildString {
    if (trimmedInput.isNotBlank()) {
      append(trimmedInput)
      append("\n\n")
    }
    append(trimmedOverflowText)
  }.trim()
}

internal fun loadAudioClip(
  attachment: RoleplayMessageAttachment,
  logContext: String,
): ByteArray? {
  val sampleRate = attachment.sampleRate
  val file = File(attachment.filePath)
  if (sampleRate == null || !file.exists()) {
    srmWarnLog(
      "failed to load roleplay audio attachment $logContext sampleRate=$sampleRate path=${attachment.filePath}",
    )
    return null
  }
  return pcm16MonoToWav(file.readBytes(), sampleRate)
}

internal fun loadMediaFromAttachments(attachments: List<RoleplayMessageAttachment>): CurrentTurnMedia {
  val images = mutableListOf<Bitmap>()
  val audioClips = mutableListOf<ByteArray>()

  attachments.forEach { attachment ->
    when (attachment.type) {
      RoleplayMessageAttachmentType.IMAGE -> {
        val bitmap = BitmapFactory.decodeFile(attachment.filePath)
        if (bitmap != null) {
          images += bitmap
        } else {
          srmWarnLog(
            "failed to decode roleplay image attachment path=${attachment.filePath}",
          )
        }
      }
      RoleplayMessageAttachmentType.AUDIO -> {
        loadAudioClip(attachment = attachment, logContext = "for multimodal context")?.let(audioClips::add)
      }
    }
  }

  return CurrentTurnMedia(images = images, audioClips = audioClips)
}

internal fun loadConversationMedia(
  dialogueWindow: List<Message>,
  currentMessages: List<Message>,
): CurrentTurnMedia {
  val selectedAttachments =
    selectConversationMediaAttachments(
      dialogueWindow = dialogueWindow,
      currentMessages = currentMessages,
    )
  val historicalMedia =
    loadMediaFromAttachments(
      selectedAttachments.historicalImages + selectedAttachments.historicalAudioClips
    )
  val currentMedia =
    loadMediaFromAttachments(
      selectedAttachments.currentImages + selectedAttachments.currentAudioClips
    )
  val overflowText =
    buildOverflowMediaText(
      currentMessages = currentMessages,
      selectedAttachments = selectedAttachments,
    ).currentTurnText
  val mergedMedia =
    CurrentTurnMedia(
      images = historicalMedia.images + currentMedia.images,
      audioClips = historicalMedia.audioClips + currentMedia.audioClips,
      historicalImageCount = historicalMedia.images.size,
      currentImageCount = currentMedia.images.size,
      historicalAudioCount = historicalMedia.audioClips.size,
      currentAudioCount = currentMedia.audioClips.size,
      overflowText = overflowText,
    )
  val rawCurrentImageAttachmentCount =
    countRoleplayAttachments(currentMessages, RoleplayMessageAttachmentType.IMAGE)
  val rawCurrentAudioAttachmentCount =
    countRoleplayAttachments(currentMessages, RoleplayMessageAttachmentType.AUDIO)
  val rawHistoricalImageAttachmentCount =
    countRoleplayAttachments(dialogueWindow, RoleplayMessageAttachmentType.IMAGE)
  val rawHistoricalAudioAttachmentCount =
    countRoleplayAttachments(dialogueWindow, RoleplayMessageAttachmentType.AUDIO)
  if (
    rawCurrentImageAttachmentCount > selectedAttachments.currentImages.size ||
      rawCurrentAudioAttachmentCount > selectedAttachments.currentAudioClips.size ||
      rawHistoricalImageAttachmentCount > selectedAttachments.historicalImages.size ||
      rawHistoricalAudioAttachmentCount > selectedAttachments.historicalAudioClips.size
  ) {
    srmWarnLog(
      "capped roleplay multimodal context rawCurrentImages=$rawCurrentImageAttachmentCount rawCurrentAudioClips=$rawCurrentAudioAttachmentCount rawHistoricalImages=$rawHistoricalImageAttachmentCount rawHistoricalAudioClips=$rawHistoricalAudioAttachmentCount selectedImages=${mergedMedia.images.size} selectedAudioClips=${mergedMedia.audioClips.size} maxImages=$MAX_ROLEPLAY_CONTEXT_IMAGE_ATTACHMENTS maxAudioClips=$MAX_ROLEPLAY_CONTEXT_AUDIO_ATTACHMENTS",
    )
  }
  srmDebugLog(
    "loaded multimodal context dialogueWindowMessages=${dialogueWindow.size} currentMessages=${currentMessages.size} images=${mergedMedia.images.size} audioClips=${mergedMedia.audioClips.size} historicalImages=${mergedMedia.historicalImageCount} currentImages=${mergedMedia.currentImageCount} historicalAudioClips=${mergedMedia.historicalAudioCount} currentAudioClips=${mergedMedia.currentAudioCount}",
  )
  return mergedMedia
}

internal fun requiresLocalMediaContext(userMessages: List<Message>): Boolean {
  return userMessages.any { message ->
    if (message.kind != MessageKind.IMAGE && message.kind != MessageKind.AUDIO) {
      return@any false
    }
    message
      .roleplayMessageMediaPayload()
      ?.attachments
      .orEmpty()
      .any { attachment ->
        (attachment.type == RoleplayMessageAttachmentType.IMAGE ||
          attachment.type == RoleplayMessageAttachmentType.AUDIO) &&
          attachment.contextText.isNullOrBlank()
      }
  }
}

internal suspend fun describeImageAttachmentContext(
  runtimeHelper: LlmModelHelper,
  model: Model,
  bitmap: Bitmap,
  sessionId: String,
  messageId: String,
  attachmentIndex: Int,
  isStopRequested: () -> Boolean,
): String? {
  if (isStopRequested()) {
    return null
  }

  return try {
    runtimeHelper.resetConversation(
      model = model,
      supportImage = true,
      supportAudio = false,
      systemInstruction = Contents.of(IMAGE_CONTEXT_SYSTEM_PROMPT),
    )
    suspendCancellableCoroutine { continuation ->
      val partialContent = StringBuilder()
      val completed = AtomicBoolean(false)

      fun finish(value: String?) {
        if (!completed.compareAndSet(false, true)) {
          return
        }
        if (continuation.isActive) {
          continuation.resume(value)
        }
      }

      try {
        runtimeHelper.runInference(
          model = model,
          input = IMAGE_CONTEXT_USER_PROMPT,
          resultListener = { partialResult, done, _ ->
            if (!partialResult.startsWith("<ctrl") && partialResult.isNotEmpty()) {
              partialContent.append(partialResult)
            }
            if (done) {
              finish(sanitizeAttachmentContextText(partialContent.toString()))
            }
          },
          cleanUpListener = {},
          onError = { message ->
            srmWarnLog(
              "image context generation failed sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex message=$message",
            )
            finish(null)
          },
          images = listOf(bitmap),
        )
      } catch (exception: Exception) {
        srmWarnLog(
          "image context generation threw sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
          exception,
        )
        finish(null)
      }

      continuation.invokeOnCancellation {
        if (!completed.get()) {
          runtimeHelper.stopResponse(model)
        }
      }
    }
  } catch (exception: Exception) {
    srmWarnLog(
      "failed to reset conversation for image context generation sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
      exception,
    )
    null
  }
}

internal suspend fun describeAudioAttachmentContext(
  runtimeHelper: LlmModelHelper,
  model: Model,
  audioClip: ByteArray,
  sessionId: String,
  messageId: String,
  attachmentIndex: Int,
  isStopRequested: () -> Boolean,
): String? {
  if (isStopRequested()) {
    return null
  }

  return try {
    runtimeHelper.resetConversation(
      model = model,
      supportImage = false,
      supportAudio = true,
      systemInstruction = Contents.of(AUDIO_CONTEXT_SYSTEM_PROMPT),
    )
    suspendCancellableCoroutine { continuation ->
      val partialContent = StringBuilder()
      val completed = AtomicBoolean(false)

      fun finish(value: String?) {
        if (!completed.compareAndSet(false, true)) {
          return
        }
        if (continuation.isActive) {
          continuation.resume(value)
        }
      }

      try {
        runtimeHelper.runInference(
          model = model,
          input = AUDIO_CONTEXT_USER_PROMPT,
          resultListener = { partialResult, done, _ ->
            if (!partialResult.startsWith("<ctrl") && partialResult.isNotEmpty()) {
              partialContent.append(partialResult)
            }
            if (done) {
              finish(sanitizeAttachmentContextText(partialContent.toString()))
            }
          },
          cleanUpListener = {},
          onError = { message ->
            srmWarnLog(
              "audio context generation failed sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex message=$message",
            )
            finish(null)
          },
          audioClips = listOf(audioClip),
        )
      } catch (exception: Exception) {
        srmWarnLog(
          "audio context generation threw sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
          exception,
        )
        finish(null)
      }

      continuation.invokeOnCancellation {
        if (!completed.get()) {
          runtimeHelper.stopResponse(model)
        }
      }
    }
  } catch (exception: Exception) {
    srmWarnLog(
      "failed to reset conversation for audio context generation sessionId=$sessionId messageId=$messageId attachmentIndex=$attachmentIndex",
      exception,
    )
    null
  }
}

internal suspend fun RoleplayMessageAttachment.withImageContextText(
  runtimeHelper: LlmModelHelper,
  model: Model,
  sessionId: String,
  messageId: String,
  attachmentIndex: Int,
  isStopRequested: () -> Boolean,
): RoleplayMessageAttachment {
  val bitmap = BitmapFactory.decodeFile(filePath)
  if (bitmap == null) {
    srmWarnLog(
      "failed to decode roleplay image attachment for context text sessionId=$sessionId messageId=$messageId path=$filePath",
    )
    return this
  }
  val generatedContextText =
    describeImageAttachmentContext(
      runtimeHelper = runtimeHelper,
      model = model,
      bitmap = bitmap,
      sessionId = sessionId,
      messageId = messageId,
      attachmentIndex = attachmentIndex,
      isStopRequested = isStopRequested,
    )
  return if (generatedContextText.isNullOrBlank()) this else copy(contextText = generatedContextText)
}

internal suspend fun RoleplayMessageAttachment.withAudioContextText(
  runtimeHelper: LlmModelHelper,
  model: Model,
  sessionId: String,
  messageId: String,
  attachmentIndex: Int,
  isStopRequested: () -> Boolean,
): RoleplayMessageAttachment {
  val audioClip =
    loadAudioClip(
      attachment = this,
      logContext = "for context text sessionId=$sessionId messageId=$messageId",
    ) ?: return this
  val generatedContextText =
    describeAudioAttachmentContext(
      runtimeHelper = runtimeHelper,
      model = model,
      audioClip = audioClip,
      sessionId = sessionId,
      messageId = messageId,
      attachmentIndex = attachmentIndex,
      isStopRequested = isStopRequested,
    )
  return if (generatedContextText.isNullOrBlank()) this else copy(contextText = generatedContextText)
}

internal suspend fun ensureCurrentMediaAttachmentContextTexts(
  conversationRepository: ConversationRepository,
  runtimeHelper: LlmModelHelper,
  userMessages: List<Message>,
  model: Model,
  sessionId: String,
  isStopRequested: () -> Boolean,
): List<Message> {
  var updatedAny = false
  val updatedMessages =
    userMessages.map { message ->
      if ((message.kind != MessageKind.IMAGE && message.kind != MessageKind.AUDIO) || isStopRequested()) {
        return@map message
      }
      val payload = message.roleplayMessageMediaPayload() ?: return@map message
      if (
        payload.attachments.none { attachment ->
          (attachment.type == RoleplayMessageAttachmentType.IMAGE ||
            attachment.type == RoleplayMessageAttachmentType.AUDIO) &&
            attachment.contextText.isNullOrBlank()
        }
      ) {
        return@map message
      }

      val updatedAttachments =
        payload.attachments.mapIndexed { index, attachment ->
          if (
            attachment.contextText?.trim()?.isNotEmpty() == true ||
              (attachment.type != RoleplayMessageAttachmentType.IMAGE &&
                attachment.type != RoleplayMessageAttachmentType.AUDIO)
          ) {
            attachment
          } else when (attachment.type) {
            RoleplayMessageAttachmentType.IMAGE ->
              attachment.withImageContextText(
                runtimeHelper = runtimeHelper,
                model = model,
                sessionId = sessionId,
                messageId = message.id,
                attachmentIndex = index,
                isStopRequested = isStopRequested,
              )
            RoleplayMessageAttachmentType.AUDIO ->
              attachment.withAudioContextText(
                runtimeHelper = runtimeHelper,
                model = model,
                sessionId = sessionId,
                messageId = message.id,
                attachmentIndex = index,
                isStopRequested = isStopRequested,
              )
          }
        }

      if (updatedAttachments == payload.attachments) {
        message
      } else {
        updatedAny = true
        message.copy(
          metadataJson =
            encodeRoleplayMessageMediaPayload(
              payload.copy(attachments = updatedAttachments)
            ),
          updatedAt = System.currentTimeMillis(),
        )
      }
    }

  if (!updatedAny) {
    return userMessages
  }

  updatedMessages
    .filter { updated -> userMessages.any { original -> original.id == updated.id && original != updated } }
    .forEach { updatedMessage ->
      conversationRepository.updateMessage(updatedMessage)
    }
  srmDebugLog(
    "persisted media context text sessionId=$sessionId updatedMessages=${updatedMessages.count { updated -> userMessages.any { original -> original.id == updated.id && original != updated } }}",
  )
  return updatedMessages
}
