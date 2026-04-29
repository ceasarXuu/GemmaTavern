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

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import selfgemma.talk.data.Task
import selfgemma.talk.performance.FrontendPerformanceMonitor
import selfgemma.talk.feature.roleplay.navigation.RoleplayRoutes
import selfgemma.talk.ui.home.HomeScreen
import selfgemma.talk.ui.home.PromoScreenGm4
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.delay

private const val TAG = "AGAppNavGraph"

@Composable
internal fun TrackAppForeground(
  lifecycleOwner: LifecycleOwner,
  modelManagerViewModel: ModelManagerViewModel,
) {
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
}

@Composable
internal fun ChatNavigationTimingTracker(
  currentRoute: String?,
  pendingChatEnterStartedAtMs: Long?,
  pendingChatExitStartedAtMs: Long?,
  onClearPendingEnter: () -> Unit,
  onClearPendingExit: () -> Unit,
) {
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
        onClearPendingEnter()
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
      onClearPendingExit()
    }
  }
}

@Composable
internal fun HomeScreenRouteContent(
  modelManagerViewModel: ModelManagerViewModel,
  enableHomeScreenAnimation: Boolean,
  onPickTask: (Task) -> Unit,
  onOpenModelManager: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val promoId = "gm4"
  Box(modifier = modifier.fillMaxSize()) {
    var promoDismissed by remember { mutableStateOf(false) }

    val homeScreenContent: @Composable () -> Unit = {
      HomeScreen(
        modelManagerViewModel = modelManagerViewModel,
        tosViewModel = hiltViewModel(),
        enableAnimation = enableHomeScreenAnimation,
        navigateToTaskScreen = onPickTask,
        onModelsClicked = onOpenModelManager,
        gm4 = true,
      )
    }

    if (modelManagerViewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)) {
      homeScreenContent()
    } else {
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

internal fun handleIncomingDeepLink(
  intent: android.content.Intent?,
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  routeModel: String,
  routeModelManager: String,
) {
  val data = intent?.data
  if (data != null) {
    intent.data = null
    Log.d(TAG, "navigation link clicked: $data")
    if (data.toString().startsWith("selfgemma.talk://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
          navController.navigate("$routeModel/${taskId}/${model.name}")
        }
      } else {
        Log.e(TAG, "Malformed deep link URI received: $data")
      }
    } else if (data.toString() == "selfgemma.talk://global_model_manager") {
      navController.navigate(routeModelManager)
    }
  }
}
