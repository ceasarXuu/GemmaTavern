package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stcard.StRoleCardInteropMapper
import selfgemma.talk.data.roleplay.interop.stcard.StV2CardParser
import selfgemma.talk.domain.roleplay.model.RoleCard

class ImportStV2RoleCardUseCase @Inject constructor() {
  private val parser = StV2CardParser()

  fun importFromJson(
    rawJson: String,
    now: Long = System.currentTimeMillis(),
    existingRole: RoleCard? = null,
  ): RoleCard {
    val parsed = parser.parse(rawJson)
    return StRoleCardInteropMapper.importedV2ToRoleCard(
      parsed = parsed,
      now = now,
      existingRole = existingRole,
    )
  }
}
