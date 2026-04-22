package selfgemma.talk.feature.roleplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolIds
import selfgemma.talk.testing.FakeDataStoreRepository

class RoleplaySettingsViewModelTest {
  @Test
  fun init_defaultsAllRoleplayToolsToEnabled() {
    val viewModel = RoleplaySettingsViewModel(FakeDataStoreRepository())

    assertTrue(viewModel.uiState.value.allToolsEnabled)
    assertTrue(viewModel.uiState.value.toolStates.all { it.enabled })
  }

  @Test
  fun init_readsDisabledRoleplayToolState() {
    val repository =
      FakeDataStoreRepository().apply {
        setRoleplayToolEnabled(RoleplayToolIds.WEATHER, false)
        setRoleplayToolEnabled(RoleplayToolIds.CALENDAR_SNAPSHOT, false)
      }

    val viewModel = RoleplaySettingsViewModel(repository)

    assertFalse(viewModel.uiState.value.allToolsEnabled)
    assertFalse(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.WEATHER }.enabled)
    assertFalse(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.CALENDAR_SNAPSHOT }.enabled)
  }

  @Test
  fun setRoleplayToolEnabled_updatesRepositoryAndState() {
    val repository = FakeDataStoreRepository()
    val viewModel = RoleplaySettingsViewModel(repository)

    viewModel.setRoleplayToolEnabled(RoleplayToolIds.WEATHER, false)

    assertFalse(viewModel.uiState.value.allToolsEnabled)
    assertFalse(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.WEATHER }.enabled)
    assertFalse(repository.isRoleplayToolEnabled(RoleplayToolIds.WEATHER))

    viewModel.setRoleplayToolEnabled(RoleplayToolIds.WEATHER, true)

    assertTrue(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.WEATHER }.enabled)
    assertTrue(repository.isRoleplayToolEnabled(RoleplayToolIds.WEATHER))
  }

  @Test
  fun setAllRoleplayToolsEnabled_updatesRepositoryAndState() {
    val repository = FakeDataStoreRepository()
    val viewModel = RoleplaySettingsViewModel(repository)

    viewModel.setAllRoleplayToolsEnabled(false)

    assertFalse(viewModel.uiState.value.allToolsEnabled)
    assertTrue(viewModel.uiState.value.toolStates.all { !it.enabled })
    assertEquals(RoleplayToolIds.all.toSet(), repository.getDisabledRoleplayToolIds())

    viewModel.setAllRoleplayToolsEnabled(true)

    assertTrue(viewModel.uiState.value.allToolsEnabled)
    assertTrue(viewModel.uiState.value.toolStates.all { it.enabled })
    assertTrue(repository.getDisabledRoleplayToolIds().isEmpty())
  }
}
