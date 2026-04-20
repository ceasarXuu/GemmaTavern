package selfgemma.talk.feature.roleplay.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.DEFAULT_ST_USER_AVATAR_ID
import selfgemma.talk.domain.roleplay.model.DEFAULT_ST_USER_NAME
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.availablePersonaSlotIds

private const val TAG = "MyProfileViewModel"

data class PersonaSlotCardUiState(
  val slotId: String,
  val personaName: String,
  val personaTitle: String,
  val personaDescription: String,
  val avatarUri: String? = null,
  val avatarEditorSourceUri: String? = null,
  val avatarCropZoom: Float = 1f,
  val avatarCropOffsetX: Float = 0f,
  val avatarCropOffsetY: Float = 0f,
  val isDefault: Boolean = false,
  val isSelected: Boolean = false,
)

data class MyProfileUiState(
  val personaCards: List<PersonaSlotCardUiState> = emptyList(),
  val avatarUri: String? = null,
  val avatarEditorSourceUri: String? = null,
  val avatarCropZoom: Float = 1f,
  val avatarCropOffsetX: Float = 0f,
  val avatarCropOffsetY: Float = 0f,
  val personaName: String = "",
  val personaTitle: String = "",
  val personaDescription: String = "",
  val personaPosition: StPersonaDescriptionPosition = StPersonaDescriptionPosition.IN_PROMPT,
  val personaDepth: String = "2",
  val personaRole: Int = 0,
  val avatarSlotId: String = "",
  val dirty: Boolean = false,
)

