/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.ui.navigation

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import selfgemma.talk.feature.roleplay.chat.RoleplayChatScreen
import selfgemma.talk.feature.roleplay.navigation.RoleplayRoutes
import selfgemma.talk.feature.roleplay.profile.MyProfileScreen
import selfgemma.talk.feature.roleplay.roles.RoleEditorScreen
import selfgemma.talk.feature.roleplay.roles.RoleCatalogScreen
import selfgemma.talk.feature.roleplay.maintab.MainTabScreen
import selfgemma.talk.feature.roleplay.sessions.ArchivedSessionsScreen
import selfgemma.talk.feature.roleplay.settings.RoleplaySettingsScreen
import selfgemma.talk.AnalyticsEvent
import selfgemma.talk.BuildConfig
import selfgemma.talk.customtasks.common.CustomTaskData
import selfgemma.talk.customtasks.common.CustomTaskDataForBuiltinTask
import selfgemma.talk.data.Task
import selfgemma.talk.data.isLegacyTasks
import selfgemma.talk.firebaseAnalytics
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.benchmark.BenchmarkScreen
import selfgemma.talk.ui.modelmanager.GlobalModelManager
import selfgemma.talk.ui.modelmanager.ModelManager
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGAppNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val MAIN_TAB_TARGET_KEY = "main_tab_target"
private const val MAIN_TAB_TARGET_NONE = -1
private const val MAIN_TAB_TARGET_MESSAGES = 0

