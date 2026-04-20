package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload

internal data class SelectedRoleplayMediaAttachments(
  val images: List<RoleplayMessageAttachment> = emptyList(),
  val audioClips: List<RoleplayMessageAttachment> = emptyList(),
)

internal data class SelectedRoleplayConversationMediaAttachments(
  val historicalImages: List<RoleplayMessageAttachment> = emptyList(),
  val currentImages: List<RoleplayMessageAttachment> = emptyList(),
  val historicalAudioClips: List<RoleplayMessageAttachment> = emptyList(),
  val currentAudioClips: List<RoleplayMessageAttachment> = emptyList(),
) {
  val images: List<RoleplayMessageAttachment>
    get() = historicalImages + currentImages

  val audioClips: List<RoleplayMessageAttachment>
    get() = historicalAudioClips + currentAudioClips
}

internal data class RoleplayOverflowMediaText(
  val currentTurnText: String = "",
)

internal const val MAX_ROLEPLAY_CONTEXT_IMAGE_ATTACHMENTS = 1
internal const val MAX_ROLEPLAY_CONTEXT_AUDIO_ATTACHMENTS = 1

internal fun selectPromptWindowMessages(
  messages: List<Message>,
  tokenBudget: Int,
  tokenEstimator: TokenEstimator,
): List<Message> {
  val filtered =
    messages.filter { message ->
      (message.kind == MessageKind.TEXT ||
        message.kind == MessageKind.IMAGE ||
        message.kind == MessageKind.AUDIO) &&
        message.side != MessageSide.SYSTEM &&
        message.status != MessageStatus.FAILED &&
        message.toPromptRenderableContent().isNotBlank()
    }
  if (filtered.isEmpty()) {
    return emptyList()
  }

  val selected = mutableListOf<Message>()
  var tokenCount = 0
  for (message in filtered.asReversed()) {
    val renderedContent = message.toPromptRenderableContent()
    val nextTokenCount =
      tokenCount + tokenEstimator.estimate(renderedContent) + tokenEstimator.estimate(message.side.name)
    if (selected.isNotEmpty() && nextTokenCount > tokenBudget) {
      break
    }
    selected += message
    tokenCount = nextTokenCount
  }
  return selected.asReversed()
}

internal fun countRoleplayAttachments(
  messages: List<Message>,
  type: RoleplayMessageAttachmentType,
): Int {
  return messages.sumOf { message ->
    message.roleplayMessageMediaPayload()?.attachments.orEmpty().count { attachment -> attachment.type == type }
  }
}

internal fun selectHistoricalMediaAttachments(
  messages: List<Message>,
  maxImageAttachments: Int,
  maxAudioAttachments: Int,
): SelectedRoleplayMediaAttachments {
  if (maxImageAttachments <= 0 && maxAudioAttachments <= 0) {
    return SelectedRoleplayMediaAttachments()
  }

  var remainingImages = maxImageAttachments.coerceAtLeast(0)
  var remainingAudioClips = maxAudioAttachments.coerceAtLeast(0)
  val imageChunksNewestFirst = mutableListOf<List<RoleplayMessageAttachment>>()
  val audioChunksNewestFirst = mutableListOf<List<RoleplayMessageAttachment>>()

  for (message in messages.asReversed()) {
    if (remainingImages <= 0 && remainingAudioClips <= 0) {
      break
    }
    val attachments = message.roleplayMessageMediaPayload()?.attachments.orEmpty()
    if (attachments.isEmpty()) {
      continue
    }

    if (remainingImages > 0) {
      val selectedImages =
        attachments
          .filter { attachment -> attachment.type == RoleplayMessageAttachmentType.IMAGE }
          .take(remainingImages)
      if (selectedImages.isNotEmpty()) {
        imageChunksNewestFirst += selectedImages
        remainingImages -= selectedImages.size
      }
    }

    if (remainingAudioClips > 0) {
      val selectedAudio =
        attachments
          .filter { attachment -> attachment.type == RoleplayMessageAttachmentType.AUDIO }
          .take(remainingAudioClips)
      if (selectedAudio.isNotEmpty()) {
        audioChunksNewestFirst += selectedAudio
        remainingAudioClips -= selectedAudio.size
      }
    }
  }

  return SelectedRoleplayMediaAttachments(
    images = imageChunksNewestFirst.asReversed().flatten(),
    audioClips = audioChunksNewestFirst.asReversed().flatten(),
  )
}

