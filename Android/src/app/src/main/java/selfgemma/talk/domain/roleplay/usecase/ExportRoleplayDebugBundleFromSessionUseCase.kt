package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.BuildConfig
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportAppInfo
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.repository.ToolInvocationRepository

class ExportRoleplayDebugBundleFromSessionUseCase
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val toolInvocationRepository: ToolInvocationRepository,
  private val mapper: RoleplayDebugExportMapper,
  private val writer: WriteRoleplayDebugBundleUseCase,
  private val serializer: RoleplayDebugExportJsonSerializer,
) {
  internal var nowProvider: () -> Long = { System.currentTimeMillis() }

  suspend fun exportFromSession(
    sessionId: String,
    origin: RoleplayDebugExportOrigin,
  ): selfgemma.talk.domain.roleplay.model.RoleplayDebugExportResult {
    val session = conversationRepository.getSession(sessionId) ?: error("Session not found.")
    val role = roleRepository.getRole(session.roleId) ?: error("Role not found.")
    val userProfile = session.resolveUserProfile(dataStoreRepository.getStUserProfile())
    val exportedAt = nowProvider()
    val bundle =
      mapper.buildBundle(
        exportedAt = exportedAt,
        appInfo =
          RoleplayDebugExportAppInfo(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debugBuild = BuildConfig.DEBUG,
          ),
        session = session,
        role = role,
        userProfile = userProfile,
        summary = conversationRepository.getSummary(sessionId),
        messages = conversationRepository.listMessages(sessionId),
        toolInvocations = toolInvocationRepository.listBySession(sessionId),
        sessionEvents = conversationRepository.listEvents(sessionId),
        origin = origin,
      )
    val result = writer.write(bundle = bundle, origin = origin)
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.EXPORT,
        payloadJson =
          serializer.toJson(
            RoleplayDebugExportAuditPayload(
              exportKind = "roleplay_debug_bundle",
              initiatedFrom = origin.rawValue,
              exportedAt = result.exportedAt,
              fileName = result.bundleFile.fileName,
              relativePath = result.bundleFile.relativePath,
              messageCount = result.messageCount,
              toolInvocationCount = result.toolInvocationCount,
              schemaVersion = bundle.schemaVersion,
            )
          ),
        createdAt = exportedAt,
      )
    )
    return result
  }
}

private data class RoleplayDebugExportAuditPayload(
  val exportKind: String,
  val initiatedFrom: String,
  val exportedAt: Long,
  val fileName: String,
  val relativePath: String,
  val messageCount: Int,
  val toolInvocationCount: Int,
  val schemaVersion: String,
)
