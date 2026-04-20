package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.name
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.model.toStChatRuntimeRole
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository

class ExportStChatJsonlFromSessionUseCase
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val roleRepository: RoleRepository,
  private val exportStChatJsonlToUriUseCase: ExportStChatJsonlToUriUseCase,
) {
  suspend fun exportFromSession(sessionId: String, uri: String) {
    val session = conversationRepository.getSession(sessionId) ?: error("Session not found.")
    val role = roleRepository.getRole(session.roleId) ?: error("Role not found.")
    val runtimeRole =
      role.toStChatRuntimeRole(
        userProfile = session.resolveUserProfile(dataStoreRepository.getStUserProfile()),
      )
    val messages = conversationRepository.listMessages(sessionId)

    exportStChatJsonlToUriUseCase.exportToUri(
      uri = uri,
      chatMetadataJson = session.interopChatMetadataJson ?: "{}",
      userName = runtimeRole.userName,
      roleName = runtimeRole.name(),
      messages = messages,
    )
  }
}
