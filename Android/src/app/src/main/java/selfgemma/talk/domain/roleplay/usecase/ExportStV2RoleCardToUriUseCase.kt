package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ExportStV2RoleCardToUriUseCase
@Inject
constructor(
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val exportStV2RoleCardUseCase: ExportStV2RoleCardUseCase,
) {
  suspend fun exportToUri(uri: String, role: RoleCard) {
    val json = exportStV2RoleCardUseCase.exportToJson(role)
    documentRepository.writeText(uri, json)
  }
}
