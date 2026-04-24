package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin

interface RoleplayDebugBundleExportLauncher {
  suspend fun exportFromSession(
    sessionId: String,
    origin: RoleplayDebugExportOrigin,
  ): String?
}
