package selfgemma.talk.feature.roleplay.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.testing.FakeDataStoreRepository

class RoleplaySettingsViewModelTest {
  @Test
  fun init_readsRoleplayToolConsentFlags() {
    val repository =
      FakeDataStoreRepository().apply {
        setRoleplayLocationToolsEnabled(true)
        setRoleplayCalendarToolsEnabled(true)
      }

    val viewModel = RoleplaySettingsViewModel(repository)

    assertTrue(viewModel.uiState.value.roleplayLocationToolsEnabled)
    assertTrue(viewModel.uiState.value.roleplayCalendarToolsEnabled)
  }

  @Test
  fun setRoleplayToolConsentFlags_updatesRepositoryAndState() {
    val repository = FakeDataStoreRepository()
    val viewModel = RoleplaySettingsViewModel(repository)

    viewModel.setRoleplayLocationToolsEnabled(true)
    viewModel.setRoleplayCalendarToolsEnabled(true)

    assertTrue(viewModel.uiState.value.roleplayLocationToolsEnabled)
    assertTrue(viewModel.uiState.value.roleplayCalendarToolsEnabled)
    assertTrue(repository.isRoleplayLocationToolsEnabled())
    assertTrue(repository.isRoleplayCalendarToolsEnabled())

    viewModel.setRoleplayLocationToolsEnabled(false)
    viewModel.setRoleplayCalendarToolsEnabled(false)

    assertFalse(viewModel.uiState.value.roleplayLocationToolsEnabled)
    assertFalse(viewModel.uiState.value.roleplayCalendarToolsEnabled)
    assertFalse(repository.isRoleplayLocationToolsEnabled())
    assertFalse(repository.isRoleplayCalendarToolsEnabled())
  }
}
