package selfgemma.talk.domain.roleplay.model

const val DEFAULT_ST_USER_AVATAR_ID = "user-default.png"
const val DEFAULT_ST_USER_NAME = "User"
const val DEFAULT_ST_PERSONA_DEPTH = 2
const val DEFAULT_ST_PERSONA_ROLE = 0

enum class StPersonaDescriptionPosition(val rawValue: Int) {
  IN_PROMPT(0),
  AFTER_CHAR(1),
  TOP_AN(2),
  BOTTOM_AN(3),
  AT_DEPTH(4),
  NONE(9),
  ;

  companion object {
    fun fromRawValue(rawValue: Int): StPersonaDescriptionPosition {
      return entries.firstOrNull { it.rawValue == rawValue } ?: IN_PROMPT
    }
  }
}

data class StPersonaConnection(
  val type: String,
  val id: String,
)

data class StPersonaDescriptor(
  val description: String = "",
  val title: String = "",
  val position: StPersonaDescriptionPosition = StPersonaDescriptionPosition.IN_PROMPT,
  val depth: Int = DEFAULT_ST_PERSONA_DEPTH,
  val role: Int = DEFAULT_ST_PERSONA_ROLE,
  val lorebook: String = "",
  val connections: List<StPersonaConnection> = emptyList(),
  val avatarUri: String? = null,
  val avatarEditorSourceUri: String? = null,
  val avatarCropZoom: Float = 1f,
  val avatarCropOffsetX: Float = 0f,
  val avatarCropOffsetY: Float = 0f,
)

data class StUserProfile(
  val userAvatarId: String = DEFAULT_ST_USER_AVATAR_ID,
  val defaultPersonaId: String? = null,
  val personas: Map<String, String> = mapOf(DEFAULT_ST_USER_AVATAR_ID to DEFAULT_ST_USER_NAME),
  val personaDescriptions: Map<String, StPersonaDescriptor> =
    mapOf(DEFAULT_ST_USER_AVATAR_ID to StPersonaDescriptor()),
) {
  val userName: String
    get() = personas[resolvedUserAvatarId()].orEmpty().ifBlank { DEFAULT_ST_USER_NAME }

  val personaDescription: String
    get() = activePersonaDescriptor().description

  val personaTitle: String
    get() = activePersonaDescriptor().title

  val personaDescriptionPosition: StPersonaDescriptionPosition
    get() = activePersonaDescriptor().position

  val personaDescriptionDepth: Int
    get() = activePersonaDescriptor().depth

  val personaDescriptionRole: Int
    get() = activePersonaDescriptor().role

  val personaDescriptionLorebook: String
    get() = activePersonaDescriptor().lorebook

  val activeAvatarUri: String?
    get() = activePersonaDescriptor().avatarUri

  val activeAvatarEditorSourceUri: String?
    get() = activePersonaDescriptor().avatarEditorSourceUri?.takeIf { it.isNotBlank() } ?: activeAvatarUri

  val activeAvatarCropZoom: Float
    get() = activePersonaDescriptor().avatarCropZoom

  val activeAvatarCropOffsetX: Float
    get() = activePersonaDescriptor().avatarCropOffsetX

  val activeAvatarCropOffsetY: Float
    get() = activePersonaDescriptor().avatarCropOffsetY

  fun activePersonaDescriptor(): StPersonaDescriptor {
    return personaDescriptions[resolvedUserAvatarId()] ?: StPersonaDescriptor()
  }

  fun resolvedUserAvatarId(): String = userAvatarId.ifBlank { DEFAULT_ST_USER_AVATAR_ID }

  fun ensureDefaults(): StUserProfile {
    val resolvedAvatarId = resolvedUserAvatarId()
    val resolvedPersonas =
      personas.toMutableMap().apply {
        val currentName = this[resolvedAvatarId].orEmpty()
        this[resolvedAvatarId] = currentName.ifBlank { DEFAULT_ST_USER_NAME }
      }
    val resolvedDescriptors =
      personaDescriptions.toMutableMap().apply {
        putIfAbsent(resolvedAvatarId, StPersonaDescriptor())
      }
    val resolvedDefaultPersonaId = defaultPersonaId?.ifBlank { null }
    return copy(
      userAvatarId = resolvedAvatarId,
      defaultPersonaId = resolvedDefaultPersonaId,
      personas = resolvedPersonas.toMap(),
      personaDescriptions = resolvedDescriptors.toMap(),
    )
  }

  fun withActivePersona(
    name: String = userName,
    title: String = personaTitle,
    description: String = personaDescription,
    position: StPersonaDescriptionPosition = personaDescriptionPosition,
    depth: Int = personaDescriptionDepth,
    role: Int = personaDescriptionRole,
    lorebook: String = personaDescriptionLorebook,
    avatarUri: String? = activeAvatarUri,
    avatarEditorSourceUri: String? = activeAvatarEditorSourceUri,
    avatarCropZoom: Float = activeAvatarCropZoom,
    avatarCropOffsetX: Float = activeAvatarCropOffsetX,
    avatarCropOffsetY: Float = activeAvatarCropOffsetY,
  ): StUserProfile {
    val resolvedAvatarId = resolvedUserAvatarId()
    val currentDescriptor = activePersonaDescriptor()
    return ensureDefaults().copy(
      personas = personas.toMutableMap().apply { this[resolvedAvatarId] = name.ifBlank { DEFAULT_ST_USER_NAME } },
      personaDescriptions =
        personaDescriptions
          .toMutableMap()
          .apply {
            this[resolvedAvatarId] =
              currentDescriptor.copy(
                description = description,
                title = title,
                position = position,
                depth = depth,
                role = role,
                lorebook = lorebook,
                avatarUri = avatarUri,
                avatarEditorSourceUri = avatarEditorSourceUri?.takeIf { it.isNotBlank() },
                avatarCropZoom = avatarCropZoom,
                avatarCropOffsetX = avatarCropOffsetX,
                avatarCropOffsetY = avatarCropOffsetY,
              )
          },
    ).ensureDefaults()
  }
}

