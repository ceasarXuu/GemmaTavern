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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.repository.ConversationRepository
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.usecase.EnsureRoleplaySeedDataUseCase
import selfgemma.talk.domain.roleplay.model.RoleplayDebugExportOrigin
import selfgemma.talk.domain.roleplay.usecase.ExportRoleplayDebugBundleFromSessionUseCase
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

internal const val SESSIONS_STATUS_MESSAGE_AUTO_DISMISS_MS = 2_000L

private fun logDebug(message: String) {
  runCatching {
    Log.d("SessionsViewModel", message)
  }
}

private fun logWarn(message: String) {
  runCatching {
    Log.w("SessionsViewModel", message)
  }
}

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
  private val exportRoleplayDebugBundleFromSessionUseCase: ExportRoleplayDebugBundleFromSessionUseCase,
) : ViewModel() {
  private val feedbackState = MutableStateFlow(SessionsUiState(loading = false))
  private var statusMessageDismissJob: kotlinx.coroutines.Job? = null
  internal var stringResolver: (Int, List<Any>) -> String =
    { resId, args -> appContext.getString(resId, *args.toTypedArray()) }

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
      logDebug("togglePin sessionId=$sessionId fromPinned=${session.pinned} toPinned=$nextPinned")
      conversationRepository.updateSession(
        session.copy(pinned = nextPinned, updatedAt = now, lastMessageAt = session.lastMessageAt)
      )
    }
  }

  fun archiveSession(sessionId: String) {
    viewModelScope.launch {
      logDebug("archiveSession sessionId=$sessionId")
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
          showStatusMessage(appString(R.string.sessions_status_imported))
        }
        .onFailure { error ->
          showErrorMessage(error.message ?: appString(R.string.sessions_error_import_failed))
        }
    }
  }

  fun exportChatJsonl(sessionId: String, uri: String) {
    viewModelScope.launch {
      runCatching {
        exportStChatJsonlFromSessionUseCase.exportFromSession(sessionId = sessionId, uri = uri)
      }
        .onSuccess {
          showStatusMessage(appString(R.string.sessions_status_exported))
        }
        .onFailure { error ->
          showErrorMessage(error.message ?: appString(R.string.sessions_error_export_failed))
        }
    }
  }

  fun exportDebugBundle(sessionId: String) {
    viewModelScope.launch {
      runCatching {
        exportRoleplayDebugBundleFromSessionUseCase.exportFromSession(
          sessionId = sessionId,
          origin = RoleplayDebugExportOrigin.SESSIONS_LIST,
        )
      }
        .onSuccess { result ->
          showStatusMessage(
            appString(
              R.string.roleplay_debug_export_status,
              result.sessionTitle,
              displaySessionId(result.sessionId),
              result.bundleFile.fileName,
            )
          )
        }
        .onFailure { error ->
          showErrorMessage(error.message ?: appString(R.string.roleplay_debug_export_error))
        }
    }
  }

  private fun appString(@StringRes resId: Int, vararg args: Any): String {
    return stringResolver(resId, args.toList())
  }

  private fun displaySessionId(sessionId: String): String {
    return if (sessionId.length <= 12) sessionId else sessionId.take(8)
  }

  private fun showStatusMessage(message: String) {
    statusMessageDismissJob?.cancel()
    logDebug("show status message message=$message")
    feedbackState.update { current ->
      current.copy(statusMessage = message, errorMessage = null)
    }
    statusMessageDismissJob =
      viewModelScope.launch {
        delay(SESSIONS_STATUS_MESSAGE_AUTO_DISMISS_MS)
        feedbackState.update { current ->
          if (current.statusMessage == message) {
            logDebug("auto-dismiss status message message=$message")
            current.copy(statusMessage = null)
          } else {
            current
          }
        }
      }
  }

  private fun showErrorMessage(message: String) {
    statusMessageDismissJob?.cancel()
    logWarn("show error message message=$message")
    feedbackState.update { current ->
      current.copy(statusMessage = null, errorMessage = message)
    }
  }
}
