package selfgemma.talk.feature.roleplay.maintab

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import selfgemma.talk.R
import selfgemma.talk.performance.FrontendPerformanceMonitor
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel

private data class TabItem(
  val titleResId: Int,
  val icon: ImageVector,
)

private val tabs = listOf(
  TabItem(R.string.tab_messages, Icons.Rounded.Chat),
  TabItem(R.string.tab_roles, Icons.Rounded.Face),
  TabItem(R.string.tab_me, Icons.Rounded.Person),
  TabItem(R.string.tab_settings, Icons.Rounded.Settings),
)

private const val TAG = "MainTabScreen"

@Composable
fun MainTabScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenSession: (String) -> Unit,
  onOpenRoleCatalog: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenToolManagement: () -> Unit,
  onOpenModelLibrary: () -> Unit,
  onOpenChat: (String) -> Unit,
  onCreateRole: () -> Unit,
  onEditRole: (String) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pagerState = rememberPagerState(pageCount = { tabs.size })
  val scope = rememberCoroutineScope()

  val currentPage by remember { derivedStateOf { pagerState.currentPage } }
  val targetPage by remember { derivedStateOf { pagerState.targetPage } }
  val isScrollInProgress by remember { derivedStateOf { pagerState.isScrollInProgress } }
  val currentPageName = when (currentPage) {
    0 -> "messages"
    1 -> "roles"
    2 -> "me"
    else -> "settings"
  }
  val handleNavigateUp: () -> Unit = {
    if (currentPage == 0) {
      Log.d(TAG, "navigate up from root tab, delegating to host")
      navigateUp()
    } else {
      Log.d(TAG, "navigate up from secondary tab page=$currentPage, returning to messages tab")
      scope.launch { pagerState.animateScrollToPage(page = 0) }
    }
  }

  TrackPerformanceState(key = "MainTab", value = currentPageName)

  LaunchedEffect(currentPage, isScrollInProgress) {
    if (!isScrollInProgress) {
      Log.d(TAG, "main tab settled currentPage=$currentPage targetPage=$targetPage")
    }
  }

  BackHandler(enabled = currentPage != 0) { handleNavigateUp() }

  Scaffold(
    modifier = modifier,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    bottomBar = {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
      ) {
        tabs.forEachIndexed { index, tab ->
          val isSelected = currentPage == index
          val tabTitle = stringResource(tab.titleResId)
          NavigationBarItem(
            selected = isSelected,
            onClick = {
              if (currentPage != index) {
                scope.launch {
                  val startTime = SystemClock.elapsedRealtime()
                  pagerState.scrollToPage(page = index)
                  val duration = SystemClock.elapsedRealtime() - startTime
                  FrontendPerformanceMonitor.recordInteraction(
                    name = "main_tab_switch",
                    durationMs = duration,
                  )
                  Log.d(
                    TAG,
                    "main tab switched targetIndex=$index targetTitle=$tabTitle durationMs=$duration",
                  )
                }
              }
            },
            icon = {
              Icon(
                imageVector = tab.icon,
                contentDescription = tabTitle,
              )
            },
            label = {
              Text(
                text = tabTitle,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
              )
            },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MaterialTheme.colorScheme.primary,
              selectedTextColor = MaterialTheme.colorScheme.primary,
              indicatorColor = MaterialTheme.colorScheme.primaryContainer,
              unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
          )
        }
      }
    },
  ) { innerPadding ->
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
      beyondViewportPageCount = 2,
      pageSpacing = 0.dp,
      key = { page -> page },
    ) { page ->
      when (page) {
        0 -> {
          selfgemma.talk.feature.roleplay.sessions.SessionsScreen(
            onOpenSession = onOpenSession,
            onOpenRoleCatalog = onOpenRoleCatalog,
            onOpenSettings = onOpenSettings,
            onOpenModelLibrary = onOpenModelLibrary,
            showFab = false,
            contentPadding = innerPadding,
          )
        }
        1 -> {
          selfgemma.talk.feature.roleplay.roles.RoleCatalogScreen(
            modelManagerViewModel = modelManagerViewModel,
            navigateUp = handleNavigateUp,
            onOpenChat = onOpenChat,
            onOpenModelLibrary = onOpenModelLibrary,
            onCreateRole = onCreateRole,
            onEditRole = onEditRole,
            showNavigateUp = false,
            contentPadding = innerPadding,
          )
        }
        2 -> {
          selfgemma.talk.feature.roleplay.profile.MyProfileScreen(
            navigateUp = handleNavigateUp,
            showNavigateUp = false,
            contentPadding = innerPadding,
          )
        }
        3 -> {
          // NOTE:
          // The bottom "Settings" tab in the roleplay main UI renders RoleplaySettingsScreen here.
          // If a setting should appear in the roleplay tab, update RoleplaySettingsScreen instead of
          // the legacy home SettingsDialog.
          selfgemma.talk.feature.roleplay.settings.RoleplaySettingsScreen(
            modelManagerViewModel = modelManagerViewModel,
            navigateUp = handleNavigateUp,
            onOpenModelLibrary = onOpenModelLibrary,
            onOpenToolManagement = onOpenToolManagement,
            showNavigateUp = false,
            contentPadding = innerPadding,
          )
        }
      }
    }
  }
}
