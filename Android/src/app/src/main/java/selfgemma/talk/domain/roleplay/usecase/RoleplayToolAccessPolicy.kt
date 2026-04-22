package selfgemma.talk.domain.roleplay.usecase

import android.Manifest
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
  fun canUseLocationTools(): Boolean {
    return dataStoreRepository.isRoleplayLocationToolsEnabled() &&
      hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  }

  fun canUseCalendarTools(): Boolean {
    return dataStoreRepository.isRoleplayCalendarToolsEnabled() &&
      hasPermission(Manifest.permission.READ_CALENDAR)
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(appContext, permission) ==
      PackageManager.PERMISSION_GRANTED
  }
}
