package selfgemma.talk.domain.roleplay.usecase

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.RoleplayDebugBundle
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportPointer
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportResult
import selfgemma.talk.domain.roleplay.repository.RoleplayDebugExportRepository

class WriteRoleplayDebugBundleUseCase
@Inject
constructor(
  private val repository: RoleplayDebugExportRepository,
  private val serializer: RoleplayDebugExportJsonSerializer,
) {
  fun buildBundleFileName(sessionId: String, exportedAt: Long): String {
    val timestamp =
      FILE_NAME_TIME_FORMATTER.format(Instant.ofEpochMilli(exportedAt).atZone(ZoneId.systemDefault()))
    val sanitizedSessionId = sessionId.replace(FILE_NAME_SANITIZER_REGEX, "_")
    return "roleplay-debug-$sanitizedSessionId-$timestamp.json"
  }

  suspend fun write(bundle: RoleplayDebugBundle, origin: selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin): RoleplayDebugExportResult {
    val bundleFileName = buildBundleFileName(sessionId = bundle.session.id, exportedAt = bundle.exportedAt)
    val bundleFile = repository.writeBundle(displayName = bundleFileName, content = serializer.toJsonBytes(bundle))
    val pointer =
      RoleplayDebugExportPointer(
        sessionId = bundle.session.id,
        roleName = bundle.role.name,
        title = bundle.session.title,
        exportedAt = bundle.exportedAt,
        relativePath = bundleFile.relativePath,
        fileName = bundleFile.fileName,
        messageCount = bundle.messages.size,
        toolInvocationCount = bundle.toolInvocations.size,
        externalFactCount = bundle.externalFacts.size,
      )
    val pointerFile = repository.writeLatestPointer(content = serializer.toJsonBytes(pointer))
    return RoleplayDebugExportResult(
      exportedAt = bundle.exportedAt,
      sessionId = bundle.session.id,
      sessionTitle = bundle.session.title,
      roleName = bundle.role.name,
      messageCount = bundle.messages.size,
      toolInvocationCount = bundle.toolInvocations.size,
      externalFactCount = bundle.externalFacts.size,
      origin = origin,
      bundleFile = bundleFile,
      pointerFile = pointerFile,
    )
  }

  companion object {
    private val FILE_NAME_SANITIZER_REGEX = Regex("[^a-zA-Z0-9._-]")
    private val FILE_NAME_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
  }
}
