package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin

class AndroidRoleplayDebugBundleExportLauncher
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val delegate: ExportRoleplayDebugBundleFromSessionUseCase,
) : RoleplayDebugBundleExportLauncher {
  override suspend fun exportFromSession(
    sessionId: String,
    origin: RoleplayDebugExportOrigin,
  ): String {
    val result = delegate.exportFromSession(sessionId = sessionId, origin = origin)
    return appContext.getString(
      R.string.roleplay_debug_export_status,
      result.sessionTitle,
      displaySessionId(result.sessionId),
      result.bundleFile.fileName,
    )
  }

  private fun displaySessionId(sessionId: String): String {
    return if (sessionId.length <= 12) sessionId else sessionId.take(8)
  }
}
