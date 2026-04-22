package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.data.DataStoreRepository

@Singleton
class RoleplayToolAccessPolicy @Inject constructor(
  @ApplicationContext private val appContext: Context,
  private val dataStoreRepository: DataStoreRepository,
) {
  fun canRegisterTool(toolId: String): Boolean {
    if (!dataStoreRepository.isRoleplayToolEnabled(toolId)) {
      return false
    }
    return missingPermissionsForTool(toolId).isEmpty()
  }

  fun missingPermissionsForTool(toolId: String): Set<String> {
    return RoleplayToolPermissionRequirements
      .permissionsForTool(toolId)
      .filterNot(::hasPermission)
      .toSet()
  }

  fun missingPermissionsForTools(toolIds: Collection<String>): Set<String> {
    return toolIds
      .flatMap { toolId -> missingPermissionsForTool(toolId) }
      .toSet()
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(appContext, permission) ==
      PackageManager.PERMISSION_GRANTED
  }
}
