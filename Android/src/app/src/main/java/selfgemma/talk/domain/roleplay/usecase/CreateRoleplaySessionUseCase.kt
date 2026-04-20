package selfgemma.talk.domain.roleplay.usecase

import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.openingMessage
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeRole
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository

class CreateRoleplaySessionUseCase
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
) {
  suspend operator fun invoke(
    roleId: String,
    modelId: String,
    userProfile: StUserProfile? = null,
  ): Session {
    val sessionUserProfile = (userProfile ?: dataStoreRepository.getStUserProfile()).snapshotSelectedPersona()
    val session =
      conversationRepository.createSession(
        roleId = roleId,
        modelId = modelId,
        userProfile = sessionUserProfile,
      )
    val role = roleRepository.getRole(roleId) ?: return session
    val runtimeRole = role.toStChatRuntimeRole(userProfile = session.resolveUserProfile(sessionUserProfile))
    val cardData = runtimeRole.card.data
    val macroContext = runtimeRole.toStMacroContext()
    val openingMessage =
      macroContext.substitute(
        cardData?.first_mes
          ?.ifBlank { cardData.alternate_greetings.orEmpty().firstOrNull().orEmpty() }
          ?.ifBlank { runtimeRole.openingMessage() }
          ?: runtimeRole.openingMessage()
      )
    if (openingMessage.isBlank()) {
      return session
    }

    val now = session.createdAt
    conversationRepository.appendMessage(
      Message(
        id = UUID.randomUUID().toString(),
        sessionId = session.id,
        seq = conversationRepository.nextMessageSeq(session.id),
        side = MessageSide.ASSISTANT,
        status = MessageStatus.COMPLETED,
        content = openingMessage,
        createdAt = now,
        updatedAt = now,
      )
    )
    return conversationRepository.getSession(session.id) ?: session
  }
}
