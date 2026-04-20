package selfgemma.talk.domain.roleplay.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RoleplayMessageAttachmentType {
  IMAGE,
  AUDIO,
}

data class RoleplayMessageAttachment(
  val type: RoleplayMessageAttachmentType,
  val filePath: String,
  val mimeType: String,
  val contextText: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  val sampleRate: Int? = null,
  val durationMs: Long? = null,
  val fileSizeBytes: Long? = null,
)

data class RoleplayMessageMediaPayload(
  val attachments: List<RoleplayMessageAttachment> = emptyList(),
)

private val roleplayMessageMediaGson: Gson = GsonBuilder().create()

fun encodeRoleplayMessageMediaPayload(value: RoleplayMessageMediaPayload?): String? {
  return value?.takeIf { it.attachments.isNotEmpty() }?.let(roleplayMessageMediaGson::toJson)
}

fun decodeRoleplayMessageMediaPayload(value: String?): RoleplayMessageMediaPayload? {
  if (value.isNullOrBlank()) {
    return null
  }
  return runCatching {
    roleplayMessageMediaGson.fromJson(value, RoleplayMessageMediaPayload::class.java)
  }.getOrNull()
}

fun Message.roleplayMessageMediaPayload(): RoleplayMessageMediaPayload? {
  return decodeRoleplayMessageMediaPayload(metadataJson)
}

fun pcm16MonoToWav(audioData: ByteArray, sampleRate: Int): ByteArray {
  val header = ByteArray(44)
  val pcmDataSize = audioData.size
  val wavFileSize = pcmDataSize + 44
  val channels = 1
  val bitsPerSample: Short = 16
  val byteRate = sampleRate * channels * bitsPerSample / 8
  val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

  buffer.put("RIFF".toByteArray())
  buffer.putInt(wavFileSize)
  buffer.put("WAVE".toByteArray())
  buffer.put("fmt ".toByteArray())
  buffer.putInt(16)
  buffer.putShort(1.toShort())
  buffer.putShort(channels.toShort())
  buffer.putInt(sampleRate)
  buffer.putInt(byteRate)
  buffer.putShort((channels * bitsPerSample / 8).toShort())
  buffer.putShort(bitsPerSample)
  buffer.put("data".toByteArray())
  buffer.putInt(pcmDataSize)

  return header + audioData
}
