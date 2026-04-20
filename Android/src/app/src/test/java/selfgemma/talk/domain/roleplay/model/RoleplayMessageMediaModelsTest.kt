package selfgemma.talk.domain.roleplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayMessageMediaModelsTest {
  @Test
  fun encodeAndDecodeRoleplayMessageMediaPayload_roundTripsAttachments() {
    val payload =
      RoleplayMessageMediaPayload(
        attachments =
          listOf(
            RoleplayMessageAttachment(
              type = RoleplayMessageAttachmentType.IMAGE,
              filePath = "/tmp/test.png",
              mimeType = "image/png",
              contextText = "A handwritten number 8 on white paper.",
              width = 512,
              height = 512,
              fileSizeBytes = 42L,
            ),
            RoleplayMessageAttachment(
              type = RoleplayMessageAttachmentType.AUDIO,
              filePath = "/tmp/test.pcm",
              mimeType = "audio/raw",
              contextText = "A short spoken greeting.",
              sampleRate = 16000,
              durationMs = 1500L,
              fileSizeBytes = 32000L,
            ),
          )
      )

    val encoded = encodeRoleplayMessageMediaPayload(payload)
    val decoded = decodeRoleplayMessageMediaPayload(encoded)

    assertNotNull(encoded)
    assertEquals(payload, decoded)
  }

  @Test
  fun pcm16MonoToWav_addsWavHeaderWithoutChangingPayloadSize() {
    val pcm = ByteArray(320) { index -> (index % 16).toByte() }

    val wav = pcm16MonoToWav(audioData = pcm, sampleRate = 16000)

    assertEquals(pcm.size + 44, wav.size)
    assertTrue(wav.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()))
    assertTrue(wav.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()))
  }
}