/** Navigation routes. */
@Composable
fun AppNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  var lastNavigatedModelName = remember { "" }
  var pendingChatEnterStartedAtMs by remember { mutableStateOf<Long?>(null) }
  var pendingChatExitStartedAtMs by remember { mutableStateOf<Long?>(null) }
  val currentBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = currentBackStackEntry?.destination?.route

  TrackPerformanceState(key = "Route", value = currentRoute)

  ChatNavigationTimingTracker(
    currentRoute = currentRoute,
    pendingChatEnterStartedAtMs = pendingChatEnterStartedAtMs,
    pendingChatExitStartedAtMs = pendingChatExitStartedAtMs,
    onClearPendingEnter = { pendingChatEnterStartedAtMs = null },
    onClearPendingExit = { pendingChatExitStartedAtMs = null },
  )

  // Track whether app is in foreground.
  TrackAppForeground(lifecycleOwner = lifecycleOwner, modelManagerViewModel = modelManagerViewModel)

  NavHost(
    navController = navController,
    startDestination = RoleplayRoutes.SESSIONS,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    composable(route = RoleplayRoutes.SESSIONS) { backStackEntry ->
      val requestedTabIndex by
        backStackEntry.savedStateHandle
          .getStateFlow(MAIN_TAB_TARGET_KEY, MAIN_TAB_TARGET_NONE)
          .collectAsState()
      MainTabScreen(
        modelManagerViewModel = modelManagerViewModel,
        onOpenSession = { sessionId ->
          pendingChatEnterStartedAtMs = SystemClock.elapsedRealtime()
          navController.navigate(RoleplayRoutes.chat(sessionId))
        },
        onOpenRoleCatalog = { navController.navigate(RoleplayRoutes.ROLE_CATALOG) },
        onOpenSettings = { navController.navigate(RoleplayRoutes.SETTINGS) },
        onOpenArchivedSessions = { navController.navigate(RoleplayRoutes.ARCHIVED_SESSIONS) },
        onOpenToolManagement = { navController.navigate(RoleplayRoutes.TOOL_MANAGEMENT) },
        onOpenModelLibrary = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onOpenChat = { sessionId ->
          pendingChatEnterStartedAtMs = SystemClock.elapsedRealtime()
          navController.navigate(RoleplayRoutes.chat(sessionId)) {
            popUpTo(RoleplayRoutes.SESSIONS) { inclusive = false }
          }
        },
        onCreateRole = { navController.navigate(RoleplayRoutes.roleEditor()) },
        onEditRole = { roleId -> navController.navigate(RoleplayRoutes.roleEditor(roleId)) },
        navigateUp = { navController.navigateUp() },
        requestedTabIndex = requestedTabIndex.takeIf { it != MAIN_TAB_TARGET_NONE },
        onRequestedTabConsumed = {
          backStackEntry.savedStateHandle[MAIN_TAB_TARGET_KEY] = MAIN_TAB_TARGET_NONE
        },
      )
    }

composable(route = RoleplayRoutes.ROLE_CATALOG, enterTransition = { slideEnter() }, exitTransition = { slideExit() }) {
      RoleCatalogScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = { navController.navigateUp() },
        onOpenChat = { sessionId ->
          pendingChatEnterStartedAtMs = SystemClock.elapsedRealtime()
          navController.navigate(RoleplayRoutes.chat(sessionId)) {
            popUpTo(RoleplayRoutes.ROLE_CATALOG) { inclusive = true }
          }
        },
        onOpenModelLibrary = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onCreateRole = { navController.navigate(RoleplayRoutes.roleEditor()) },
        onEditRole = { roleId -> navController.navigate(RoleplayRoutes.roleEditor(roleId)) },
        showNavigateUp = true,
      )
    }

    composable(
      route = RoleplayRoutes.ROLE_EDITOR,
      arguments = listOf(navArgument("roleId") { type = NavType.StringType; nullable = true; defaultValue = null }),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) {
      RoleEditorScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = { navController.navigateUp() },
      )
    }

    composable(
      route = RoleplayRoutes.PROFILE,
      arguments =
        listOf(
          navArgument("slotId") { type = NavType.StringType; nullable = true; defaultValue = null },
          navArgument("edit") { type = NavType.BoolType; defaultValue = false },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      MyProfileScreen(
        navigateUp = { navController.navigateUp() },
        showNavigateUp = true,
        initialSlotId = backStackEntry.arguments?.getString("slotId"),
        startInEditMode = backStackEntry.arguments?.getBoolean("edit") == true,
      )
    }

    composable(
      route = RoleplayRoutes.CHAT,
      arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
      enterTransition = { chatEnter() },
      exitTransition = { chatExit() },
    ) {
      RoleplayChatScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = {
          pendingChatExitStartedAtMs = SystemClock.elapsedRealtime()
          navController.navigateUp()
        },
        onOpenModelLibrary = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onOpenRoleEditor = { roleId -> navController.navigate(RoleplayRoutes.roleEditor(roleId)) },
        onOpenPersonaEditor = { slotId -> navController.navigate(RoleplayRoutes.profile(slotId = slotId, edit = true)) },
      )
    }

    composable(route = RoleplayRoutes.SETTINGS, enterTransition = { slideUpEnter() }, exitTransition = { slideDownExit() }) {
      // NOTE:
      // This route uses the same RoleplaySettingsScreen as the roleplay bottom "Settings" tab.
      // There is also a separate legacy home SettingsDialog used by HomeScreen drawer actions.
      RoleplaySettingsScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = { navController.navigateUp() },
        onOpenModelLibrary = { navController.navigate(ROUTE_MODEL_MANAGER) },
        onOpenArchivedSessions = { navController.navigate(RoleplayRoutes.ARCHIVED_SESSIONS) },
        onOpenToolManagement = { navController.navigate(RoleplayRoutes.TOOL_MANAGEMENT) },
        showNavigateUp = true,
      )
    }

    composable(
      route = RoleplayRoutes.ARCHIVED_SESSIONS,
      enterTransition = { slideUpEnter() },
      exitTransition = { slideDownExit() },
    ) {
      ArchivedSessionsScreen(
        navigateUp = { navController.navigateUp() },
        onSessionRestored = {
          navController.getBackStackEntry(RoleplayRoutes.SESSIONS)
            .savedStateHandle[MAIN_TAB_TARGET_KEY] = MAIN_TAB_TARGET_MESSAGES
          navController.popBackStack(RoleplayRoutes.SESSIONS, false)
        },
      )
    }

    composable(
      route = RoleplayRoutes.TOOL_MANAGEMENT,
      enterTransition = { slideUpEnter() },
      exitTransition = { slideDownExit() },
    ) {
      selfgemma.talk.feature.roleplay.settings.RoleplayToolManagementScreen(
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = { navController.navigateUp() },
      )
    }

    // Home screen.
    composable(route = ROUTE_HOMESCREEN) {
      HomeScreenRouteContent(
        modelManagerViewModel = modelManagerViewModel,
        enableHomeScreenAnimation = enableHomeScreenAnimation,
        onPickTask = { task ->
          pickedTask = task
          enableModelListAnimation = true
          navController.navigate(ROUTE_MODEL_LIST)
          firebaseAnalytics?.logEvent(
            AnalyticsEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", task.id) },
          )
        },
        onOpenModelManager = { navController.navigate(ROUTE_MODEL_MANAGER) },
        modifier = modifier,
      )
    }

    // Model list.
    composable(
      route = ROUTE_MODEL_LIST,
      enterTransition = {
        if (initialState.destination.route == ROUTE_HOMESCREEN) {
          slideEnter()
        } else {
          EnterTransition.None
        }
      },
      exitTransition = {
        if (targetState.destination.route == ROUTE_HOMESCREEN) {
          slideExit()
        } else {
          ExitTransition.None
        }
      },
    ) {
      pickedTask?.let {
        ModelManager(
          viewModel = modelManagerViewModel,
          task = it,
          enableAnimation = enableModelListAnimation,
          onModelClicked = { model ->
            navController.navigate("$ROUTE_MODEL/${it.id}/${model.name}")
          },
          navigateUp = {
            enableHomeScreenAnimation = false
            navController.navigateUp()
          },
        )
      }
    }

    // Model page.
    composable(
      route = "$ROUTE_MODEL/{taskId}/{modelName}",
      arguments =
        listOf(
          navArgument("taskId") { type = NavType.StringType },
          navArgument("modelName") { type = NavType.StringType },
        ),
      enterTransition = { slideEnter() },
      exitTransition = { slideExit() },
    ) { backStackEntry ->
      val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
      val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
      val scope = rememberCoroutineScope()
      val context = LocalContext.current

      modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
        if (lastNavigatedModelName != modelName) {
          modelManagerViewModel.selectModel(initialModel)
          lastNavigatedModelName = modelName
        }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          if (isLegacyTasks(customTask.task.id)) {
            customTask.MainScreen(
              data =
                CustomTaskDataForBuiltinTask(
                  modelManagerViewModel = modelManagerViewModel,
                  onNavUp = {
                    enableModelListAnimation = false
                    lastNavigatedModelName = ""
                    navController.navigateUp()
                  },
                )
            )
          } else {
            var disableAppBarControls by remember { mutableStateOf(false) }
            var hideTopBar by remember { mutableStateOf(false) }
            var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            CustomTaskScreen(
              task = customTask.task,
              modelManagerViewModel = modelManagerViewModel,
              onNavigateUp = {
                if (customNavigateUpCallback != null) {
                  customNavigateUpCallback?.invoke()
                } else {
                  enableModelListAnimation = false
                  lastNavigatedModelName = ""
                  navController.navigateUp()

                  // clean up all models.
                  for (curModel in customTask.task.models) {
                    val instanceToCleanUp = curModel.instance
                    scope.launch(Dispatchers.Default) {
                      modelManagerViewModel.cleanupModel(
                        context = context,
                        task = customTask.task,
                        model = curModel,
                        instanceToCleanUp = instanceToCleanUp,
                      )
                    }
                  }
                }
              },
              disableAppBarControls = disableAppBarControls,
              hideTopBar = hideTopBar,
              useThemeColor = customTask.task.useThemeColor,
            ) { bottomPadding ->
              customTask.MainScreen(
                data =
                  CustomTaskData(
                    modelManagerViewModel = modelManagerViewModel,
                    bottomPadding = bottomPadding,
                    setAppBarControlsDisabled = { disableAppBarControls = it },
                    setTopBarVisible = { hideTopBar = !it },
                    setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                  )
              )
            }
          }
        }
      }
    }

    // Global model manager page.
    composable(
      route = ROUTE_MODEL_MANAGER,
      enterTransition = {
        if (
          initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            initialState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideUpEnter()
        }
      },
      exitTransition = {
        if (
          targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
            targetState.destination.route?.startsWith(ROUTE_MODEL) == true
        ) {
          null
        } else {
          slideDownExit()
        }
      },
    ) { backStackEntry ->
      GlobalModelManager(
        viewModel = modelManagerViewModel,
        navigateUp = {
          enableHomeScreenAnimation = false
          navController.navigateUp()
        },
        onModelSelected = { task, model ->
          navController.navigate("$ROUTE_MODEL/${task.id}/${model.name}")
        },
        onBenchmarkClicked = { model ->
          if (!BuildConfig.ENABLE_BENCHMARK_UI) {
            Log.w(TAG, "benchmark route blocked for this build model=${model.name}")
            return@GlobalModelManager
          }
          firebaseAnalytics?.logEvent(
            AnalyticsEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
          )
          navController.navigate("$ROUTE_BENCHMARK/${model.name}")
        },
      )
    }

    if (BuildConfig.ENABLE_BENCHMARK_UI) {
      // Benchmark creation page.
      composable(
        route = "$ROUTE_BENCHMARK/{modelName}",
        arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
        enterTransition = { slideEnter() },
        exitTransition = { slideExit() },
      ) { backStackEntry ->
        val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          BenchmarkScreen(
            initialModel = model,
            modelManagerViewModel = modelManagerViewModel,
            onBackClicked = {
              enableModelListAnimation = false
              navController.navigateUp()
            },
          )
        }
      }
    }
  }

  // Handle incoming intents for deep links
  handleIncomingDeepLink(
    intent = androidx.activity.compose.LocalActivity.current?.intent,
    navController = navController,
    modelManagerViewModel = modelManagerViewModel,
    routeModel = ROUTE_MODEL,
    routeModelManager = ROUTE_MODEL_MANAGER,
  )
}