@HiltViewModel
class MyProfileViewModel
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  private var savedProfile: StUserProfile = dataStoreRepository.getStUserProfile().ensureDefaults()
  private var workingProfile: StUserProfile = savedProfile
  private val _uiState = MutableStateFlow(workingProfile.toUiState(savedProfile))
  val uiState: StateFlow<MyProfileUiState> = _uiState.asStateFlow()

  init {
    debugProfile("loaded ST user persona profile", savedProfile)
  }

  fun updatePersonaName(value: String) {
    updateUiState { it.copy(personaName = value) }
  }

  fun updatePersonaTitle(value: String) {
    updateUiState { it.copy(personaTitle = value) }
  }

  fun updatePersonaDescription(value: String) {
    updateUiState { it.copy(personaDescription = value) }
  }

  fun updatePersonaPosition(value: StPersonaDescriptionPosition) {
    updateUiState { it.copy(personaPosition = value) }
  }

  fun updatePersonaDepth(value: String) {
    updateUiState { it.copy(personaDepth = value) }
  }

  fun updatePersonaRole(value: Int) {
    updateUiState { it.copy(personaRole = value) }
  }

  fun updateAvatarUri(value: String?) {
    updateUiState { it.copy(avatarUri = value?.takeIf(String::isNotBlank)) }
  }

  fun updateAvatarEditState(
    avatarUri: String?,
    avatarEditorSourceUri: String?,
    avatarCropZoom: Float,
    avatarCropOffsetX: Float,
    avatarCropOffsetY: Float,
  ) {
    updateUiState {
      it.copy(
        avatarUri = avatarUri?.takeIf(String::isNotBlank),
        avatarEditorSourceUri = avatarEditorSourceUri?.takeIf(String::isNotBlank),
        avatarCropZoom = avatarCropZoom,
        avatarCropOffsetX = avatarCropOffsetX,
        avatarCropOffsetY = avatarCropOffsetY,
      )
    }
  }

  fun selectAvatarSlot(slotId: String) {
    val normalizedSlotId = slotId.trim()
    if (normalizedSlotId.isBlank()) {
      return
    }
    workingProfile =
      buildProfileFromUiState(_uiState.value)
        .ensureSlot(normalizedSlotId)
        .copy(userAvatarId = normalizedSlotId)
        .ensureDefaults()
    _uiState.value = workingProfile.toUiState(savedProfile)
    debugLog("selected persona slot avatarId=$normalizedSlotId dirty=${_uiState.value.dirty}")
  }

  fun createAvatarSlot(slotId: String) {
    val normalizedSlotId = slotId.trim()
    if (normalizedSlotId.isBlank()) {
      return
    }
    workingProfile =
      buildProfileFromUiState(_uiState.value)
        .ensureSlot(normalizedSlotId)
        .copy(userAvatarId = normalizedSlotId)
        .ensureDefaults()
    _uiState.value = workingProfile.toUiState(savedProfile)
    debugLog("created persona slot avatarId=$normalizedSlotId totalSlots=${_uiState.value.personaCards.size}")
  }

  fun saveProfile() {
    val updatedProfile = buildProfileFromUiState(_uiState.value).ensureDefaults()
    dataStoreRepository.setStUserProfile(updatedProfile)
    val persistedProfile = dataStoreRepository.getStUserProfile().ensureDefaults()
    savedProfile = persistedProfile
    workingProfile = persistedProfile
    _uiState.value = persistedProfile.toUiState(savedProfile)
    debugProfile("saved ST user persona profile", persistedProfile)
  }

  fun setDefaultPersona(
    slotId: String,
    enabled: Boolean,
  ) {
    val normalizedSlotId = slotId.trim()
    if (normalizedSlotId.isBlank()) {
      return
    }
    val nextSavedProfile =
      savedProfile
        .ensureSlot(normalizedSlotId)
        .copy(
          defaultPersonaId =
            when {
              enabled -> normalizedSlotId
              savedProfile.defaultPersonaId == normalizedSlotId -> null
              else -> savedProfile.defaultPersonaId
            },
        ).ensureDefaults()
    val nextWorkingProfile =
      workingProfile
        .ensureSlot(normalizedSlotId)
        .copy(
          defaultPersonaId =
            when {
              enabled -> normalizedSlotId
              workingProfile.defaultPersonaId == normalizedSlotId -> null
              else -> workingProfile.defaultPersonaId
            },
        ).ensureDefaults()
    dataStoreRepository.setStUserProfile(nextSavedProfile)
    savedProfile = nextSavedProfile
    workingProfile = nextWorkingProfile
    _uiState.value = workingProfile.toUiState(savedProfile)
    debugLog("set default persona avatarId=$normalizedSlotId enabled=$enabled dirty=${_uiState.value.dirty}")
  }

  fun deleteAvatarSlot(slotId: String) {
    val normalizedSlotId = slotId.trim()
    if (normalizedSlotId.isBlank()) {
      return
    }

    val draftProfile = buildProfileFromUiState(_uiState.value)
    val nextWorkingProfile = draftProfile.removeSlot(normalizedSlotId)
    val nextSavedProfile = savedProfile.removeSlot(normalizedSlotId)

    dataStoreRepository.setStUserProfile(nextSavedProfile)
    savedProfile = nextSavedProfile
    workingProfile = nextWorkingProfile
    _uiState.value = workingProfile.toUiState(savedProfile)
    debugLog(
      "deleted persona slot avatarId=$normalizedSlotId active=${_uiState.value.avatarSlotId} totalSlots=${_uiState.value.personaCards.size}",
    )
  }

  fun resetProfile() {
    val defaultProfile = StUserProfile().ensureDefaults()
    dataStoreRepository.setStUserProfile(defaultProfile)
    savedProfile = defaultProfile
    workingProfile = defaultProfile
    _uiState.value = defaultProfile.toUiState(savedProfile)
    debugLog("reset ST user persona profile to defaults")
  }

  private fun updateUiState(transform: (MyProfileUiState) -> MyProfileUiState) {
    val nextState = transform(_uiState.value)
    workingProfile = buildProfileFromUiState(nextState)
    _uiState.value = workingProfile.toUiState(savedProfile)
  }

  private fun buildProfileFromUiState(state: MyProfileUiState): StUserProfile {
    val activeSlotId = state.avatarSlotId.ifBlank { workingProfile.resolvedUserAvatarId() }
    val baseProfile = workingProfile.ensureSlot(activeSlotId)
    val depthFallback = baseProfile.personaDescriptions[activeSlotId]?.depth ?: baseProfile.personaDescriptionDepth
    return baseProfile
      .copy(
        userAvatarId = activeSlotId,
      )
      .withActivePersona(
        name = state.personaName.trim(),
        title = state.personaTitle.trim(),
        description = state.personaDescription.trim(),
        position = state.personaPosition,
        depth = state.personaDepth.toIntOrNull()?.coerceAtLeast(0) ?: depthFallback,
        role = state.personaRole,
        lorebook = baseProfile.personaDescriptionLorebook,
        avatarUri = state.avatarUri?.takeIf(String::isNotBlank),
        avatarEditorSourceUri = state.avatarEditorSourceUri?.takeIf(String::isNotBlank),
        avatarCropZoom = state.avatarCropZoom,
        avatarCropOffsetX = state.avatarCropOffsetX,
        avatarCropOffsetY = state.avatarCropOffsetY,
      )
      .ensureDefaults()
  }
}

private fun debugLog(message: String) {
  runCatching { Log.d(TAG, message) }
}

private fun debugProfile(
  prefix: String,
  profile: StUserProfile,
) {
  debugLog(
    "$prefix avatarId=${profile.resolvedUserAvatarId()} default=${profile.defaultPersonaId} name=${profile.userName} avatarUri=${profile.activeAvatarUri} avatarSourceUri=${profile.activeAvatarEditorSourceUri} cropZoom=${profile.activeAvatarCropZoom} cropOffsetX=${profile.activeAvatarCropOffsetX} cropOffsetY=${profile.activeAvatarCropOffsetY} slots=${profile.availablePersonaSlotIds()} personas=${profile.personas} personaAvatarUris=${profile.personaDescriptions.mapValues { (_, descriptor) -> descriptor.avatarUri }} position=${profile.personaDescriptionPosition.rawValue}",
  )
}

