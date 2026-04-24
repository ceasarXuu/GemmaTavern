package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin

class NoopRoleplayDebugBundleExportLauncher
@Inject
constructor() : RoleplayDebugBundleExportLauncher {
  override suspend fun exportFromSession(
    sessionId: String,
    origin: RoleplayDebugExportOrigin,
  ): String? = null
}
