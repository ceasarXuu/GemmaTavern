package selfgemma.talk.feature.roleplay.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import selfgemma.talk.AppTopBar
import selfgemma.talk.BuildConfig
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.ui.common.AppPreferenceSwitchCard
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private const val TAG = "RoleplayToolMgmtScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayToolManagementScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  viewModel: RoleplaySettingsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsState()
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      result ->
      Log.d(TAG, "tool permission request completed results=$result")
      modelManagerViewModel.updateSettingsUpdateTrigger()
    }
  val handleEnableTools: (Set<String>) -> Unit = { toolIds ->
    val missingPermissions =
      toolIds
        .flatMap { toolId ->
          roleplayToolManagementEntries
            .firstOrNull { it.toolId == toolId }
            ?.requiredPermissions
            .orEmpty()
        }
        .filterNot { permission -> hasPermission(context, permission) }
        .distinct()
    if (missingPermissions.isNotEmpty()) {
      permissionLauncher.launch(missingPermissions.toTypedArray())
    }
  }

  Scaffold(
    modifier = modifier,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      AppTopBar(
        title = stringResource(R.string.settings_tool_management_title),
        leftAction =
          AppBarAction(
            actionType = AppBarActionType.NAVIGATE_UP,
            actionFn = navigateUp,
          ),
      )
    },
  ) { innerPadding ->
    val combinedPadding =
      PaddingValues(
        top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
        start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
      )

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(combinedPadding)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (BuildConfig.ENABLE_INTERNAL_DIAGNOSTICS) {
        AppPreferenceSwitchCard(
          title = stringResource(R.string.settings_roleplay_tool_debug_output_title),
          summary = stringResource(R.string.settings_roleplay_tool_debug_output_summary),
          checked = uiState.roleplayToolDebugOutputEnabled,
          onCheckedChange = { enabled ->
            viewModel.setRoleplayToolDebugOutputEnabled(enabled)
            modelManagerViewModel.updateSettingsUpdateTrigger()
          },
        )
      }
      AppPreferenceSwitchCard(
        title = stringResource(R.string.settings_tool_management_all_tools_title),
        summary =
          if (uiState.allToolsEnabled) {
            stringResource(R.string.settings_tool_management_all_tools_summary_enabled)
          } else {
            stringResource(R.string.settings_tool_management_all_tools_summary_disabled)
          },
        checked = uiState.allToolsEnabled,
        onCheckedChange = { enabled ->
          viewModel.setAllRoleplayToolsEnabled(enabled)
          if (enabled) {
            handleEnableTools(roleplayToolManagementEntries.map(RoleplayToolManagementEntry::toolId).toSet())
          }
          modelManagerViewModel.updateSettingsUpdateTrigger()
        },
      )
      roleplayToolManagementEntries.forEach { entry ->
        val toolState = uiState.toolStates.firstOrNull { it.toolId == entry.toolId }
        val permissionHint = permissionHintForEntry(context = context, entry = entry)
        AppPreferenceSwitchCard(
          title = stringResource(entry.titleResId),
          summary =
            buildString {
              append(stringResource(entry.descriptionResId))
              if (permissionHint != null) {
                append("\n")
                append(permissionHint)
              }
            },
          checked = toolState?.enabled ?: true,
          onCheckedChange = { enabled ->
            viewModel.setRoleplayToolEnabled(entry.toolId, enabled)
            if (enabled) {
              handleEnableTools(setOf(entry.toolId))
            }
            modelManagerViewModel.updateSettingsUpdateTrigger()
          },
        )
      }
    }
  }
}

@Composable
private fun permissionHintForEntry(
  context: Context,
  entry: RoleplayToolManagementEntry,
): String? {
  val missingPermissions = entry.requiredPermissions.filterNot { permission -> hasPermission(context, permission) }
  return when {
    Manifest.permission.ACCESS_COARSE_LOCATION in missingPermissions ->
      stringResource(R.string.settings_tool_management_location_permission_hint)
    Manifest.permission.READ_CALENDAR in missingPermissions ->
      stringResource(R.string.settings_tool_management_calendar_permission_hint)
    else -> null
  }
}

private fun hasPermission(context: Context, permission: String): Boolean {
  return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
