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
import selfgemma.talk.domain.roleplay.model.DEFAULT_ST_USER_AVATAR_ID
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

      val expectedProfile =
        StUserProfile(
          userAvatarId = DEFAULT_ST_USER_AVATAR_ID,
          defaultPersonaId = DEFAULT_ST_USER_AVATAR_ID,
          personas = mapOf(DEFAULT_ST_USER_AVATAR_ID to "纲手User"),
          personaDescriptions =
            mapOf(
              DEFAULT_ST_USER_AVATAR_ID to
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
      val persistedDescriptor = persistedProfile.getPersonaDescriptionsOrThrow(DEFAULT_ST_USER_AVATAR_ID)

      assertEquals(DEFAULT_ST_USER_AVATAR_ID, reloadedProfile.userAvatarId)
      assertEquals(DEFAULT_ST_USER_AVATAR_ID, reloadedProfile.defaultPersonaId)
      assertEquals("纲手User", reloadedProfile.personas[DEFAULT_ST_USER_AVATAR_ID])
      assertEquals("content://persona/avatar-1", reloadedProfile.activeAvatarUri)
      assertEquals(DEFAULT_ST_USER_AVATAR_ID, persistedProfile.defaultPersonaId)
      assertEquals("纲手User", persistedProfile.getPersonasOrThrow(DEFAULT_ST_USER_AVATAR_ID))
      assertEquals("content://persona/avatar-1", persistedDescriptor.avatarUri)
      assertEquals("色情女郎", persistedDescriptor.description)
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
