package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.roleplayMessageMediaPayload

internal data class CloudRoleplayMediaBridgeResult(
  val text: String,
  val requiredCount: Int,
  val bridgedCount: Int,
  val available: Boolean,
)

internal object CloudRoleplayMediaBridge {
  fun build(userMessages: List<Message>): CloudRoleplayMediaBridgeResult {
    val lines = mutableListOf<String>()
    var required = 0
    var bridged = 0
    userMessages.forEach { message ->
      message.roleplayMessageMediaPayload()?.attachments.orEmpty().forEachIndexed { index, attachment ->
        val context = attachment.contextText?.trim().orEmpty()
        if (attachment.type == RoleplayMessageAttachmentType.IMAGE || attachment.type == RoleplayMessageAttachmentType.AUDIO) {
          required += 1
          if (context.isNotBlank()) {
            bridged += 1
            lines += "- ${attachment.type.name.lowercase()} ${index + 1}: $context"
          } else if (attachment.type == RoleplayMessageAttachmentType.AUDIO) {
            lines += "- audio ${index + 1}: ${attachment.audioMetadataText()}; no local transcript is available."
          }
        }
      }
    }
    return CloudRoleplayMediaBridgeResult(
      text = lines.joinToString(separator = "\n"),
      requiredCount = required,
      bridgedCount = bridged,
      available = required == 0 || required == bridged,
    )
  }

  private fun RoleplayMessageAttachment.audioMetadataText(): String {
    return buildString {
      append(mimeType.ifBlank { "audio" })
      durationMs?.let { append(", durationMs=").append(it) }
      sampleRate?.let { append(", sampleRate=").append(it) }
      fileSizeBytes?.let { append(", bytes=").append(it) }
    }
  }
}
