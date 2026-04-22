package selfgemma.talk.domain.roleplay.repository

import selfgemma.talk.domain.roleplay.model.RoleplayDebugStoredFile

interface RoleplayDebugExportRepository {
  suspend fun writeBundle(displayName: String, content: ByteArray): RoleplayDebugStoredFile

  suspend fun writeLatestPointer(content: ByteArray): RoleplayDebugStoredFile
}
