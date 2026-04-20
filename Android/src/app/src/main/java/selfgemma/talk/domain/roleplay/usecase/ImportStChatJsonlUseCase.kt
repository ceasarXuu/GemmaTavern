package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stchat.ImportedStChatMessages
import selfgemma.talk.data.roleplay.interop.stchat.StChatInteropMapper
import selfgemma.talk.data.roleplay.interop.stchat.StChatJsonlParser

class ImportStChatJsonlUseCase @Inject constructor() {
  private val parser = StChatJsonlParser()

  fun importFromJsonl(
    sessionId: String,
    rawJsonl: String,
    now: Long = System.currentTimeMillis(),
  ): ImportedStChatMessages {
    val parsed = parser.parse(rawJsonl)
    return StChatInteropMapper.importedToMessages(
      sessionId = sessionId,
      parsed = parsed,
      now = now,
    )
  }
}