fun StUserProfile.availablePersonaSlotIds(): List<String> {
  return buildSet {
    add(resolvedUserAvatarId())
    addAll(personas.keys)
    addAll(personaDescriptions.keys)
  }.filter { it.isNotBlank() }
}

fun StUserProfile.resolvedPersonaSlotId(preferredSlotId: String? = null): String {
  val availableSlots = availablePersonaSlotIds()
  val normalizedPreferredSlotId = preferredSlotId?.trim().orEmpty()
  return when {
    normalizedPreferredSlotId.isNotBlank() && normalizedPreferredSlotId in availableSlots -> normalizedPreferredSlotId
    !defaultPersonaId.isNullOrBlank() && defaultPersonaId in availableSlots -> defaultPersonaId
    resolvedUserAvatarId() in availableSlots -> resolvedUserAvatarId()
    availableSlots.isNotEmpty() -> availableSlots.sorted().first()
    else -> DEFAULT_ST_USER_AVATAR_ID
  }
}

fun StUserProfile.selectPersonaSlot(slotId: String?): StUserProfile {
  val normalizedProfile = ensureDefaults()
  val resolvedSlotId = normalizedProfile.resolvedPersonaSlotId(slotId)
  return normalizedProfile.copy(userAvatarId = resolvedSlotId).ensureDefaults()
}

fun StUserProfile.snapshotSelectedPersona(slotId: String? = null): StUserProfile {
  val selectedProfile = selectPersonaSlot(slotId)
  val selectedSlotId = selectedProfile.resolvedUserAvatarId()
  return StUserProfile(
    userAvatarId = selectedSlotId,
    defaultPersonaId = selectedSlotId,
    personas = mapOf(selectedSlotId to selectedProfile.userName),
    personaDescriptions = mapOf(selectedSlotId to selectedProfile.activePersonaDescriptor()),
  ).ensureDefaults()
}

fun StUserProfile.personaDescriptionInPrompt(): String {
  return if (personaDescriptionPosition == StPersonaDescriptionPosition.IN_PROMPT) {
    personaDescription.trim()
  } else {
    ""
  }
}

fun StUserProfile.personaDescriptionForAuthorNote(): String? {
  return when (personaDescriptionPosition) {
    StPersonaDescriptionPosition.TOP_AN,
    StPersonaDescriptionPosition.BOTTOM_AN -> personaDescription.trim().ifBlank { null }
    else -> null
  }
}

fun StUserProfile.personaDescriptionForDepthPrompt(): String? {
  return if (personaDescriptionPosition == StPersonaDescriptionPosition.AT_DEPTH) {
    personaDescription.trim().ifBlank { null }
  } else {
    null
  }
}
