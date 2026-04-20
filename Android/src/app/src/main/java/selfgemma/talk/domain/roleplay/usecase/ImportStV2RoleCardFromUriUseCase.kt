package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ImportStV2RoleCardFromUriUseCase
@Inject
constructor(
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val importStV2RoleCardUseCase: ImportStV2RoleCardUseCase,
) {
  suspend fun importFromUri(
    uri: String,
    now: Long = System.currentTimeMillis(),
    existingRole: RoleCard? = null,
  ): RoleCard {
    val rawJson = documentRepository.readText(uri)
    return importStV2RoleCardUseCase.importFromJson(
      rawJson = rawJson,
      now = now,
      existingRole = existingRole,
    )
  }
}
