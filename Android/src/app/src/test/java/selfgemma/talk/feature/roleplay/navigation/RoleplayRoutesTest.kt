package selfgemma.talk.feature.roleplay.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleplayRoutesTest {
  @Test
  fun `role editor route encodes role id`() {
    assertEquals(
      "roleplay_role_editor?roleId=role%201%3Ftest",
      RoleplayRoutes.roleEditor("role 1?test"),
    )
  }

  @Test
  fun `profile route supports direct persona editor entry`() {
    assertEquals(
      "roleplay_profile?slotId=user-default.png&edit=true",
      RoleplayRoutes.profile(slotId = "user-default.png", edit = true),
    )
  }

  @Test
  fun `profile route omits slot when opening current persona`() {
    assertEquals(
      "roleplay_profile?edit=true",
      RoleplayRoutes.profile(edit = true),
    )
  }
}
