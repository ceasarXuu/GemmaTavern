package selfgemma.talk.performance.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import selfgemma.talk.feature.roleplay.chat.RoleplayChatScreen
import selfgemma.talk.feature.roleplay.navigation.RoleplayRoutes
import selfgemma.talk.feature.roleplay.roles.RoleCatalogScreen
import selfgemma.talk.feature.roleplay.roles.RoleEditorScreen
import selfgemma.talk.feature.roleplay.sessions.SessionsScreen
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.theme.AppTheme

@AndroidEntryPoint
class RoleplayBenchmarkSurfaceActivity : ComponentActivity() {
  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var launchState by mutableStateOf(RoleplayBenchmarkSurfaceLaunch())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    launchState = intent.toLaunchState()

    setContent {
      AppTheme {
        RoleplayBenchmarkSurfaceHost(
          surface = launchState.surface,
          sessionId = launchState.sessionId,
          modelManagerViewModel = modelManagerViewModel,
          finishActivity = ::finish,
        )
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    launchState = intent.toLaunchState()
  }
}

private data class RoleplayBenchmarkSurfaceLaunch(
  val surface: String? = null,
  val sessionId: String? = null,
)

private fun android.content.Intent.toLaunchState(): RoleplayBenchmarkSurfaceLaunch {
  return RoleplayBenchmarkSurfaceLaunch(
    surface = getStringExtra(ROLEPLAY_BENCHMARK_SURFACE_EXTRA),
    sessionId = getStringExtra(ROLEPLAY_BENCHMARK_SESSION_ID_EXTRA),
  )
}

@Composable
private fun RoleplayBenchmarkSurfaceHost(
  surface: String?,
  sessionId: String?,
  modelManagerViewModel: ModelManagerViewModel,
  finishActivity: () -> Unit,
) {
  when (surface) {
    ROLEPLAY_BENCHMARK_SURFACE_SESSIONS -> {
      SessionsScreen(
        onOpenSession = {},
        onOpenRoleCatalog = {},
        onOpenSettings = {},
        onOpenModelLibrary = {},
        showFab = false,
      )
    }

    ROLEPLAY_BENCHMARK_SURFACE_ROLES -> {
      RoleCatalogScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = finishActivity,
        onOpenChat = {},
        onOpenModelLibrary = {},
        onCreateRole = {},
        onEditRole = {},
      )
    }

    ROLEPLAY_BENCHMARK_SURFACE_ROLE_EDITOR -> {
      RoleEditorScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = finishActivity,
      )
    }

    ROLEPLAY_BENCHMARK_SURFACE_CHAT -> {
      check(!sessionId.isNullOrBlank()) { "sessionId is required for chat benchmark surface" }
      BenchmarkChatSurface(
        sessionId = sessionId,
        modelManagerViewModel = modelManagerViewModel,
        finishActivity = finishActivity,
      )
    }

    else -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Unknown benchmark surface: ${surface ?: "null"}")
      }
    }
  }
}

@Composable
private fun BenchmarkChatSurface(
  sessionId: String,
  modelManagerViewModel: ModelManagerViewModel,
  finishActivity: () -> Unit,
) {
  val navController = rememberNavController()

  NavHost(navController = navController, startDestination = "bootstrap") {
    composable(route = "bootstrap") {
      LaunchedEffect(sessionId) {
        navController.navigate(RoleplayRoutes.chat(sessionId)) {
          popUpTo("bootstrap") { inclusive = true }
        }
      }

      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Opening benchmark chat...")
      }
    }

    composable(
      route = RoleplayRoutes.CHAT,
      arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
    ) {
      val viewModel = hiltViewModel<selfgemma.talk.feature.roleplay.chat.RoleplayChatViewModel>()
      RoleplayChatScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = finishActivity,
        onOpenModelLibrary = {},
        onOpenRoleEditor = {},
        onOpenPersonaEditor = {},
        viewModel = viewModel,
      )
    }
  }
}
