package selfgemma.talk.data.roleplay.interop.stchat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.time.Instant
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus

internal object StChatInteropMapper {
  private val gson: Gson = GsonBuilder().create()

  fun importedToMessages(
    sessionId: String,
    parsed: ParsedStChatJsonl,
    now: Long = System.currentTimeMillis(),
  ): ImportedStChatMessages {
    val messages =
      parsed.messages.mapIndexed { index, message ->
        Message(
          id = "$sessionId-st-import-${index + 1}",
          sessionId = sessionId,
          seq = index + 1,
          side =
            when {
              message.isSystem -> MessageSide.SYSTEM
              message.isUser -> MessageSide.USER
              else -> MessageSide.ASSISTANT
            },
          status = MessageStatus.COMPLETED,
          content = message.text,
          metadataJson = encodeInteropMetadata(message),
          createdAt = now,
          updatedAt = now,
        )
      }

    return ImportedStChatMessages(
      chatMetadataJson = parsed.chatMetadataJson,
      userName = parsed.userName,
      messages = messages,
    )
  }

  fun messagesToExport(
    messages: List<Message>,
    roleName: String,
    userName: String = DEFAULT_USER_NAME,
  ): List<StChatMessage> {
    return messages.sortedBy(Message::seq).map { message ->
      decodeInteropMetadata(message.metadataJson)?.copy(
        name = decodeInteropMetadata(message.metadataJson)?.name?.ifBlank {
          defaultNameForMessage(message = message, roleName = roleName, userName = userName)
        } ?: defaultNameForMessage(message = message, roleName = roleName, userName = userName),
        text = message.content,
      )
        ?: StChatMessage(
          name = defaultNameForMessage(message = message, roleName = roleName, userName = userName),
          isUser = message.side == MessageSide.USER,
          isSystem = message.side == MessageSide.SYSTEM,
          sendDate = Instant.ofEpochMilli(message.createdAt).toString(),
          text = message.content,
        )
    }
  }

  private fun defaultNameForMessage(message: Message, roleName: String, userName: String): String {
    return when (message.side) {
      MessageSide.USER -> userName
      MessageSide.ASSISTANT -> roleName
      MessageSide.SYSTEM -> "System"
    }
  }

  private fun encodeInteropMetadata(message: StChatMessage): String {
    return gson.toJson(
      mapOf(
        KEY_ST_NAME to message.name,
        KEY_ST_IS_USER to message.isUser,
        KEY_ST_IS_SYSTEM to message.isSystem,
        KEY_ST_SEND_DATE to message.sendDate,
        KEY_ST_EXTRA_JSON to message.extraJson,
        KEY_ST_SWIPES_JSON to message.swipesJson,
        KEY_ST_SWIPE_ID to message.swipeId,
        KEY_ST_SWIPE_INFO_JSON to message.swipeInfoJson,
        KEY_ST_FORCE_AVATAR to message.forceAvatar,
        KEY_ST_ORIGINAL_AVATAR to message.originalAvatar,
        KEY_ST_GEN_STARTED to message.genStarted,
        KEY_ST_GEN_FINISHED to message.genFinished,
      )
    )
  }

  private fun decodeInteropMetadata(metadataJson: String?): StChatMessage? {
    if (metadataJson.isNullOrBlank()) {
      return null
    }
    val map = runCatching {
      @Suppress("UNCHECKED_CAST")
      gson.fromJson(metadataJson, Map::class.java) as Map<String, Any?>
    }.getOrNull() ?: return null

    return StChatMessage(
      name = map[KEY_ST_NAME] as? String ?: "",
      isUser = map[KEY_ST_IS_USER] as? Boolean ?: false,
      isSystem = map[KEY_ST_IS_SYSTEM] as? Boolean ?: false,
      sendDate = map[KEY_ST_SEND_DATE] as? String,
      text = "",
      extraJson = map[KEY_ST_EXTRA_JSON] as? String ?: "{}",
      swipesJson = map[KEY_ST_SWIPES_JSON] as? String,
      swipeId = (map[KEY_ST_SWIPE_ID] as? Number)?.toInt(),
      swipeInfoJson = map[KEY_ST_SWIPE_INFO_JSON] as? String,
      forceAvatar = map[KEY_ST_FORCE_AVATAR] as? String,
      originalAvatar = map[KEY_ST_ORIGINAL_AVATAR] as? String,
      genStarted = map[KEY_ST_GEN_STARTED] as? String,
      genFinished = map[KEY_ST_GEN_FINISHED] as? String,
    )
  }

  private const val KEY_ST_NAME = "st_name"
  private const val KEY_ST_IS_USER = "st_is_user"
  private const val KEY_ST_IS_SYSTEM = "st_is_system"
  private const val KEY_ST_SEND_DATE = "st_send_date"
  private const val KEY_ST_EXTRA_JSON = "st_extra_json"
  private const val KEY_ST_SWIPES_JSON = "st_swipes_json"
  private const val KEY_ST_SWIPE_ID = "st_swipe_id"
  private const val KEY_ST_SWIPE_INFO_JSON = "st_swipe_info_json"
  private const val KEY_ST_FORCE_AVATAR = "st_force_avatar"
  private const val KEY_ST_ORIGINAL_AVATAR = "st_original_avatar"
  private const val KEY_ST_GEN_STARTED = "st_gen_started"
  private const val KEY_ST_GEN_FINISHED = "st_gen_finished"
  private const val DEFAULT_USER_NAME = "User"
}
