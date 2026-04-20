package selfgemma.talk.feature.roleplay.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import selfgemma.talk.domain.roleplay.model.DEFAULT_ST_USER_NAME
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.testing.FakeDataStoreRepository

class MyProfileViewModelTest {
  @Test
  fun switchingSlots_preservesUnsavedPersonaEdits() {
    val viewModel =
      MyProfileViewModel(
        FakeDataStoreRepository(
          stUserProfile =
            StUserProfile(
              userAvatarId = "slot-a",
              defaultPersonaId = "slot-a",
              personas = mapOf("slot-a" to "Alice"),
              personaDescriptions =
                mapOf(
                  "slot-a" to StPersonaDescriptor(description = "old description"),
                ),
            ).ensureDefaults(),
        ),
      )

    viewModel.updatePersonaName("Alice Updated")
    viewModel.updatePersonaDescription("new description")
    viewModel.createAvatarSlot("slot-b")

    val slotBState = viewModel.uiState.value
    assertEquals("slot-b", slotBState.avatarSlotId)
    assertEquals(DEFAULT_ST_USER_NAME, slotBState.personaName)
    assertTrue(slotBState.personaCards.any { it.slotId == "slot-a" })
    assertTrue(slotBState.personaCards.any { it.slotId == "slot-b" })

    viewModel.selectAvatarSlot("slot-a")

    val slotAState = viewModel.uiState.value
    assertEquals("slot-a", slotAState.avatarSlotId)
    assertEquals("Alice Updated", slotAState.personaName)
    assertEquals("new description", slotAState.personaDescription)
    assertTrue(slotAState.dirty)
  }

  @Test
  fun saveProfile_persistsNewSlotAndDefaultPersona() {
    val dataStoreRepository =
      FakeDataStoreRepository(
        stUserProfile =
          StUserProfile(
            userAvatarId = "slot-a",
            defaultPersonaId = "slot-a",
            personas = mapOf("slot-a" to "Alice"),
            personaDescriptions =
              mapOf(
                "slot-a" to StPersonaDescriptor(description = "old description"),
              ),
          ).ensureDefaults(),
      )
    val viewModel = MyProfileViewModel(dataStoreRepository)

    viewModel.createAvatarSlot("slot-b")
    viewModel.updatePersonaName("Bob")
    viewModel.updatePersonaDescription("traveler")
    viewModel.setDefaultPersona("slot-b", true)
    viewModel.saveProfile()

    val savedProfile = dataStoreRepository.getStUserProfile()
    assertEquals("slot-b", savedProfile.userAvatarId)
    assertEquals("slot-b", savedProfile.defaultPersonaId)
    assertEquals("Bob", savedProfile.personas["slot-b"])
    assertEquals("traveler", savedProfile.personaDescriptions["slot-b"]?.description)
    assertEquals("Alice", savedProfile.personas["slot-a"])
  }

  @Test
  fun saveProfile_roundTripsPersonaNameAndAvatarAfterReload() {
    val dataStoreRepository =
      FakeDataStoreRepository(
        stUserProfile =
          StUserProfile(
            userAvatarId = "slot-a",
            defaultPersonaId = "slot-a",
            personas =
              mapOf(
                "slot-a" to "Alice",
                "slot-b" to "Bob",
              ),
            personaDescriptions =
              mapOf(
                "slot-a" to StPersonaDescriptor(description = "alpha"),
                "slot-b" to StPersonaDescriptor(description = "beta"),
              ),
          ).ensureDefaults(),
      )
    val viewModel = MyProfileViewModel(dataStoreRepository)

    viewModel.selectAvatarSlot("slot-b")
    viewModel.updatePersonaName("Bob Reloaded")
    viewModel.updateAvatarEditState(
      avatarUri = "content://persona/avatar-b",
      avatarEditorSourceUri = "content://persona/source-b",
      avatarCropZoom = 1.8f,
      avatarCropOffsetX = 0.12f,
      avatarCropOffsetY = -0.08f,
    )
    viewModel.saveProfile()

    val reloadedViewModel = MyProfileViewModel(dataStoreRepository)
    assertEquals("slot-b", reloadedViewModel.uiState.value.avatarSlotId)
    assertEquals("Bob Reloaded", reloadedViewModel.uiState.value.personaName)
    assertEquals("content://persona/avatar-b", reloadedViewModel.uiState.value.avatarUri)
    assertEquals("content://persona/source-b", reloadedViewModel.uiState.value.avatarEditorSourceUri)
    assertEquals(1.8f, reloadedViewModel.uiState.value.avatarCropZoom, 0.001f)
    assertEquals(0.12f, reloadedViewModel.uiState.value.avatarCropOffsetX, 0.001f)
    assertEquals(-0.08f, reloadedViewModel.uiState.value.avatarCropOffsetY, 0.001f)

    reloadedViewModel.selectAvatarSlot("slot-b")
    val reloadedCard = reloadedViewModel.uiState.value.personaCards.first { it.slotId == "slot-b" }
    assertEquals("Bob Reloaded", reloadedCard.personaName)
    assertEquals("content://persona/avatar-b", reloadedCard.avatarUri)
    assertEquals("content://persona/source-b", reloadedCard.avatarEditorSourceUri)
  }

