package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachment
import selfgemma.talk.domain.roleplay.model.RoleplayMessageAttachmentType
import selfgemma.talk.domain.roleplay.model.RoleplayMessageMediaPayload
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.ToolInvocation

data class RoleplayContinuityDebugState(
  val runtimeState: RuntimeStateSnapshot? = null,
  val openThreads: List<OpenThread> = emptyList(),
  val memoryAtoms: List<MemoryAtom> = emptyList(),
  val recentEvents: List<SessionEvent> = emptyList(),
  val latestMemoryQueryPayload: String? = null,
  val latestMemoryPackPayload: String? = null,
  val compactionEntryCount: Int = 0,
)

data class RoleplayChatUiState(
  val loading: Boolean = true,
  val session: Session? = null,
  val role: RoleCard? = null,
  val messages: List<selfgemma.talk.domain.roleplay.model.Message> = emptyList(),
  val draft: String = "",
  val userPersonaSlotId: String = "",
  val userPersonaName: String = "",
  val userPersonaAvatarUri: String? = null,
  val userPersonaDescription: String = "",
  val summary: SessionSummary? = null,
  val pinnedMemories: List<MemoryItem> = emptyList(),
  val toolInvocations: List<ToolInvocation> = emptyList(),
  val continuityDebug: RoleplayContinuityDebugState = RoleplayContinuityDebugState(),
  val inProgress: Boolean = false,
  val hasPendingSends: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

internal data class QueuedUserMessage(
  val message: selfgemma.talk.domain.roleplay.model.Message,
  val persisted: Boolean = false,
)

internal data class RoleplayChatMetaState(
  val summary: SessionSummary? = null,
  val pinnedMemories: List<MemoryItem> = emptyList(),
  val continuityDebug: RoleplayContinuityDebugState = RoleplayContinuityDebugState(),
  val pendingUserMessages: List<QueuedUserMessage> = emptyList(),
  val inProgress: Boolean = false,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

internal data class RoleplayChatTransientState(
  val draft: String,
  val meta: RoleplayChatMetaState,
  val toolInvocations: List<ToolInvocation>,
)

private const val TAG_RPCVM = "RoleplayChatViewModel"

internal fun logDebug(message: String) {
  runCatching { Log.d(TAG_RPCVM, message) }
}

internal fun logWarn(message: String) {
  runCatching { Log.w(TAG_RPCVM, message) }
}

internal fun logError(message: String, error: Throwable) {
  runCatching { Log.e(TAG_RPCVM, message, error) }
}

internal fun resolveAttachmentFile(appContext: Context, sessionId: String, messageId: String, fileName: String): File {
  val directory = File(appContext.filesDir, "roleplay-media/$sessionId/$messageId")
  if (!directory.exists()) {
    directory.mkdirs()
  }
  return File(directory, fileName)
}

internal fun writeBitmapToFile(bitmap: Bitmap, file: File) {
  FileOutputStream(file).use { output ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    output.flush()
  }
}

internal fun persistImagePayload(
  appContext: Context,
  sessionId: String,
  messageId: String,
  bitmaps: List<Bitmap>,
): RoleplayMessageMediaPayload {
  val attachments =
    bitmaps.mapIndexed { index, bitmap ->
      val targetFile = resolveAttachmentFile(appContext, sessionId, messageId, "image-${index + 1}.png")
      writeBitmapToFile(bitmap = bitmap, file = targetFile)
      RoleplayMessageAttachment(
        type = RoleplayMessageAttachmentType.IMAGE,
        filePath = targetFile.absolutePath,
        mimeType = "image/png",
        width = bitmap.width,
        height = bitmap.height,
        fileSizeBytes = targetFile.length(),
      )
    }
  return RoleplayMessageMediaPayload(attachments = attachments)
}

internal fun persistAudioPayload(
  appContext: Context,
  sessionId: String,
  messageId: String,
  audioData: ByteArray,
  sampleRate: Int,
): RoleplayMessageMediaPayload {
  val targetFile = resolveAttachmentFile(appContext, sessionId, messageId, "audio-1.pcm")
  targetFile.writeBytes(audioData)
  val durationMs =
    if (sampleRate > 0) {
      ((audioData.size / 2.0) / sampleRate * 1000).toLong()
    } else {
      null
    }
  return RoleplayMessageMediaPayload(
    attachments =
      listOf(
        RoleplayMessageAttachment(
          type = RoleplayMessageAttachmentType.AUDIO,
          filePath = targetFile.absolutePath,
          mimeType = "audio/raw",
          sampleRate = sampleRate,
          durationMs = durationMs,
          fileSizeBytes = targetFile.length(),
        )
      )
  )
}

internal fun String.escapeJson(): String {
  return replace("\\", "\\\\").replace("\"", "\\\"")
}
