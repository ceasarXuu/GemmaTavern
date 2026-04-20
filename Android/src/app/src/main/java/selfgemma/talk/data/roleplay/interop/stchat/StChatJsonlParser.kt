package selfgemma.talk.data.roleplay.interop.stchat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement

class StChatJsonlParser {
  private val gson: Gson = GsonBuilder().create()

  fun parse(rawJsonl: String): ParsedStChatJsonl {
    val lines = rawJsonl.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    require(lines.isNotEmpty()) { "ST chat jsonl is empty." }

    val header = gson.fromJson(lines.first(), StChatHeaderDto::class.java)
    val messages =
      lines.drop(1).map { line ->
        val dto = gson.fromJson(line, StChatMessageDto::class.java)
        dto.toDomain(gson)
      }

    return ParsedStChatJsonl(
      chatMetadataJson = header.chat_metadata.toJsonString(gson),
      userName = header.user_name,
      characterName = header.character_name,
      messages = messages,
    )
  }

  private fun StChatMessageDto.toDomain(gson: Gson): StChatMessage {
    return StChatMessage(
      name = name.orEmpty(),
      isUser = is_user ?: false,
      isSystem = is_system ?: false,
      sendDate = send_date,
      text = mes.orEmpty(),
      extraJson = extra.toJsonString(gson),
      swipesJson = swipes.toNullableJsonString(gson),
      swipeId = swipe_id,
      swipeInfoJson = swipe_info.toNullableJsonString(gson),
      forceAvatar = force_avatar,
      originalAvatar = original_avatar,
      genStarted = gen_started,
      genFinished = gen_finished,
    )
  }

  private fun JsonElement?.toJsonString(gson: Gson): String {
    return if (this == null || isJsonNull) "{}" else gson.toJson(this)
  }

  private fun JsonElement?.toNullableJsonString(gson: Gson): String? {
    return if (this == null || isJsonNull) null else gson.toJson(this)
  }
}
