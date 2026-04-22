package selfgemma.talk.domain.roleplay.usecase

import android.Manifest

object RoleplayToolPermissionRequirements {
  fun permissionsForTool(toolId: String): Set<String> {
    return when (toolId) {
      RoleplayToolIds.APPROXIMATE_LOCATION,
      RoleplayToolIds.WEATHER -> setOf(Manifest.permission.ACCESS_COARSE_LOCATION)
      RoleplayToolIds.CALENDAR_SNAPSHOT -> setOf(Manifest.permission.READ_CALENDAR)
      else -> emptySet()
    }
  }
}
