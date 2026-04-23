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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import selfgemma.talk.customtasks.common.CustomTaskData
import selfgemma.talk.customtasks.common.CustomTaskDataForBuiltinTask
import selfgemma.talk.data.ModelDownloadStatusType
import selfgemma.talk.data.Task
import selfgemma.talk.data.isLegacyTasks
import selfgemma.talk.firebaseAnalytics
import selfgemma.talk.performance.FrontendPerformanceMonitor
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.benchmark.BenchmarkScreen
import selfgemma.talk.ui.common.ErrorDialog
import selfgemma.talk.ui.common.ModelPageAppBar
import selfgemma.talk.ui.common.chat.ModelDownloadStatusInfoPanel
import selfgemma.talk.ui.home.HomeScreen
import selfgemma.talk.ui.home.PromoScreenGm4
import selfgemma.talk.ui.modelmanager.GlobalModelManager
import selfgemma.talk.ui.modelmanager.ModelInitializationStatusType
import selfgemma.talk.ui.modelmanager.ModelManager
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGAppNavGraph"
private const val ROUTE_HOMESCREEN = "homepage"
private const val ROUTE_MODEL_LIST = "model_list"
private const val ROUTE_MODEL = "route_model"
private const val ROUTE_BENCHMARK = "benchmark"
private const val ROUTE_MODEL_MANAGER = "model_manager"
private const val ENTER_ANIMATION_DURATION_MS = 500
private val ENTER_ANIMATION_EASING = EaseOutExpo
private const val ENTER_ANIMATION_DELAY_MS = 100
private const val EXIT_ANIMATION_DURATION_MS = 500
private val EXIT_ANIMATION_EASING = EaseOutExpo
private const val CHAT_ENTER_ANIMATION_DURATION_MS = 140
private const val CHAT_EXIT_ANIMATION_DURATION_MS = 110
private const val MAIN_TAB_TARGET_KEY = "main_tab_target"
private const val MAIN_TAB_TARGET_NONE = -1
private const val MAIN_TAB_TARGET_MESSAGES = 0

private fun enterTween(): FiniteAnimationSpec<IntOffset> {
  return tween(
    ENTER_ANIMATION_DURATION_MS,
    easing = ENTER_ANIMATION_EASING,
    delayMillis = ENTER_ANIMATION_DELAY_MS,
  )
}

private fun exitTween(): FiniteAnimationSpec<IntOffset> {
  return tween(EXIT_ANIMATION_DURATION_MS, easing = EXIT_ANIMATION_EASING)
}

private fun AnimatedContentTransitionScope<*>.slideEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Left,
  )
}

private fun AnimatedContentTransitionScope<*>.slideExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Right,
  )
}

private fun AnimatedContentTransitionScope<*>.slideUpEnter(): EnterTransition {
  return slideIntoContainer(
    animationSpec = enterTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Up,
  )
}

private fun AnimatedContentTransitionScope<*>.slideDownExit(): ExitTransition {
  return slideOutOfContainer(
    animationSpec = exitTween(),
    towards = AnimatedContentTransitionScope.SlideDirection.Down,
  )
}

private fun AnimatedContentTransitionScope<*>.chatEnter(): EnterTransition {
  return fadeIn(animationSpec = tween(durationMillis = CHAT_ENTER_ANIMATION_DURATION_MS)) +
    slideIntoContainer(
      animationSpec = tween(durationMillis = CHAT_ENTER_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
      towards = AnimatedContentTransitionScope.SlideDirection.Left,
      initialOffset = { fullSize -> fullSize / 8 },
    )
}

private fun AnimatedContentTransitionScope<*>.chatExit(): ExitTransition {
  return fadeOut(animationSpec = tween(durationMillis = CHAT_EXIT_ANIMATION_DURATION_MS)) +
    slideOutOfContainer(
      animationSpec = tween(durationMillis = CHAT_EXIT_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
      towards = AnimatedContentTransitionScope.SlideDirection.Right,
      targetOffset = { fullSize -> fullSize / 10 },
    )
}

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

  LaunchedEffect(currentRoute) {
    val currentRouteValue = currentRoute
    val isOnChatRoute = currentRouteValue == RoleplayRoutes.CHAT

    if (isOnChatRoute) {
      pendingChatEnterStartedAtMs?.let { startedAt ->
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        FrontendPerformanceMonitor.recordInteraction(
          name = "chat_navigation_enter",
          durationMs = durationMs,
        )
        Log.d(TAG, "chat navigation enter completed route=$currentRouteValue durationMs=$durationMs")
        pendingChatEnterStartedAtMs = null
      }
      return@LaunchedEffect
    }

    pendingChatExitStartedAtMs?.let { startedAt ->
      val durationMs = SystemClock.elapsedRealtime() - startedAt
      FrontendPerformanceMonitor.recordInteraction(
        name = "chat_navigation_exit",
        durationMs = durationMs,
      )
      Log.d(TAG, "chat navigation exit completed route=$currentRouteValue durationMs=$durationMs")
      pendingChatExitStartedAtMs = null
    }
  }

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> {
          /* Do nothing for other events */
        }
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

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
      // Create a state to trigger PromoScreen fade in animation.
      val promoId = "gm4"
      Box(modifier = modifier.fillMaxSize()) {
        var promoDismissed by remember { mutableStateOf(false) }

        val homeScreenContent: @Composable () -> Unit = {
          HomeScreen(
            modelManagerViewModel = modelManagerViewModel,
            tosViewModel = hiltViewModel(),
            enableAnimation = enableHomeScreenAnimation,
            navigateToTaskScreen = { task ->
              pickedTask = task
              enableModelListAnimation = true
              navController.navigate(ROUTE_MODEL_LIST)
              firebaseAnalytics?.logEvent(
                AnalyticsEvent.CAPABILITY_SELECT.id,
                Bundle().apply { putString("capability_name", task.id) },
              )
            },
            onModelsClicked = { navController.navigate(ROUTE_MODEL_MANAGER) },
            gm4 = true,
          )
        }

        // Show home page directly if promo has been viewed.
        if (modelManagerViewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)) {
          homeScreenContent()
        }
        // If the promo has not been viewed, show promo screen first.
        else {
          AnimatedContent(
            targetState = promoDismissed,
            label = "PromoToHome",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
          ) { dismissed ->
            if (dismissed) {
              homeScreenContent()
            } else {
              var startAnimation by remember { mutableStateOf(false) }
              LaunchedEffect(Unit) {
                delay(0L)
                startAnimation = true
              }
              AnimatedVisibility(
                visible = startAnimation,
                enter = scaleIn(initialScale = 1.05f, animationSpec = tween(durationMillis = 1000)),
              ) {
                PromoScreenGm4(
                  onDismiss = {
                    modelManagerViewModel.dataStoreRepository.addViewedPromoId(promoId = promoId)
                    promoDismissed = true
                  }
                )
              }
            }
          }
        }
      }
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
          firebaseAnalytics?.logEvent(
            AnalyticsEvent.CAPABILITY_SELECT.id,
            Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
          )
          navController.navigate("$ROUTE_BENCHMARK/${model.name}")
        },
      )
    }

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

  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  if (data != null) {
    intent.data = null
    Log.d(TAG, "navigation link clicked: $data")
    if (data.toString().startsWith("selfgemma.talk://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          navController.navigate("$ROUTE_MODEL/${taskId}/${model.name}")
        }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else if (data.toString() == "selfgemma.talk://global_model_manager") {
      navController.navigate(ROUTE_MODEL_MANAGER)
    }
  }
}