private fun StUserProfile.toUiState(savedProfile: StUserProfile): MyProfileUiState {
  val activeSlotId = resolvedUserAvatarId()
  return MyProfileUiState(
    personaCards = personaCards(activeSlotId),
    avatarUri = activeAvatarUri,
    avatarEditorSourceUri = activeAvatarEditorSourceUri,
    avatarCropZoom = activeAvatarCropZoom,
    avatarCropOffsetX = activeAvatarCropOffsetX,
    avatarCropOffsetY = activeAvatarCropOffsetY,
    personaName = userName,
    personaTitle = personaTitle,
    personaDescription = personaDescription,
    personaPosition = personaDescriptionPosition,
    personaDepth = personaDescriptionDepth.toString(),
    personaRole = personaDescriptionRole,
    avatarSlotId = activeSlotId,
    dirty = this != savedProfile,
  )
}

private fun StUserProfile.personaCards(activeSlotId: String): List<PersonaSlotCardUiState> {
  return availableSlotIds(activeSlotId)
    .map { slotId ->
      val descriptor = personaDescriptions[slotId] ?: StPersonaDescriptor()
      PersonaSlotCardUiState(
        slotId = slotId,
        personaName = personas[slotId].orEmpty().ifBlank { DEFAULT_ST_USER_NAME },
        personaTitle = descriptor.title,
        personaDescription = descriptor.description,
        avatarUri = descriptor.avatarUri,
        avatarEditorSourceUri = descriptor.avatarEditorSourceUri?.takeIf { it.isNotBlank() } ?: descriptor.avatarUri,
        avatarCropZoom = descriptor.avatarCropZoom,
        avatarCropOffsetX = descriptor.avatarCropOffsetX,
        avatarCropOffsetY = descriptor.avatarCropOffsetY,
        isDefault = defaultPersonaId == slotId,
        isSelected = activeSlotId == slotId,
      )
    }.sortedWith(
      compareByDescending<PersonaSlotCardUiState> { it.isSelected }
        .thenByDescending { it.isDefault }
        .thenBy { it.personaName.lowercase() }
        .thenBy { it.slotId },
    )
}

private fun StUserProfile.availableSlotIds(activeSlotId: String): List<String> {
  return buildSet {
    add(activeSlotId)
    addAll(personas.keys)
    addAll(personaDescriptions.keys)
  }.filter { it.isNotBlank() }
}

private fun StUserProfile.ensureSlot(slotId: String): StUserProfile {
  val normalizedSlotId = slotId.trim()
  if (normalizedSlotId.isBlank()) {
    return ensureDefaults()
  }
  return copy(
    personas =
      personas.toMutableMap().apply {
        putIfAbsent(normalizedSlotId, DEFAULT_ST_USER_NAME)
      },
    personaDescriptions =
      personaDescriptions.toMutableMap().apply {
        putIfAbsent(normalizedSlotId, StPersonaDescriptor())
      },
  ).ensureDefaults()
}

private fun StUserProfile.removeSlot(slotId: String): StUserProfile {
  val normalizedSlotId = slotId.trim()
  if (normalizedSlotId.isBlank()) {
    return ensureDefaults()
  }

  val updatedPersonas = personas.toMutableMap().apply { remove(normalizedSlotId) }
  val updatedDescriptors = personaDescriptions.toMutableMap().apply { remove(normalizedSlotId) }
  val remainingSlotIds = buildSet {
    addAll(updatedPersonas.keys)
    addAll(updatedDescriptors.keys)
  }.filter { it.isNotBlank() }

  val fallbackSlotId =
    when {
      remainingSlotIds.contains(userAvatarId) && userAvatarId != normalizedSlotId -> userAvatarId
      defaultPersonaId != null && defaultPersonaId != normalizedSlotId && remainingSlotIds.contains(defaultPersonaId) -> defaultPersonaId
      remainingSlotIds.isNotEmpty() -> remainingSlotIds.sorted().first()
      else -> DEFAULT_ST_USER_AVATAR_ID
    }

  return copy(
    userAvatarId = fallbackSlotId,
    defaultPersonaId =
      when {
        defaultPersonaId == normalizedSlotId -> null
        defaultPersonaId != null && remainingSlotIds.contains(defaultPersonaId) -> defaultPersonaId
        else -> null
      },
    personas = updatedPersonas,
    personaDescriptions = updatedDescriptors,
  ).ensureSlot(fallbackSlotId)
}
