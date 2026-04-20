package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleplaySeedData

interface RoleplaySeedCatalog {
  fun defaultRoles(now: Long, defaultModelId: String?): List<RoleCard>
}

@Singleton
class AndroidRoleplaySeedCatalog
@Inject
constructor(@ApplicationContext private val appContext: Context) : RoleplaySeedCatalog {
  override fun defaultRoles(now: Long, defaultModelId: String?): List<RoleCard> {
    return RoleplaySeedData.defaultRoles(
      context = appContext,
      now = now,
      defaultModelId = defaultModelId,
    )
  }
}
