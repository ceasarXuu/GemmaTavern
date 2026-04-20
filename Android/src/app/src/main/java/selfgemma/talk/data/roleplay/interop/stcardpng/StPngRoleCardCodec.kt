package selfgemma.talk.data.roleplay.interop.stcardpng

import com.google.gson.JsonParser
import java.util.Base64

internal object StPngRoleCardCodec {
  private const val CHARA_KEY = "chara"
  private const val CCV3_KEY = "ccv3"

  fun extractCardJson(pngBytes: ByteArray): String {
    val textChunks =
      PngTextChunkCodec.decodeChunks(pngBytes)
        .mapNotNull(PngTextChunkCodec::decodeTextChunk)

    val ccv3Value = textChunks.firstOrNull { it.first.equals(CCV3_KEY, ignoreCase = true) }?.second
    if (ccv3Value != null) {
      return decodeBase64(ccv3Value)
    }

    val charaValue = textChunks.firstOrNull { it.first.equals(CHARA_KEY, ignoreCase = true) }?.second
    if (charaValue != null) {
      return decodeBase64(charaValue)
    }

    error("No ST role card metadata found in PNG.")
  }

  fun embedCardJson(basePngBytes: ByteArray, json: String): ByteArray {
    val chunks = PngTextChunkCodec.decodeChunks(basePngBytes).toMutableList()
    chunks.removeAll { chunk ->
      PngTextChunkCodec.decodeTextChunk(chunk)?.first?.let {
        it.equals(CHARA_KEY, ignoreCase = true) || it.equals(CCV3_KEY, ignoreCase = true)
      } == true
    }

    val iendIndex = chunks.indexOfFirst { it.type == "IEND" }.takeIf { it >= 0 } ?: chunks.size
    chunks.add(iendIndex, PngTextChunkCodec.encodeTextChunk(CHARA_KEY, encodeBase64(json)))
    buildV3Json(json)?.let { v3Json ->
      chunks.add(iendIndex + 1, PngTextChunkCodec.encodeTextChunk(CCV3_KEY, encodeBase64(v3Json)))
    }

    return PngTextChunkCodec.encodeChunks(chunks)
  }

  private fun buildV3Json(v2Json: String): String? {
    return runCatching {
      val jsonObject = JsonParser.parseString(v2Json).asJsonObject
      jsonObject.addProperty("spec", "chara_card_v3")
      jsonObject.addProperty("spec_version", "3.0")
      jsonObject.toString()
    }.getOrNull()
  }

  private fun encodeBase64(value: String): String {
    return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
  }

  private fun decodeBase64(value: String): String {
    return String(Base64.getDecoder().decode(value), Charsets.UTF_8)
  }
}
