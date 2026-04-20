package selfgemma.talk.data.roleplay.interop.stcardpng

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StPngRoleCardCodecTest {
  @Test
  fun embedAndExtract_roundTripsRoleCardJson() {
    val rawJson =
      """
      {
        "spec": "chara_card_v2",
        "spec_version": "2.0",
        "data": {
          "name": "Iris",
          "description": "Archivist"
        }
      }
      """.trimIndent()

    val pngWithCard = StPngRoleCardCodec.embedCardJson(DEFAULT_TRANSPARENT_PNG, rawJson)
    val extractedJson = StPngRoleCardCodec.extractCardJson(pngWithCard)

    assertTrue(extractedJson.contains(""""name":"Iris""""))
    assertTrue(extractedJson.contains(""""spec":"chara_card_v3""""))
  }

  @Test
  fun extract_prefersCcv3WhenBothChunksExist() {
    val v2Json = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"V2"}}"""
    val v3Json = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"V3"}}"""
    val chunks =
      PngTextChunkCodec.decodeChunks(DEFAULT_TRANSPARENT_PNG).toMutableList().apply {
        val iendIndex = indexOfFirst { it.type == "IEND" }
        add(iendIndex, PngTextChunkCodec.encodeTextChunk("chara", Base64.getEncoder().encodeToString(v2Json.toByteArray())))
        add(iendIndex + 1, PngTextChunkCodec.encodeTextChunk("ccv3", Base64.getEncoder().encodeToString(v3Json.toByteArray())))
      }

    val extractedJson = StPngRoleCardCodec.extractCardJson(PngTextChunkCodec.encodeChunks(chunks))

    assertEquals(v3Json, extractedJson)
  }

  private companion object {
    val DEFAULT_TRANSPARENT_PNG: ByteArray =
      Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO8BzZQAAAAASUVORK5CYII="
      )
  }
}
