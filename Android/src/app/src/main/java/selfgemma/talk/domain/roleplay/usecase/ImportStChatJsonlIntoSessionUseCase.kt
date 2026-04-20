package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

class ImportStChatJsonlIntoSessionUseCase
@Inject
constructor(
  private val conversationRepository: ConversationRepository,
  private val importStChatJsonlFromUriUseCase: ImportStChatJsonlFromUriUseCase,
) {
  suspend fun importIntoSession(sessionId: String, uri: String, now: Long = System.currentTimeMillis()) {
    val session = conversationRepository.getSession(sessionId) ?: error("Session not found.")
    val imported =
      importStChatJsonlFromUriUseCase.importFromUri(
        sessionId = sessionId,
        uri = uri,
        now = now,
      )

    conversationRepository.replaceMessages(sessionId = sessionId, messages = imported.messages)
    conversationRepository.updateSession(
      session.copy(
        interopChatMetadataJson = imported.chatMetadataJson,
        updatedAt = now,
      )
    )
  }
}
