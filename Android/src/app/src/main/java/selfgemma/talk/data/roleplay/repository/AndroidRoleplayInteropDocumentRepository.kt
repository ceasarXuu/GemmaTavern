package selfgemma.talk.data.roleplay.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentMetadata
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

@Singleton
class AndroidRoleplayInteropDocumentRepository
@Inject
constructor(@ApplicationContext private val context: Context) : RoleplayInteropDocumentRepository {
  override suspend fun readText(uri: String): String =
    withContext(Dispatchers.IO) {
      readBytes(uri).decodeToString()
    }

  override suspend fun writeText(uri: String, content: String) {
    writeBytes(uri, content.toByteArray())
  }

  override suspend fun readBytes(uri: String): ByteArray =
    withContext(Dispatchers.IO) {
      openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to open input stream for uri=$uri")
    }

  override suspend fun writeBytes(uri: String, content: ByteArray) {
    withContext(Dispatchers.IO) {
      openOutputStream(uri)?.use { output ->
        output.write(content)
      } ?: error("Unable to open output stream for uri=$uri")
    }
  }

  override suspend fun getMetadata(uri: String): RoleplayInteropDocumentMetadata =
    withContext(Dispatchers.IO) {
      val parsedUri = Uri.parse(uri)
      if (parsedUri.scheme.isNullOrBlank() || parsedUri.scheme == "file") {
        val file = if (parsedUri.scheme == "file") File(parsedUri.path.orEmpty()) else File(uri)
        return@withContext RoleplayInteropDocumentMetadata(
          displayName = file.name.takeIf { it.isNotBlank() },
          mimeType = context.contentResolver.getType(parsedUri),
        )
      }

      var displayName: String? = null
      context.contentResolver.query(parsedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0) {
            displayName = cursor.getString(index)
          }
        }
      }

      RoleplayInteropDocumentMetadata(
        displayName = displayName,
        mimeType = context.contentResolver.getType(parsedUri),
      )
    }

  private fun openInputStream(uri: String) =
    run {
      val parsedUri = Uri.parse(uri)
      if (parsedUri.scheme.isNullOrBlank()) {
        File(uri).inputStream()
      } else if (parsedUri.scheme == "file") {
        File(parsedUri.path.orEmpty()).inputStream()
      } else {
        context.contentResolver.openInputStream(parsedUri)
      }
    }

  private fun openOutputStream(uri: String) =
    run {
      val parsedUri = Uri.parse(uri)
      if (parsedUri.scheme.isNullOrBlank()) {
        File(uri).outputStream()
      } else if (parsedUri.scheme == "file") {
        File(parsedUri.path.orEmpty()).outputStream()
      } else {
        context.contentResolver.openOutputStream(parsedUri, "w")
      }
    }
}
