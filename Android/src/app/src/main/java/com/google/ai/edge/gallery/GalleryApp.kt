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

package selfgemma.talk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import selfgemma.talk.performance.TrackPerformanceState
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.navigation.AppNavHost

/** Top level composable representing the main screen of the application. */
@Composable
fun SelfGemmaTalkApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  val backStackEntry by navController.currentBackStackEntryAsState()

  TrackPerformanceState(
    key = "Route",
    value = backStackEntry?.destination?.route ?: ROLEPLAY_BOOTSTRAP_ROUTE,
  )

  AppNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
}

private const val ROLEPLAY_BOOTSTRAP_ROUTE = "roleplay_bootstrap"
