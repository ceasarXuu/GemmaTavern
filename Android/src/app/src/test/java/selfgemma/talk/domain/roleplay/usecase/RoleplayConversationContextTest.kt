package selfgemma.talk.domain.roleplay.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.RoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.encodeRoleplayMessageMediaPayload

class RoleplayConversationContextTest {
  @Test
  fun selectHistoricalMediaAttachments_prefersNewestAttachmentsWithinRemainingSlots() {
    val now = System.currentTimeMillis()
    val messages =
      listOf(
        message(
          id = "image-old",
          seq = 1,
          kind = MessageKind.IMAGE,
          content = "Shared 1 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/old-image.png",
              )
            ),
          now = now,
        ),
        message(
          id = "audio-old",
          seq = 2,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/old-audio.wav",
                sampleRate = 16000,
              )
            ),
          now = now,
        ),
        message(
          id = "image-new",
          seq = 3,
          kind = MessageKind.IMAGE,
          content = "Shared 2 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/new-image-1.png",
              ),
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/new-image-2.png",
              ),
            ),
          now = now,
        ),
        message(
          id = "audio-new",
          seq = 4,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/new-audio.wav",
                sampleRate = 22050,
              )
            ),
          now = now,
        ),
      )

    val selected =
      selectHistoricalMediaAttachments(
        messages = messages,
        maxImageAttachments = 2,
        maxAudioAttachments = 1,
      )

    assertEquals(
      listOf("/tmp/new-image-1.png", "/tmp/new-image-2.png"),
      selected.images.map { it.filePath },
    )
    assertEquals(listOf("/tmp/new-audio.wav"), selected.audioClips.map { it.filePath })
  }

  @Test
  fun promptRenderableContent_addsExplicitMultimodalContextMarker() {
    val now = System.currentTimeMillis()
    val imageMessage =
      message(
        id = "image",
        seq = 1,
        kind = MessageKind.IMAGE,
        content = "Shared 1 image(s).",
        attachments =
          listOf(
            attachment(
              type = RoleplayMessageAttachmentType.IMAGE,
              filePath = "/tmp/image.png",
            )
          ),
        now = now,
      )
    val audioMessage =
      message(
        id = "audio",
        seq = 2,
        kind = MessageKind.AUDIO,
        content = "Shared an audio clip.",
        attachments =
          listOf(
            attachment(
              type = RoleplayMessageAttachmentType.AUDIO,
              filePath = "/tmp/audio.wav",
              sampleRate = 16000,
            )
          ),
        now = now,
      )

    assertTrue(imageMessage.toPromptRenderableContent().contains("multimodal context"))
    assertTrue(audioMessage.toPromptRenderableContent().contains("multimodal context"))
  }

  @Test
  fun promptRenderableContent_includesStoredAttachmentUnderstandingText() {
    val now = System.currentTimeMillis()
    val imageMessage =
      message(
        id = "image",
        seq = 1,
        kind = MessageKind.IMAGE,
        content = "Shared 1 image(s).",
        attachments =
          listOf(
            attachment(
              type = RoleplayMessageAttachmentType.IMAGE,
              filePath = "/tmp/image.png",
              contextText = "A handwritten number 8 on white paper.",
            )
          ),
        now = now,
      )

    assertTrue(imageMessage.toPromptRenderableContent().contains("A handwritten number 8"))
  }

  @Test
  fun selectConversationMediaAttachments_capsHistoricalReplayToSingleImageAndAudio() {
    val now = System.currentTimeMillis()
    val dialogueWindow =
      listOf(
        message(
          id = "image-old",
          seq = 1,
          kind = MessageKind.IMAGE,
          content = "Shared 1 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/old-image.png",
              )
            ),
          now = now,
        ),
        message(
          id = "image-new",
          seq = 2,
          kind = MessageKind.IMAGE,
          content = "Shared 1 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/new-image.png",
              )
            ),
          now = now,
        ),
        message(
          id = "audio-old",
          seq = 3,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/old-audio.wav",
                sampleRate = 16000,
              )
            ),
          now = now,
        ),
        message(
          id = "audio-new",
          seq = 4,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/new-audio.wav",
                sampleRate = 22050,
              )
            ),
          now = now,
        ),
      )

    val selected =
      selectConversationMediaAttachments(
        dialogueWindow = dialogueWindow,
        currentMessages = emptyList(),
      )

    assertEquals(listOf("/tmp/new-image.png"), selected.images.map { it.filePath })
    assertEquals(listOf("/tmp/new-audio.wav"), selected.audioClips.map { it.filePath })
  }

  @Test
  fun selectConversationMediaAttachments_prefersCurrentTurnAttachmentsOverHistoricalReplay() {
    val now = System.currentTimeMillis()
    val dialogueWindow =
      listOf(
        message(
          id = "historical-image",
          seq = 1,
          kind = MessageKind.IMAGE,
          content = "Shared 1 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/historical-image.png",
              )
            ),
          now = now,
        ),
        message(
          id = "historical-audio",
          seq = 2,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/historical-audio.wav",
                sampleRate = 16000,
              )
            ),
          now = now,
        ),
      )
    val currentMessages =
      listOf(
        message(
          id = "current-image",
          seq = 3,
          kind = MessageKind.IMAGE,
          content = "Shared 1 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/current-image.png",
              )
            ),
          now = now,
        ),
        message(
          id = "current-audio",
          seq = 4,
          kind = MessageKind.AUDIO,
          content = "Shared an audio clip.",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.AUDIO,
                filePath = "/tmp/current-audio.wav",
                sampleRate = 22050,
              )
            ),
          now = now,
        ),
      )

    val selected =
      selectConversationMediaAttachments(
        dialogueWindow = dialogueWindow,
        currentMessages = currentMessages,
      )

    assertEquals(listOf("/tmp/current-image.png"), selected.images.map { it.filePath })
    assertEquals(listOf("/tmp/current-audio.wav"), selected.audioClips.map { it.filePath })
    assertTrue(selected.historicalImages.isEmpty())
    assertTrue(selected.historicalAudioClips.isEmpty())
  }

  @Test
  fun buildOverflowMediaText_usesStoredUnderstandingForOverflowedCurrentImages() {
    val now = System.currentTimeMillis()
    val currentMessages =
      listOf(
        message(
          id = "current-image",
          seq = 1,
          kind = MessageKind.IMAGE,
          content = "Shared 2 image(s).",
          attachments =
            listOf(
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/current-image-1.png",
                contextText = "A cat sitting on a sofa.",
              ),
              attachment(
                type = RoleplayMessageAttachmentType.IMAGE,
                filePath = "/tmp/current-image-2.png",
                contextText = "A handwritten number 8 on white paper.",
              ),
            ),
          now = now,
        )
      )

    val selected =
      selectConversationMediaAttachments(
        dialogueWindow = emptyList(),
        currentMessages = currentMessages,
      )

    val overflowText =
      buildOverflowMediaText(
        currentMessages = currentMessages,
        selectedAttachments = selected,
      )

    assertTrue(overflowText.currentTurnText.contains("Additional image context"))
    assertTrue(overflowText.currentTurnText.contains("A handwritten number 8 on white paper."))
  }

  private fun message(
    id: String,
    seq: Int,
    kind: MessageKind,
    content: String,
    attachments: List<RoleplayMessageAttachment>,
    now: Long,
  ): Message {
    return Message(
      id = id,
      sessionId = "session-1",
      seq = seq,
      side = MessageSide.USER,
      kind = kind,
      status = MessageStatus.COMPLETED,
      content = content,
      metadataJson =
        encodeRoleplayMessageMediaPayload(
          RoleplayMessageMediaPayload(attachments = attachments)
        ),
      createdAt = now,
      updatedAt = now,
    )
  }

  private fun attachment(
    type: RoleplayMessageAttachmentType,
    filePath: String,
    sampleRate: Int? = null,
    contextText: String? = null,
  ): RoleplayMessageAttachment {
    return RoleplayMessageAttachment(
      type = type,
      filePath = filePath,
      mimeType =
        when (type) {
          RoleplayMessageAttachmentType.IMAGE -> "image/png"
          RoleplayMessageAttachmentType.AUDIO -> "audio/raw"
        },
      contextText = contextText,
      sampleRate = sampleRate,
    )
  }
}
