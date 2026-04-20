package selfgemma.talk.data.roleplay.interop.stcardpng

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal data class PngChunk(
  val type: String,
  val data: ByteArray,
)

internal object PngTextChunkCodec {
  private val pngSignature =
    byteArrayOf(
      0x89.toByte(),
      0x50,
      0x4E,
      0x47,
      0x0D,
      0x0A,
      0x1A,
      0x0A,
    )

  fun decodeChunks(pngBytes: ByteArray): List<PngChunk> {
    require(hasValidSignature(pngBytes)) { "Invalid PNG signature." }

    val chunks = mutableListOf<PngChunk>()
    var offset = pngSignature.size
    while (offset + 12 <= pngBytes.size) {
      val length = readInt(pngBytes, offset)
      val typeOffset = offset + 4
      val dataOffset = typeOffset + 4
      val endOffset = dataOffset + length
      require(endOffset + 4 <= pngBytes.size) { "Invalid PNG chunk length." }

      val type = pngBytes.copyOfRange(typeOffset, dataOffset).decodeToString()
      val data = pngBytes.copyOfRange(dataOffset, endOffset)
      chunks += PngChunk(type = type, data = data)
      offset = endOffset + 4
      if (type == "IEND") {
        break
      }
    }
    return chunks
  }

  fun encodeChunks(chunks: List<PngChunk>): ByteArray {
    val output = ByteArrayOutputStream()
    output.write(pngSignature)
    chunks.forEach { chunk ->
      output.write(intToBytes(chunk.data.size))
      val typeBytes = chunk.type.toByteArray(Charsets.US_ASCII)
      output.write(typeBytes)
      output.write(chunk.data)
      output.write(crcBytes(typeBytes = typeBytes, data = chunk.data))
    }
    return output.toByteArray()
  }

  fun encodeTextChunk(keyword: String, text: String): PngChunk {
    return PngChunk(
      type = "tEXt",
      data = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1),
    )
  }

  fun decodeTextChunk(chunk: PngChunk): Pair<String, String>? {
    if (chunk.type != "tEXt") {
      return null
    }
    val separatorIndex = chunk.data.indexOf(0)
    require(separatorIndex >= 0) { "Invalid PNG tEXt chunk." }
    val keyword = chunk.data.copyOfRange(0, separatorIndex).decodeToString()
    val text = chunk.data.copyOfRange(separatorIndex + 1, chunk.data.size).decodeToString()
    return keyword to text
  }

  private fun hasValidSignature(bytes: ByteArray): Boolean {
    return bytes.size >= pngSignature.size && bytes.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)
  }

  private fun readInt(bytes: ByteArray, offset: Int): Int {
    return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
  }

  private fun intToBytes(value: Int): ByteArray {
    return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
  }

  private fun crcBytes(typeBytes: ByteArray, data: ByteArray): ByteArray {
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    return intToBytes(crc.value.toInt())
  }
}
