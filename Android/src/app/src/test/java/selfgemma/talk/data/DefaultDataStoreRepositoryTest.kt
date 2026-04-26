package selfgemma.talk.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import selfgemma.talk.BenchmarkResultsSerializer
import selfgemma.talk.CutoutsSerializer
import selfgemma.talk.SettingsSerializer
import selfgemma.talk.SkillsSerializer
import selfgemma.talk.UserDataSerializer
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.cloudllm.CloudProviderId
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolIds
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.proto.BenchmarkResults
import selfgemma.talk.proto.CutoutCollection
import selfgemma.talk.proto.Settings
import selfgemma.talk.proto.Skills
import selfgemma.talk.proto.UserData

class DefaultDataStoreRepositoryTest {
  @Test
  fun stUserProfile_roundTripsPersonaNameAndAvatarThroughProtoDataStore() {
    val tempDir = Files.createTempDirectory("datastore-repo-test").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settingsFile = File(tempDir, "settings.pb")
      val repository =
        DefaultDataStoreRepository(
          dataStore = createDataStore(settingsFile, SettingsSerializer, scope),
          userDataDataStore = createDataStore(File(tempDir, "user-data.pb"), UserDataSerializer, scope),
          cutoutDataStore = createDataStore(File(tempDir, "cutouts.pb"), CutoutsSerializer, scope),
          benchmarkResultsDataStore =
            createDataStore(File(tempDir, "benchmark-results.pb"), BenchmarkResultsSerializer, scope),
          skillsDataStore = createDataStore(File(tempDir, "skills.pb"), SkillsSerializer, scope),
        )

      val defaultAvatarId = StUserProfile().resolvedUserAvatarId()
      val expectedProfile =
        StUserProfile(
          userAvatarId = defaultAvatarId,
          defaultPersonaId = defaultAvatarId,
          personas = mapOf(defaultAvatarId to "纲手User"),
          personaDescriptions =
            mapOf(
              defaultAvatarId to
                StPersonaDescriptor(
                  description = "色情女郎",
                  avatarUri = "content://persona/avatar-1",
                ),
            ),
        ).ensureDefaults()

      repository.setStUserProfile(expectedProfile)

      val reloadedProfile = repository.getStUserProfile()
      val persistedSettings = settingsFile.inputStream().use(Settings::parseFrom)
      val persistedProfile = persistedSettings.stUserProfile
      val persistedDescriptor = persistedProfile.getPersonaDescriptionsOrThrow(defaultAvatarId)

      assertEquals(defaultAvatarId, reloadedProfile.userAvatarId)
      assertEquals(defaultAvatarId, reloadedProfile.defaultPersonaId)
      assertEquals("纲手User", reloadedProfile.personas[defaultAvatarId])
      assertEquals("content://persona/avatar-1", reloadedProfile.activeAvatarUri)
      assertEquals(defaultAvatarId, persistedProfile.defaultPersonaId)
      assertEquals("纲手User", persistedProfile.getPersonasOrThrow(defaultAvatarId))
      assertEquals("content://persona/avatar-1", persistedDescriptor.avatarUri)
      assertEquals("色情女郎", persistedDescriptor.description)
    } finally {
      scope.cancel()
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun roleplayToolConsentFlags_roundTripThroughProtoDataStore() {
    val tempDir = Files.createTempDirectory("datastore-repo-tool-flags").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settingsFile = File(tempDir, "settings.pb")
      val repository =
        DefaultDataStoreRepository(
          dataStore = createDataStore(settingsFile, SettingsSerializer, scope),
          userDataDataStore = createDataStore(File(tempDir, "user-data.pb"), UserDataSerializer, scope),
          cutoutDataStore = createDataStore(File(tempDir, "cutouts.pb"), CutoutsSerializer, scope),
          benchmarkResultsDataStore =
            createDataStore(File(tempDir, "benchmark-results.pb"), BenchmarkResultsSerializer, scope),
          skillsDataStore = createDataStore(File(tempDir, "skills.pb"), SkillsSerializer, scope),
        )

      repository.setRoleplayLocationToolsEnabled(true)
      repository.setRoleplayCalendarToolsEnabled(true)

      val persistedSettings = settingsFile.inputStream().use(Settings::parseFrom)

      assertEquals(true, repository.isRoleplayLocationToolsEnabled())
      assertEquals(true, repository.isRoleplayCalendarToolsEnabled())
      assertEquals(true, persistedSettings.roleplayLocationToolsEnabled)
      assertEquals(true, persistedSettings.roleplayCalendarToolsEnabled)
    } finally {
      scope.cancel()
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun cloudModelConfig_roundTripsThroughProtoDataStore() {
    val tempDir = Files.createTempDirectory("datastore-repo-cloud-model").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settingsFile = File(tempDir, "settings.pb")
      val repository =
        DefaultDataStoreRepository(
          dataStore = createDataStore(settingsFile, SettingsSerializer, scope),
          userDataDataStore = createDataStore(File(tempDir, "user-data.pb"), UserDataSerializer, scope),
          cutoutDataStore = createDataStore(File(tempDir, "cutouts.pb"), CutoutsSerializer, scope),
          benchmarkResultsDataStore =
            createDataStore(File(tempDir, "benchmark-results.pb"), BenchmarkResultsSerializer, scope),
          skillsDataStore = createDataStore(File(tempDir, "skills.pb"), SkillsSerializer, scope),
        )

      repository.setCloudModelConfig(
        CloudModelConfig(
          enabled = true,
          providerId = CloudProviderId.CLAUDE,
          modelName = " claude-sonnet-4-6 ",
          allowRawMediaUpload = true,
        )
      )

      val config = repository.getCloudModelConfig()
      val persistedSettings = settingsFile.inputStream().use(Settings::parseFrom)

      assertEquals(true, config.enabled)
      assertEquals(CloudProviderId.CLAUDE, config.providerId)
      assertEquals("claude-sonnet-4-6", config.modelName)
      assertEquals(true, config.allowRawMediaUpload)
      assertEquals("claude", persistedSettings.cloudLlmProviderId)
      assertEquals("claude-sonnet-4-6", persistedSettings.cloudLlmModelName)
    } finally {
      scope.cancel()
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun roleplayToolDisabledSet_defaultsToAllEnabledAndPersistsOverrides() {
    val tempDir = Files.createTempDirectory("datastore-repo-disabled-tools").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val settingsFile = File(tempDir, "settings.pb")
      val repository =
        DefaultDataStoreRepository(
          dataStore = createDataStore(settingsFile, SettingsSerializer, scope),
          userDataDataStore = createDataStore(File(tempDir, "user-data.pb"), UserDataSerializer, scope),
          cutoutDataStore = createDataStore(File(tempDir, "cutouts.pb"), CutoutsSerializer, scope),
          benchmarkResultsDataStore =
            createDataStore(File(tempDir, "benchmark-results.pb"), BenchmarkResultsSerializer, scope),
          skillsDataStore = createDataStore(File(tempDir, "skills.pb"), SkillsSerializer, scope),
        )

      assertEquals(true, repository.isRoleplayToolEnabled(RoleplayToolIds.WEATHER))

      repository.setRoleplayToolEnabled(RoleplayToolIds.WEATHER, false)
      repository.setRoleplayToolEnabled(RoleplayToolIds.CALENDAR_SNAPSHOT, false)

      val persistedSettings = settingsFile.inputStream().use(Settings::parseFrom)

      assertEquals(false, repository.isRoleplayToolEnabled(RoleplayToolIds.WEATHER))
      assertEquals(false, repository.isRoleplayToolEnabled(RoleplayToolIds.CALENDAR_SNAPSHOT))
      assertEquals(
        setOf(RoleplayToolIds.WEATHER, RoleplayToolIds.CALENDAR_SNAPSHOT),
        repository.getDisabledRoleplayToolIds(),
      )
      assertEquals(
        listOf(RoleplayToolIds.CALENDAR_SNAPSHOT, RoleplayToolIds.WEATHER),
        persistedSettings.roleplayDisabledToolIdList,
      )

      repository.setAllRoleplayToolsEnabled(RoleplayToolIds.all, true)

      assertEquals(true, repository.isRoleplayToolEnabled(RoleplayToolIds.WEATHER))
      assertEquals(emptySet<String>(), repository.getDisabledRoleplayToolIds())
    } finally {
      scope.cancel()
      tempDir.deleteRecursively()
    }
  }
}

private fun <T> createDataStore(
  file: File,
  serializer: androidx.datastore.core.Serializer<T>,
  scope: CoroutineScope,
): DataStore<T> {
  return DataStoreFactory.create(
    serializer = serializer,
    scope = scope,
    produceFile = { file },
  )
}
