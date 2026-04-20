package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ExportStChatJsonlToUriUseCase
@Inject
constructor(
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val exportStChatJsonlUseCase: ExportStChatJsonlUseCase,
) {
  suspend fun exportToUri(
    uri: String,
    chatMetadataJson: String,
    userName: String?,
    roleName: String,
    messages: List<Message>,
  ) {
    val jsonl =
      exportStChatJsonlUseCase.exportToJsonl(
        chatMetadataJson = chatMetadataJson,
        userName = userName,
        roleName = roleName,
        messages = messages,
      )
    documentRepository.writeText(uri, jsonl)
  }
}
