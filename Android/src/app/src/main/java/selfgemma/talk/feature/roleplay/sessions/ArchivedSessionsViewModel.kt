package selfgemma.talk.feature.roleplay.sessions

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository

private const val TAG = "ArchivedSessionsVM"

data class ArchivedSessionsUiState(
  val loading: Boolean = true,
  val sessions: List<SessionListItemUiState> = emptyList(),
)

@HiltViewModel
class ArchivedSessionsViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val conversationRepository: ConversationRepository,
  roleRepository: RoleRepository,
) : ViewModel() {
  val uiState: StateFlow<ArchivedSessionsUiState> =
    combine(
      conversationRepository.observeArchivedSessions(),
      roleRepository.observeRoles(),
    ) { sessions, roles ->
      val rolesById = roles.associateBy { it.id }
      ArchivedSessionsUiState(
        loading = false,
        sessions =
          sessions.map { session ->
            val role = rolesById[session.roleId]
            SessionListItemUiState(
              id = session.id,
              title = session.title,
              roleName = role?.name ?: appString(R.string.sessions_unknown_role),
              avatarUri = role?.primaryAvatarUri(),
              pinned = session.pinned,
              updatedAt = session.updatedAt,
              lastMessage =
                session.lastAssistantMessageExcerpt
                  ?: session.lastUserMessageExcerpt
                  ?: session.lastSummary
                  ?: appString(R.string.sessions_no_messages),
            )
          },
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArchivedSessionsUiState(),
      )

  fun restoreSession(sessionId: String, onRestored: () -> Unit) {
    viewModelScope.launch {
      logDebug("restore archived session sessionId=$sessionId")
      conversationRepository.restoreSession(sessionId)
      onRestored()
    }
  }

  private fun appString(@StringRes resId: Int, vararg args: Any): String {
    return appContext.getString(resId, *args)
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }
}
