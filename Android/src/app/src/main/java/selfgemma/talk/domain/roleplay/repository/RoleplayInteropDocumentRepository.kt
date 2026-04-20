package selfgemma.talk.domain.roleplay.repository

data class RoleplayInteropDocumentMetadata(
  val displayName: String? = null,
  val mimeType: String? = null,
)

interface RoleplayInteropDocumentRepository {
  suspend fun readText(uri: String): String

  suspend fun writeText(uri: String, content: String)

  suspend fun readBytes(uri: String): ByteArray

  suspend fun writeBytes(uri: String, content: ByteArray)

  suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata
}
