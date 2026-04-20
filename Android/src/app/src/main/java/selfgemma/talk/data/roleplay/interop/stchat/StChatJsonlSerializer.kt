package selfgemma.talk.data.roleplay.interop.stchat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class StChatJsonlSerializer {
  private val gson: Gson = GsonBuilder().create()

  fun serialize(
    chatMetadataJson: String,
    userName: String?,
    characterName: String?,
    messages: List<StChatMessage>,
  ): String {
    val header =
      StChatHeaderDto(
        chat_metadata = chatMetadataJson.toJsonElement(),
        user_name = userName,
        character_name = characterName,
      )

    return buildList {
      add(gson.toJson(header))
      messages.forEach { message ->
        add(
          gson.toJson(
            StChatMessageDto(
              name = message.name,
              is_user = message.isUser,
              is_system = message.isSystem,
              send_date = message.sendDate,
              mes = message.text,
              extra = message.extraJson.toJsonElement(),
              swipes = message.swipesJson.toNullableJsonElement(),
              swipe_id = message.swipeId,
              swipe_info = message.swipeInfoJson.toNullableJsonElement(),
              force_avatar = message.forceAvatar,
              original_avatar = message.originalAvatar,
              gen_started = message.genStarted,
              gen_finished = message.genFinished,
            )
          )
        )
      }
    }.joinToString("\n")
  }

  private fun String.toJsonElement() =
    runCatching { JsonParser.parseString(this) }.getOrElse { JsonObject() }

  private fun String?.toNullableJsonElement() =
    this?.let { value -> runCatching { JsonParser.parseString(value) }.getOrElse { JsonObject() } }
}
