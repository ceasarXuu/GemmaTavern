package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stchat.ImportedStChatMessages
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ImportStChatJsonlFromUriUseCase
@Inject
constructor(
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val importStChatJsonlUseCase: ImportStChatJsonlUseCase,
) {
  suspend fun importFromUri(
    sessionId: String,
    uri: String,
    now: Long = System.currentTimeMillis(),
  ): ImportedStChatMessages {
    val rawJsonl = documentRepository.readText(uri)
    return importStChatJsonlUseCase.importFromJsonl(
      sessionId = sessionId,
      rawJsonl = rawJsonl,
      now = now,
    )
  }
}
