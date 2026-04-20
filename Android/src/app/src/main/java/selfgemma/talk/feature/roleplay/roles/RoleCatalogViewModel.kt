package selfgemma.talk.feature.roleplay.roles

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.availablePersonaSlotIds
import selfgemma.talk.domain.roleplay.model.selectPersonaSlot
import selfgemma.talk.domain.roleplay.model.snapshotSelectedPersona
import selfgemma.talk.domain.roleplay.repository.RoleRepository
import selfgemma.talk.domain.roleplay.usecase.CompileRuntimeRoleProfileUseCase
import selfgemma.talk.domain.roleplay.usecase.CreateRoleplaySessionUseCase
import selfgemma.talk.domain.roleplay.usecase.EnsureRoleplaySeedDataUseCase
import selfgemma.talk.domain.roleplay.usecase.ImportStRoleCardFromUriUseCase

private const val TAG = "RoleCatalogViewModel"

data class RoleCatalogUiState(
  val loading: Boolean = true,
  val builtInRoles: List<RoleCard> = emptyList(),
  val customRoles: List<RoleCard> = emptyList(),
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

data class SessionPersonaOptionUiState(
  val slotId: String,
  val name: String,
  val descriptionPreview: String,
  val avatarUri: String? = null,
  val isDefault: Boolean = false,
  val isCurrent: Boolean = false,
)

@HiltViewModel
class RoleCatalogViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val dataStoreRepository: DataStoreRepository,
  private val roleRepository: RoleRepository,
  private val createRoleplaySessionUseCase: CreateRoleplaySessionUseCase,
  private val importStRoleCardFromUriUseCase: ImportStRoleCardFromUriUseCase,
  private val compileRuntimeRoleProfileUseCase: CompileRuntimeRoleProfileUseCase,
  private val ensureRoleplaySeedDataUseCase: EnsureRoleplaySeedDataUseCase,
) : ViewModel() {
  private val feedbackState = MutableStateFlow(RoleCatalogUiState(loading = false))

  val uiState: StateFlow<RoleCatalogUiState> =
    combine(roleRepository.observeRoles(), feedbackState) { roles, feedback ->
      RoleCatalogUiState(
        loading = false,
        builtInRoles = roles.filter { it.builtIn },
        customRoles = roles.filterNot { it.builtIn },
        statusMessage = feedback.statusMessage,
        errorMessage = feedback.errorMessage,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RoleCatalogUiState(),
      )

  init {
    viewModelScope.launch {
      ensureRoleplaySeedDataUseCase()
    }
  }

  fun getSessionPersonaOptions(): List<SessionPersonaOptionUiState> {
    val profile = dataStoreRepository.getStUserProfile().ensureDefaults()
    val availableSlotIds = profile.availablePersonaSlotIds()
    val defaultSlotId = profile.defaultPersonaId?.trim().takeUnless { it.isNullOrBlank() }
    val currentSlotId = profile.resolvedUserAvatarId()
    return availableSlotIds
      .map { slotId ->
        val slotProfile = profile.selectPersonaSlot(slotId)
        SessionPersonaOptionUiState(
          slotId = slotId,
          name = slotProfile.userName,
          descriptionPreview = slotProfile.personaDescription.firstNonBlankLine(),
          avatarUri = slotProfile.activeAvatarUri,
          isDefault = slotId == defaultSlotId,
          isCurrent = slotId == currentSlotId,
        )
      }.sortedWith(
        compareByDescending<SessionPersonaOptionUiState> { it.isDefault }
          .thenByDescending { it.isCurrent }
          .thenBy { it.name.lowercase() }
          .thenBy { it.slotId },
      )
  }

  suspend fun createSession(roleId: String, modelId: String, personaSlotId: String? = null): String {
    val selectedPersonaProfile =
      dataStoreRepository.getStUserProfile().snapshotSelectedPersona(personaSlotId)
    Log.d(
      TAG,
      "create session roleId=$roleId modelId=$modelId personaSlotId=${selectedPersonaProfile.userAvatarId} personaName=${selectedPersonaProfile.userName}",
    )
    return createRoleplaySessionUseCase(
      roleId = roleId,
      modelId = modelId,
      userProfile = selectedPersonaProfile,
    ).id
  }

  fun deleteRole(roleId: String) {
    viewModelScope.launch {
      roleRepository.deleteRole(roleId)
    }
  }

  fun importStRoleCard(uri: String) {
    viewModelScope.launch {
      runCatching {
        importStRoleCardFromUriUseCase.importFromUri(uri = uri)
      }
        .onSuccess { importedRole ->
          roleRepository.saveRole(compileRuntimeRoleProfileUseCase(importedRole))
          feedbackState.update {
            it.copy(
              statusMessage = appString(R.string.role_catalog_status_st_imported),
              errorMessage = null,
            )
          }
        }
        .onFailure { error ->
          feedbackState.update {
            it.copy(
              statusMessage = null,
              errorMessage = error.message ?: appString(R.string.role_catalog_error_st_import_failed),
            )
          }
        }
    }
  }

  private fun appString(@StringRes resId: Int, vararg args: Any): String {
    return appContext.getString(resId, *args)
  }
}

private fun String.firstNonBlankLine(): String {
  return lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
}
