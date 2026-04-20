package selfgemma.talk.data.roleplay.interop.stchat

import com.google.gson.JsonElement
import selfgemma.talk.domain.roleplay.model.Message

internal data class StChatHeaderDto(
  val chat_metadata: JsonElement? = null,
  val user_name: String? = null,
  val character_name: String? = null,
)

internal data class StChatMessageDto(
  val name: String? = null,
  val is_user: Boolean? = null,
  val is_system: Boolean? = null,
  val send_date: String? = null,
  val mes: String? = null,
  val extra: JsonElement? = null,
  val swipes: JsonElement? = null,
  val swipe_id: Int? = null,
  val swipe_info: JsonElement? = null,
  val force_avatar: String? = null,
  val original_avatar: String? = null,
  val gen_started: String? = null,
  val gen_finished: String? = null,
)

data class ParsedStChatJsonl(
  val chatMetadataJson: String,
  val userName: String? = null,
  val characterName: String? = null,
  val messages: List<StChatMessage> = emptyList(),
)

data class StChatMessage(
  val name: String,
  val isUser: Boolean,
  val isSystem: Boolean,
  val sendDate: String? = null,
  val text: String = "",
  val extraJson: String = "{}",
  val swipesJson: String? = null,
  val swipeId: Int? = null,
  val swipeInfoJson: String? = null,
  val forceAvatar: String? = null,
  val originalAvatar: String? = null,
  val genStarted: String? = null,
  val genFinished: String? = null,
)

data class ImportedStChatMessages(
  val chatMetadataJson: String,
  val userName: String? = null,
  val messages: List<Message>,
)