@Composable
private fun CustomTaskScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  disableAppBarControls: Boolean,
  hideTopBar: Boolean,
  useThemeColor: Boolean,
  onNavigateUp: () -> Unit,
  content: @Composable (bottomPadding: Dp) -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var navigatingUp by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var appBarHeight by remember { mutableIntStateOf(0) }

  val handleNavigateUp = {
    navigatingUp = true
    onNavigateUp()
  }

  // Handle system's edge swipe.
  BackHandler { handleNavigateUp() }

  // Initialize model when model/download state changes.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (!navigatingUp) {
      if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
        Log.d(
          TAG,
          "Initializing model '${selectedModel.name}' from CustomTaskScreen launched effect",
        )
        modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
      }
    }
  }

  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  LaunchedEffect(modelInitializationStatus) {
    showErrorDialog = modelInitializationStatus?.status == ModelInitializationStatusType.ERROR
  }

  Scaffold(
    topBar = {
      AnimatedVisibility(
        !hideTopBar,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
      ) {
        ModelPageAppBar(
          task = task,
          model = selectedModel,
          modelManagerViewModel = modelManagerViewModel,
          inProgress = disableAppBarControls,
          modelPreparing = disableAppBarControls,
          canShowResetSessionButton = false,
          useThemeColor = useThemeColor,
          modifier =
            Modifier.onGloballyPositioned { coordinates -> appBarHeight = coordinates.size.height },
          hideModelSelector = task.models.size <= 1,
          onConfigChanged = { _, _ -> },
          onBackClicked = { handleNavigateUp() },
          onModelSelected = { prevModel, newSelectedModel ->
            val instanceToCleanUp = prevModel.instance
            scope.launch(Dispatchers.Default) {
              // Clean up prev model.
              if (prevModel.name != newSelectedModel.name) {
                modelManagerViewModel.cleanupModel(
                  context = context,
                  task = task,
                  model = prevModel,
                  instanceToCleanUp = instanceToCleanUp,
                )
              }

              // Update selected model.
              Log.d(TAG, "from model picker. new: ${newSelectedModel.name}")
              modelManagerViewModel.selectModel(model = newSelectedModel)
            }
          },
        )
      }
    }
  ) { innerPadding ->
    // Calculate the target height in Dp for the content's top padding.
    val targetPaddingDp =
      if (!hideTopBar && appBarHeight > 0) {
        // Convert measured pixel height to Dp
        with(LocalDensity.current) { appBarHeight.toDp() }
      } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      }

    // Animate the actual top padding value.
    val animatedTopPadding by
      animateDpAsState(
        targetValue = targetPaddingDp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "TopPaddingAnimation",
      )

    Box(
      modifier =
        Modifier.padding(
          top = if (!hideTopBar) innerPadding.calculateTopPadding() else animatedTopPadding,
          start = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
          end = innerPadding.calculateStartPadding(LocalLayoutDirection.current),
        )
    ) {
      val curModelDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
      AnimatedContent(
        targetState = curModelDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) { targetState ->
        when (targetState) {
          // Main UI when model is downloaded.
          true -> content(innerPadding.calculateBottomPadding())
          // Model download
          false ->
            ModelDownloadStatusInfoPanel(
              model = selectedModel,
              task = task,
              modelManagerViewModel = modelManagerViewModel,
            )
        }
      }
    }
  }

  if (showErrorDialog) {
    ErrorDialog(
      error = modelInitializationStatus?.error ?: "",
      onDismiss = {
        showErrorDialog = false
        onNavigateUp()
      },
    )
  }
}
