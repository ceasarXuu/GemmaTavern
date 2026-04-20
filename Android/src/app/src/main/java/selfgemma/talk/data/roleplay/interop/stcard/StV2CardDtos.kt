package selfgemma.talk.data.roleplay.interop.stcard

import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.StCharacterCard

data class ParsedStCardV2(
  val card: StCharacterCard,
  val interopState: RoleInteropState,
)
