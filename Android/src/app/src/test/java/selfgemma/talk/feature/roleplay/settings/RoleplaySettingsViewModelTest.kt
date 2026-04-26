package selfgemma.talk.feature.roleplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.data.cloudllm.CloudCredentialStore
import selfgemma.talk.data.cloudllm.CloudModelConfigRepository
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolIds
import selfgemma.talk.testing.FakeDataStoreRepository

class RoleplaySettingsViewModelTest {
  @Test
  fun init_defaultsAllRoleplayToolsToEnabled() {
    val viewModel = createViewModel()

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

    val viewModel = createViewModel(repository)

    assertFalse(viewModel.uiState.value.allToolsEnabled)
    assertFalse(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.WEATHER }.enabled)
    assertFalse(viewModel.uiState.value.toolStates.first { it.toolId == RoleplayToolIds.CALENDAR_SNAPSHOT }.enabled)
  }

  @Test
  fun setRoleplayToolEnabled_updatesRepositoryAndState() {
    val repository = FakeDataStoreRepository()
    val viewModel = createViewModel(repository)

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
    val viewModel = createViewModel(repository)

    viewModel.setAllRoleplayToolsEnabled(false)

    assertFalse(viewModel.uiState.value.allToolsEnabled)
    assertTrue(viewModel.uiState.value.toolStates.all { !it.enabled })
    assertEquals(RoleplayToolIds.all.toSet(), repository.getDisabledRoleplayToolIds())

    viewModel.setAllRoleplayToolsEnabled(true)

    assertTrue(viewModel.uiState.value.allToolsEnabled)
    assertTrue(viewModel.uiState.value.toolStates.all { it.enabled })
    assertTrue(repository.getDisabledRoleplayToolIds().isEmpty())
  }

  @Test
  fun saveCloudModelSettings_persistsConfigAndApiKey() {
    val repository = FakeDataStoreRepository()
    val credentialStore = InMemoryCloudCredentialStore()
    val viewModel = createViewModel(repository, credentialStore)
    val config =
      CloudModelConfig(
        enabled = true,
        providerId = CloudProviderId.DEEPSEEK,
        modelName = " deepseek-v4-flash ",
      )

    viewModel.saveCloudModelSettings(config, " sk-test ")

    val expectedConfig = config.copy(modelName = "deepseek-v4-flash")
    assertEquals(expectedConfig, repository.getCloudModelConfig())
    assertEquals(
      "sk-test",
      credentialStore.readSecret(CloudModelConfig.apiKeySecretName(CloudProviderId.DEEPSEEK)),
    )
    assertEquals(expectedConfig, viewModel.uiState.value.cloudModelConfig)
    assertTrue(viewModel.uiState.value.cloudApiKeySaved)
  }

  @Test
  fun saveCloudModelSettings_keepsSavedApiKeyWhenInputIsBlank() {
    val repository = FakeDataStoreRepository()
    val credentialStore = InMemoryCloudCredentialStore()
    val secretName = CloudModelConfig.apiKeySecretName(CloudProviderId.CLAUDE)
    credentialStore.saveSecret(secretName, "sk-existing")
    repository.setCloudModelConfig(
      CloudModelConfig(
        enabled = true,
        providerId = CloudProviderId.CLAUDE,
        modelName = "claude-sonnet-4-6",
      )
    )
    val viewModel = createViewModel(repository, credentialStore)

    viewModel.saveCloudModelSettings(repository.getCloudModelConfig().copy(modelName = "claude-opus-4-6"), "")

    assertEquals("sk-existing", credentialStore.readSecret(secretName))
    assertTrue(viewModel.uiState.value.cloudApiKeySaved)
  }

  @Test
  fun clearCloudApiKey_deletesSecretAndUpdatesState() {
    val repository = FakeDataStoreRepository()
    val credentialStore = InMemoryCloudCredentialStore()
    repository.setCloudModelConfig(
      CloudModelConfig(
        enabled = true,
        providerId = CloudProviderId.OPENROUTER,
        modelName = "openai/gpt-5.1",
      )
    )
    credentialStore.saveSecret(
      CloudModelConfig.apiKeySecretName(CloudProviderId.OPENROUTER),
      "sk-openrouter",
    )
    val viewModel = createViewModel(repository, credentialStore)

    viewModel.clearCloudApiKey(CloudProviderId.OPENROUTER)

    assertFalse(viewModel.uiState.value.cloudApiKeySaved)
    assertEquals(
      null,
      credentialStore.readSecret(CloudModelConfig.apiKeySecretName(CloudProviderId.OPENROUTER)),
    )
  }

  private fun createViewModel(
    repository: FakeDataStoreRepository = FakeDataStoreRepository(),
    credentialStore: CloudCredentialStore = InMemoryCloudCredentialStore(),
  ): RoleplaySettingsViewModel {
    return RoleplaySettingsViewModel(
      dataStoreRepository = repository,
      cloudModelConfigRepository = CloudModelConfigRepository(repository, credentialStore),
    )
  }

  private class InMemoryCloudCredentialStore : CloudCredentialStore {
    private val secrets = mutableMapOf<String, String>()

    override fun saveSecret(secretName: String, value: String) {
      secrets[secretName] = value
    }

    override fun readSecret(secretName: String): String? = secrets[secretName]

    override fun deleteSecret(secretName: String) {
      secrets.remove(secretName)
    }
  }
}
