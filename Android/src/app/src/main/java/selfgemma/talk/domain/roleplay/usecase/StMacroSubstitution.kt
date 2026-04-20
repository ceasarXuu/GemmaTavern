package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.StChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.model.cardData
import selfgemma.talk.domain.roleplay.model.exampleDialoguesRaw
import selfgemma.talk.domain.roleplay.model.name
import selfgemma.talk.domain.roleplay.model.personaDescriptionInPrompt
import selfgemma.talk.domain.roleplay.model.personaDescription
import selfgemma.talk.domain.roleplay.model.summary
import selfgemma.talk.domain.roleplay.model.systemPrompt
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.userPersonaDescription
import selfgemma.talk.domain.roleplay.model.worldSettings

private const val ST_MACRO_MAX_PASSES = 4
private val LEGACY_ST_NAME_MACROS =
  linkedMapOf(
    "<USER>" to "{{user}}",
    "<BOT>" to "{{char}}",
    "<CHAR>" to "{{char}}",
  )
private val ST_MACRO_REGEX = Regex("""\{\{\s*([a-zA-Z0-9_]+)\s*\}\}""")

data class StMacroContext(
  val values: Map<String, String>,
) {
  fun substitute(content: String?): String {
    if (content.isNullOrEmpty()) {
      return content.orEmpty()
    }

    var current = normalizeLegacyMacros(content)
    repeat(ST_MACRO_MAX_PASSES) {
      val replaced =
        ST_MACRO_REGEX.replace(current) { match ->
          values[match.groupValues[1]] ?: match.value
        }
      if (replaced == current) {
        return replaced
      }
      current = replaced
    }
    return current
  }

  private fun normalizeLegacyMacros(content: String): String {
    var normalized = content
    LEGACY_ST_NAME_MACROS.forEach { (legacyToken, macroToken) ->
      normalized = normalized.replace(legacyToken, macroToken, ignoreCase = true)
    }
    return normalized
  }
}

fun RoleCard.toStMacroContext(userProfile: StUserProfile = StUserProfile()): StMacroContext {
  return toStChatRuntimeRole(userProfile = userProfile).toStMacroContext()
}

fun RoleCard.toStMacroContext(userName: String): StMacroContext {
  return toStMacroContext(userProfile = StUserProfile().withActivePersona(name = userName))
}

fun StChatRuntimeRole.toStMacroContext(): StMacroContext {
  val cardData = cardData()
  val creatorNotes = cardData.creator_notes.orEmpty().ifBlank { card.creatorcomment.orEmpty() }
  val mesExamplesRaw = exampleDialoguesRaw()
  val inPromptPersona = userProfile.personaDescriptionInPrompt()
  return StMacroContext(
    values =
      mapOf(
        "user" to userName,
        "char" to name(),
        "description" to summary(),
        "personality" to personaDescription(),
        "scenario" to worldSettings(),
        "persona" to inPromptPersona,
        "mesExamples" to mesExamplesRaw,
        "mesExamplesRaw" to mesExamplesRaw,
        "creatorNotes" to creatorNotes,
        "charPrompt" to systemPrompt(),
        "charVersion" to cardData.character_version.orEmpty(),
        "char_version" to cardData.character_version.orEmpty(),
        "charDepthPrompt" to cardData.extensions.toDepthPromptPrompt().orEmpty(),
      ),
  )
}

private fun JsonObject?.toDepthPromptPrompt(): String? {
  val depthPrompt = this?.getAsJsonObject("depth_prompt") ?: return null
  return depthPrompt.get("prompt")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
}
