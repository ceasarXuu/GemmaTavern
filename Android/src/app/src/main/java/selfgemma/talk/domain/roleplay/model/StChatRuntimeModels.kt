package selfgemma.talk.domain.roleplay.model

private const val DEFAULT_ST_RUNTIME_TRIGGER = "normal"

data class StChatRuntimeRole(
  val card: StCharacterCard,
  val userProfile: StUserProfile = StUserProfile(),
  val safetyPolicy: String = "",
) {
  val userName: String
    get() = userProfile.userName
}

data class StChatRuntimeSession(
  val chatMetadataJson: String? = null,
  val generationTrigger: String = DEFAULT_ST_RUNTIME_TRIGGER,
)

fun RoleCard.toStChatRuntimeRole(userProfile: StUserProfile = StUserProfile()): StChatRuntimeRole {
  return StChatRuntimeRole(
    card = stCard,
    userProfile = userProfile.ensureDefaults(),
    safetyPolicy = safetyPolicy,
  )
}

fun RoleCard.toStChatRuntimeRole(userName: String): StChatRuntimeRole {
  return toStChatRuntimeRole(userProfile = StUserProfile().withActivePersona(name = userName))
}

fun Session.toStChatRuntimeSession(generationTrigger: String = DEFAULT_ST_RUNTIME_TRIGGER): StChatRuntimeSession {
  return StChatRuntimeSession(
    chatMetadataJson = interopChatMetadataJson,
    generationTrigger = generationTrigger,
  )
}

fun StChatRuntimeRole.name(): String = card.resolvedName()

fun StChatRuntimeRole.summary(): String = card.resolvedDescription()

fun StChatRuntimeRole.systemPrompt(): String = card.resolvedSystemPrompt()

fun StChatRuntimeRole.personaDescription(): String = card.resolvedPersonality()

fun StChatRuntimeRole.userPersonaDescription(): String = userProfile.personaDescription

fun StChatRuntimeRole.worldSettings(): String = card.resolvedScenario()

fun StChatRuntimeRole.openingMessage(): String = card.resolvedFirstMessage()

fun StChatRuntimeRole.exampleDialoguesRaw(): String = card.resolvedMessageExample()

fun StChatRuntimeRole.tags(): List<String> = card.resolvedTags()

fun StChatRuntimeRole.cardData(): StCharacterCardData = card.cardDataOrEmpty()
