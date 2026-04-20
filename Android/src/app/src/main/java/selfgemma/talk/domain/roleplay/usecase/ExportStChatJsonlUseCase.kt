package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stchat.StChatInteropMapper
import selfgemma.talk.data.roleplay.interop.stchat.StChatJsonlSerializer
import selfgemma.talk.domain.roleplay.model.Message

private const val ST_DEPRECATED_CHAT_HEADER_VALUE = "unused"

class ExportStChatJsonlUseCase @Inject constructor() {
  private val serializer = StChatJsonlSerializer()

  fun exportToJsonl(
    chatMetadataJson: String,
    userName: String?,
    roleName: String,
    messages: List<Message>,
  ): String {
    val interopMessages =
      StChatInteropMapper.messagesToExport(
        messages = messages,
        roleName = roleName,
        userName = userName ?: "User",
      )

    return serializer.serialize(
      chatMetadataJson = chatMetadataJson,
      userName = ST_DEPRECATED_CHAT_HEADER_VALUE,
      characterName = ST_DEPRECATED_CHAT_HEADER_VALUE,
      messages = interopMessages,
    )
  }
}
