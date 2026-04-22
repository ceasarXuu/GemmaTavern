package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import selfgemma.talk.testing.FakeDataStoreRepository

internal fun fakeRoleplayToolAccessPolicy(): RoleplayToolAccessPolicy {
  return RoleplayToolAccessPolicy(
    appContext = ContextWrapper(null),
    dataStoreRepository = FakeDataStoreRepository(),
  )
}
