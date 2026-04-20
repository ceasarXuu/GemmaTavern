package selfgemma.talk.data.roleplay.interop.stcard

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.roleplay.model.StCharacterCard

class StV2CardSerializer {
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

  fun serialize(card: StCharacterCard): String {
    return gson.toJson(card)
  }

  fun serialize(card: JsonObject): String {
    return gson.toJson(card)
  }

  fun toJsonObject(card: StCharacterCard): JsonObject {
    return JsonParser.parseString(gson.toJson(card)).asJsonObject
  }
}