  @Test
  fun updatingPersonaFields_refreshesCardSummariesFromDraftProfile() {
    val viewModel =
      MyProfileViewModel(
        FakeDataStoreRepository(
          stUserProfile =
            StUserProfile(
              userAvatarId = "slot-a",
              personas = mapOf("slot-a" to "Alice"),
              personaDescriptions = mapOf("slot-a" to StPersonaDescriptor()),
            ).ensureDefaults(),
        ),
      )

    viewModel.updatePersonaName("Alice Draft")
    viewModel.updatePersonaTitle("Captain")
    viewModel.updatePersonaDescription("keeps the crew calm")
    viewModel.updateAvatarUri("content://persona/avatar-a")

    val updatedCard = viewModel.uiState.value.personaCards.first { it.slotId == "slot-a" }
    assertEquals("Alice Draft", updatedCard.personaName)
    assertEquals("Captain", updatedCard.personaTitle)
    assertEquals("keeps the crew calm", updatedCard.personaDescription)
    assertEquals("content://persona/avatar-a", updatedCard.avatarUri)
    assertEquals("content://persona/avatar-a", viewModel.uiState.value.avatarUri)
  }

  @Test
  fun settingDefaultPersona_isMutuallyExclusiveAndDoesNotPersistUnsavedDraftFields() {
    val dataStoreRepository =
      FakeDataStoreRepository(
        stUserProfile =
          StUserProfile(
            userAvatarId = "slot-a",
            defaultPersonaId = "slot-a",
            personas = mapOf("slot-a" to "Alice"),
            personaDescriptions =
              mapOf(
                "slot-a" to StPersonaDescriptor(description = "old description"),
              ),
          ).ensureDefaults(),
      )
    val viewModel = MyProfileViewModel(dataStoreRepository)

    viewModel.updatePersonaDescription("draft description")
    viewModel.createAvatarSlot("slot-b")
    viewModel.updatePersonaName("Bob Draft")
    viewModel.setDefaultPersona("slot-b", true)

    val savedProfile = dataStoreRepository.getStUserProfile()
    assertEquals("slot-b", savedProfile.defaultPersonaId)
    assertEquals("old description", savedProfile.personaDescriptions["slot-a"]?.description)
    assertEquals(DEFAULT_ST_USER_NAME, savedProfile.personas["slot-b"])

    val slotACard = viewModel.uiState.value.personaCards.first { it.slotId == "slot-a" }
    val slotBCard = viewModel.uiState.value.personaCards.first { it.slotId == "slot-b" }
    assertEquals("draft description", slotACard.personaDescription)
    assertEquals("Bob Draft", slotBCard.personaName)
    assertTrue(!slotACard.isDefault)
    assertTrue(slotBCard.isDefault)
    assertTrue(viewModel.uiState.value.dirty)
  }

  @Test
  fun deletingActiveDefaultPersona_removesSlotAndFallsBackToRemainingPersona() {
    val dataStoreRepository =
      FakeDataStoreRepository(
        stUserProfile =
          StUserProfile(
            userAvatarId = "slot-a",
            defaultPersonaId = "slot-a",
            personas =
              mapOf(
                "slot-a" to "Alice",
                "slot-b" to "Bob",
              ),
            personaDescriptions =
              mapOf(
                "slot-a" to StPersonaDescriptor(description = "alpha"),
                "slot-b" to StPersonaDescriptor(description = "beta"),
              ),
          ).ensureDefaults(),
      )
    val viewModel = MyProfileViewModel(dataStoreRepository)

    viewModel.deleteAvatarSlot("slot-a")

    val savedProfile = dataStoreRepository.getStUserProfile()
    assertEquals("slot-b", savedProfile.userAvatarId)
    assertEquals(null, savedProfile.defaultPersonaId)
    assertEquals(null, savedProfile.personas["slot-a"])
    assertEquals("Bob", savedProfile.personas["slot-b"])
    assertEquals("slot-b", viewModel.uiState.value.avatarSlotId)
    assertEquals(1, viewModel.uiState.value.personaCards.size)
    assertEquals("slot-b", viewModel.uiState.value.personaCards.single().slotId)
  }
}