internal fun selectConversationMediaAttachments(
  dialogueWindow: List<Message>,
  currentMessages: List<Message>,
  maxImageAttachments: Int = MAX_ROLEPLAY_CONTEXT_IMAGE_ATTACHMENTS,
  maxAudioAttachments: Int = MAX_ROLEPLAY_CONTEXT_AUDIO_ATTACHMENTS,
): SelectedRoleplayConversationMediaAttachments {
  val boundedImageLimit = maxImageAttachments.coerceAtLeast(0)
  val boundedAudioLimit = maxAudioAttachments.coerceAtLeast(0)
  val currentAttachments =
    selectHistoricalMediaAttachments(
      messages = currentMessages,
      maxImageAttachments = boundedImageLimit,
      maxAudioAttachments = boundedAudioLimit,
    )
  val historicalAttachments =
    selectHistoricalMediaAttachments(
      messages = dialogueWindow,
      maxImageAttachments = (boundedImageLimit - currentAttachments.images.size).coerceAtLeast(0),
      maxAudioAttachments = (boundedAudioLimit - currentAttachments.audioClips.size).coerceAtLeast(0),
    )

  return SelectedRoleplayConversationMediaAttachments(
    historicalImages = historicalAttachments.images,
    currentImages = currentAttachments.images,
    historicalAudioClips = historicalAttachments.audioClips,
    currentAudioClips = currentAttachments.audioClips,
  )
}

internal fun buildOverflowMediaText(
  currentMessages: List<Message>,
  selectedAttachments: SelectedRoleplayConversationMediaAttachments,
): RoleplayOverflowMediaText {
  val selectedImagePaths = selectedAttachments.currentImages.mapTo(mutableSetOf()) { it.filePath }
  val selectedAudioPaths = selectedAttachments.currentAudioClips.mapTo(mutableSetOf()) { it.filePath }
  val overflowLines =
    currentMessages.flatMap { message ->
      val attachments = message.roleplayMessageMediaPayload()?.attachments.orEmpty()
      attachments.mapNotNull { attachment ->
        when (attachment.type) {
          RoleplayMessageAttachmentType.IMAGE ->
            attachment.contextText
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.takeIf { attachment.filePath !in selectedImagePaths }
              ?.let { "Additional image context: $it" }
          RoleplayMessageAttachmentType.AUDIO ->
            attachment.contextText
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.takeIf { attachment.filePath !in selectedAudioPaths }
              ?.let { "Additional audio context: $it" }
        }
      }
    }

  return RoleplayOverflowMediaText(
    currentTurnText = overflowLines.joinToString(separator = "\n").trim()
  )
}

internal fun Message.toPromptRenderableContent(): String {
  val attachments = roleplayMessageMediaPayload()?.attachments.orEmpty()
  return when (kind) {
    MessageKind.IMAGE -> {
      val imageCount = attachments.count { attachment -> attachment.type == RoleplayMessageAttachmentType.IMAGE }
      val imageContextText =
        attachments
          .filter { attachment -> attachment.type == RoleplayMessageAttachmentType.IMAGE }
          .mapNotNull { attachment -> attachment.contextText?.trim()?.takeIf(String::isNotBlank) }
          .distinct()
          .joinToString(separator = " ")
      val baseContent =
        content.trim().ifBlank {
          if (imageCount > 0) {
            "Shared $imageCount image(s)."
          } else {
            "Shared image attachment(s)."
          }
        }
      buildString {
        append(baseContent)
        if (imageContextText.isNotBlank()) {
          append(" Image understanding: ")
          append(imageContextText)
        }
        append(" [Image attachment remains available in multimodal context.]")
      }
    }
    MessageKind.AUDIO -> {
      val audioCount = attachments.count { attachment -> attachment.type == RoleplayMessageAttachmentType.AUDIO }
      val audioContextText =
        attachments
          .filter { attachment -> attachment.type == RoleplayMessageAttachmentType.AUDIO }
          .mapNotNull { attachment -> attachment.contextText?.trim()?.takeIf(String::isNotBlank) }
          .distinct()
          .joinToString(separator = " ")
      val baseContent =
        content.trim().ifBlank {
          if (audioCount == 1) {
            "Shared an audio clip."
          } else if (audioCount > 1) {
            "Shared $audioCount audio clip(s)."
          } else {
            "Shared audio clip(s)."
          }
        }
      buildString {
        append(baseContent)
        if (audioContextText.isNotBlank()) {
          append(" Audio understanding: ")
          append(audioContextText)
        }
        append(" [Audio attachment remains available in multimodal context.]")
      }
    }
    else -> content.trim()
  }
}
