package selfgemma.talk.feature.roleplay.sessions

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.usecase.EnsureRoleplaySeedDataUseCase
import selfgemma.talk.domain.roleplay.usecase.ExportStChatJsonlFromSessionUseCase
import selfgemma.talk.domain.roleplay.usecase.ImportStChatJsonlIntoSessionUseCase
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri

data class SessionListItemUiState(
  val id: String,
  val title: String,
  val roleName: String,
  val avatarUri: String?,
  val pinned: Boolean,
  val updatedAt: Long,
  val lastMessage: String,
)

data class SessionsUiState(
  val loading: Boolean = true,
  val sessions: List<SessionListItemUiState> = emptyList(),
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

@HiltViewModel
class SessionsViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val conversationRepository: ConversationRepository,
  roleRepository: RoleRepository,
  ensureRoleplaySeedData: EnsureRoleplaySeedDataUseCase,
  private val importStChatJsonlIntoSessionUseCase: ImportStChatJsonlIntoSessionUseCase,
  private val exportStChatJsonlFromSessionUseCase: ExportStChatJsonlFromSessionUseCase,
) : ViewModel() {
  companion object {
    private const val TAG = "SessionsViewModel"
  }

  private val feedbackState = MutableStateFlow(SessionsUiState(loading = false))

  val uiState: StateFlow<SessionsUiState> =
    combine(
      conversationRepository.observeSessions(),
      roleRepository.observeRoles(),
      feedbackState,
    ) { sessions, roles, feedback ->
      val rolesById = roles.associateBy { it.id }
      SessionsUiState(
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
        statusMessage = feedback.statusMessage,
        errorMessage = feedback.errorMessage,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SessionsUiState(),
      )

  init {
    viewModelScope.launch {
      ensureRoleplaySeedData()
    }
  }

  fun togglePin(sessionId: String) {
    viewModelScope.launch {
      val session = conversationRepository.getSession(sessionId) ?: return@launch
      val now = System.currentTimeMillis()
      val nextPinned = !session.pinned
      Log.d(TAG, "togglePin sessionId=$sessionId fromPinned=${session.pinned} toPinned=$nextPinned")
      conversationRepository.updateSession(
        session.copy(pinned = nextPinned, updatedAt = now, lastMessageAt = session.lastMessageAt)
      )
    }
  }

  fun archiveSession(sessionId: String) {
    viewModelScope.launch {
      conversationRepository.archiveSession(sessionId)
    }
  }

  fun deleteSession(sessionId: String) {
    viewModelScope.launch {
      conversationRepository.deleteSession(sessionId)
    }
  }

  fun importChatJsonl(sessionId: String, uri: String) {
    viewModelScope.launch {
      runCatching {
        importStChatJsonlIntoSessionUseCase.importIntoSession(sessionId = sessionId, uri = uri)
      }
        .onSuccess {
          feedbackState.update {
            it.copy(
              statusMessage = appString(R.string.sessions_status_imported),
              errorMessage = null,
            )
          }
        }
        .onFailure { error ->
          feedbackState.update {
            it.copy(
              statusMessage = null,
              errorMessage = error.message ?: appString(R.string.sessions_error_import_failed),
            )
          }
        }
    }
  }

  fun exportChatJsonl(sessionId: String, uri: String) {
    viewModelScope.launch {
      runCatching {
        exportStChatJsonlFromSessionUseCase.exportFromSession(sessionId = sessionId, uri = uri)
      }
        .onSuccess {
          feedbackState.update {
            it.copy(
              statusMessage = appString(R.string.sessions_status_exported),
              errorMessage = null,
            )
          }
        }
        .onFailure { error ->
          feedbackState.update {
            it.copy(
              statusMessage = null,
              errorMessage = error.message ?: appString(R.string.sessions_error_export_failed),
            )
          }
        }
    }
  }

  private fun appString(@StringRes resId: Int, vararg args: Any): String {
    return appContext.getString(resId, *args)
  }
}
