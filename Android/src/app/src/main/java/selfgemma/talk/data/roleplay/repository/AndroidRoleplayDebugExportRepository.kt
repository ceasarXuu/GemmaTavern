package selfgemma.talk.data.roleplay.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selfgemma.talk.domain.roleplay.model.RoleplayDebugStoredFile
import selfgemma.talk.domain.roleplay.repository.RoleplayDebugExportRepository

private const val DEBUG_EXPORT_MIME_TYPE = "application/json"
private val DEBUG_EXPORT_BUNDLE_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/GemmaTavern/debug-exports/"
private const val DEBUG_EXPORT_POINTER_FILE_NAME = "latest-debug-export.json"
private const val ADB_DOWNLOAD_ROOT = "/sdcard/"

@Singleton
class AndroidRoleplayDebugExportRepository
@Inject
constructor(
  @ApplicationContext private val context: Context,
) : RoleplayDebugExportRepository {
  override suspend fun writeBundle(displayName: String, content: ByteArray): RoleplayDebugStoredFile =
    withContext(Dispatchers.IO) {
      upsertJsonFile(displayName = displayName, content = content)
    }

  override suspend fun writeLatestPointer(content: ByteArray): RoleplayDebugStoredFile =
    withContext(Dispatchers.IO) {
      upsertJsonFile(displayName = DEBUG_EXPORT_POINTER_FILE_NAME, content = content)
    }

  private fun upsertJsonFile(displayName: String, content: ByteArray): RoleplayDebugStoredFile {
    val existingUri = findExistingFileUri(displayName)
    val targetUri =
      existingUri ?: createPendingFile(displayName = displayName)
    writeContent(uri = targetUri, content = content)
    markPendingState(uri = targetUri, isPending = false)
    val relativePath = "$DEBUG_EXPORT_BUNDLE_DIRECTORY$displayName"
    return RoleplayDebugStoredFile(
      fileName = displayName,
      relativePath = relativePath,
      adbPath = "$ADB_DOWNLOAD_ROOT$relativePath",
      contentUri = targetUri.toString(),
    )
  }

  private fun createPendingFile(displayName: String): Uri {
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, DEBUG_EXPORT_MIME_TYPE)
        put(MediaStore.MediaColumns.RELATIVE_PATH, DEBUG_EXPORT_BUNDLE_DIRECTORY)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    return checkNotNull(context.contentResolver.insert(downloadsCollectionUri(), values)) {
      "Unable to create debug export file: $displayName"
    }
  }

  private fun findExistingFileUri(displayName: String): Uri? {
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val args = arrayOf(displayName, DEBUG_EXPORT_BUNDLE_DIRECTORY)
    context.contentResolver.query(downloadsCollectionUri(), projection, selection, args, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        return ContentUris.withAppendedId(downloadsCollectionUri(), cursor.getLong(idIndex))
      }
    }
    return null
  }

  private fun writeContent(uri: Uri, content: ByteArray) {
    markPendingState(uri = uri, isPending = true)
    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
      "Unable to open output stream for debug export uri=$uri"
    }.use { output ->
      output.write(content)
      output.flush()
    }
  }

  private fun markPendingState(uri: Uri, isPending: Boolean) {
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, if (isPending) 1 else 0)
      }
    context.contentResolver.update(uri, values, null, null)
  }

  private fun downloadsCollectionUri(): Uri {
    return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
  }
}
