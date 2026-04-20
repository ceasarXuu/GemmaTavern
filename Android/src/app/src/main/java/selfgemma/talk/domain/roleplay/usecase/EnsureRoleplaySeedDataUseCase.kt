package selfgemma.talk.domain.roleplay.usecase

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import selfgemma.talk.domain.roleplay.repository.RoleRepository

private const val TAG = "EnsureRoleplaySeedData"

private fun logDebug(message: String) {
  runCatching { Log.d(TAG, message) }
}

private fun logWarn(message: String) {
  runCatching { Log.w(TAG, message) }
}

@Singleton
class EnsureRoleplaySeedDataUseCase
@Inject
constructor(
  private val roleRepository: RoleRepository,
  private val roleplaySeedCatalog: RoleplaySeedCatalog,
) {
  private val mutex = Mutex()

  suspend operator fun invoke(defaultModelId: String? = null) {
    mutex.withLock {
      val existingRoles = roleRepository.observeRoles().first()
      val existingRolesById = existingRoles.associateBy { it.id }
      val now = System.currentTimeMillis()
      val localizedBuiltInRoles =
        roleplaySeedCatalog.defaultRoles(now = now, defaultModelId = defaultModelId)
      val currentBuiltInIds = localizedBuiltInRoles.map { it.id }.toSet()

      localizedBuiltInRoles.forEach { seededRole ->
        val existingRole = existingRolesById[seededRole.id]
        val syncedRole =
          seededRole.copy(
            avatarUri = existingRole?.avatarUri ?: seededRole.avatarUri,
            coverUri = existingRole?.coverUri ?: seededRole.coverUri,
            defaultModelId = seededRole.defaultModelId ?: existingRole?.defaultModelId,
            defaultTemperature = existingRole?.defaultTemperature ?: seededRole.defaultTemperature,
            defaultTopP = existingRole?.defaultTopP ?: seededRole.defaultTopP,
            defaultTopK = existingRole?.defaultTopK ?: seededRole.defaultTopK,
            runtimeProfile = existingRole?.runtimeProfile ?: seededRole.runtimeProfile,
            mediaProfile = existingRole?.mediaProfile ?: seededRole.mediaProfile,
            interopState = existingRole?.interopState ?: seededRole.interopState,
            archived = false,
            createdAt = existingRole?.createdAt ?: seededRole.createdAt,
            updatedAt = now,
          )
        logDebug("sync built-in roleId=${syncedRole.id} localizedName=${syncedRole.name} existing=${existingRole != null}")
        roleRepository.saveRole(syncedRole)
      }

      existingRoles
        .filter { it.builtIn && it.id !in currentBuiltInIds }
        .forEach { legacyBuiltIn ->
          logDebug("delete legacy built-in roleId=${legacyBuiltIn.id} name=${legacyBuiltIn.name}")
          roleRepository.deleteRole(legacyBuiltIn.id)
        }

      if (localizedBuiltInRoles.isEmpty()) {
        logWarn("seed catalog returned no built-in roles")
      }
    }
  }
}
