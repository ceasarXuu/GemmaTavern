package selfgemma.talk.data.roleplay.interop.stcard

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.cardDataOrEmpty
import selfgemma.talk.domain.roleplay.model.resolvedName

class StV2CardParser {
  private val gson: Gson = GsonBuilder().create()

  fun parse(rawJson: String): ParsedStCardV2 {
    val rawObject = JsonParser.parseString(rawJson).asJsonObject
    val parsed = gson.fromJson(rawJson, StCharacterCard::class.java)
    require(parsed.spec == ST_V2_SPEC) { "Unsupported ST card spec: ${parsed.spec}" }
    require(parsed.spec_version == ST_V2_SPEC_VERSION) {
      "Unsupported ST card spec_version: ${parsed.spec_version}"
    }

    val normalized = readFromV2(parsed)
    require(normalized.data != null) { "ST v2 card is missing data payload." }
    require(normalized.resolvedName().isNotBlank()) { "ST v2 card name is blank." }

    return ParsedStCardV2(
      card = normalized,
      interopState =
        RoleInteropState(
          sourceFormat = RoleCardSourceFormat.ST_JSON,
          sourceSpec = normalized.spec,
          sourceSpecVersion = normalized.spec_version,
          rawCardJson = rawJson,
          rawUnknownTopLevelJson = extractUnknownTopLevel(rawObject).takeIf { it.size() > 0 }?.toString(),
          rawUnknownDataJson = extractUnknownData(rawObject).takeIf { it.size() > 0 }?.toString(),
          rawUnknownExtensionsJson = extractUnknownExtensions(rawObject).takeIf { it.size() > 0 }?.toString(),
        ),
    )
  }

  private fun extractUnknownTopLevel(rawObject: JsonObject): JsonObject {
    val known =
      setOf(
        "spec", "spec_version", "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creatorcomment", "avatar", "chat", "talkativeness", "fav", "creator", "tags", "create_date", "data",
      )
    return rawObject.deepCopy().apply { known.forEach(::remove) }
  }

  private fun extractUnknownData(rawObject: JsonObject): JsonObject {
    val data = rawObject.getAsJsonObject("data") ?: return JsonObject()
    val known =
      setOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
        "system_prompt", "post_history_instructions", "alternate_greetings", "tags", "creator",
        "character_version", "character_book", "extensions",
      )
    return data.deepCopy().apply { known.forEach(::remove) }
  }

  private fun extractUnknownExtensions(rawObject: JsonObject): JsonObject {
    val extensions = rawObject.getAsJsonObject("data")?.getAsJsonObject("extensions") ?: return JsonObject()
    val known = setOf("talkativeness", "fav", "world", "depth_prompt")
    return extensions.deepCopy().apply { known.forEach(::remove) }
  }

  private fun readFromV2(card: StCharacterCard): StCharacterCard {
    val data = card.data ?: return card
    val talkativeness = data.extensions?.get("talkativeness")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.5
    val fav = data.extensions?.get("fav")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    val tags = data.tags ?: card.tags
    val name = data.name ?: card.name
    val description = data.description ?: card.description
    val personality = data.personality ?: card.personality
    val scenario = data.scenario ?: card.scenario
    val firstMes = data.first_mes ?: card.first_mes
    val mesExample = data.mes_example ?: card.mes_example

    return card.copy(
      name = name,
      description = description,
      personality = personality,
      scenario = scenario,
      first_mes = firstMes,
      mes_example = mesExample,
      talkativeness = talkativeness,
      fav = fav,
      tags = tags,
      chat = card.chat ?: "${name.orEmpty()} - ${humanizedDateTime()}",
      data =
        data.copy(
          alternate_greetings = data.alternate_greetings.orEmpty(),
          tags = data.tags ?: card.tags ?: emptyList(),
          extensions = data.extensions,
        ),
    )
  }

  private fun humanizedDateTime(timestamp: Long = System.currentTimeMillis()): String {
    val date = java.util.Date(timestamp)
    val calendar = java.util.Calendar.getInstance().apply { time = date }
    fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
    return buildString {
      append(calendar.get(java.util.Calendar.YEAR))
      append('-')
      append(pad(calendar.get(java.util.Calendar.MONTH) + 1))
      append('-')
      append(pad(calendar.get(java.util.Calendar.DAY_OF_MONTH)))
      append('@')
      append(pad(calendar.get(java.util.Calendar.HOUR_OF_DAY)))
      append('h')
      append(pad(calendar.get(java.util.Calendar.MINUTE)))
      append('m')
      append(pad(calendar.get(java.util.Calendar.SECOND)))
      append('s')
      append(pad(calendar.get(java.util.Calendar.MILLISECOND), 3))
      append("ms")
    }
  }

  companion object {
    const val ST_V2_SPEC = "chara_card_v2"
    const val ST_V2_SPEC_VERSION = "2.0"
  }
}
