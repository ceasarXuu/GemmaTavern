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

package selfgemma.talk.ui.home

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import selfgemma.talk.AppTopBar
import selfgemma.talk.R
import selfgemma.talk.data.AppBarAction
import selfgemma.talk.data.AppBarActionType
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Category
import selfgemma.talk.data.CategoryInfo
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.RevealingText
import selfgemma.talk.ui.common.SwipingText
import selfgemma.talk.ui.common.TaskIcon
import selfgemma.talk.ui.common.buildTrackableUrlAnnotatedString
import selfgemma.talk.ui.common.rememberDelayedAnimationProgress
import selfgemma.talk.ui.common.tos.AppTosDialog
import selfgemma.talk.ui.common.tos.TosViewModel
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.theme.customColors
import selfgemma.talk.ui.theme.homePageTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreen"
internal const val TASK_COUNT_ANIMATION_DURATION = 250
internal const val ANIMATION_INIT_DELAY = 0L
internal const val TOP_APP_BAR_ANIMATION_DURATION = 600
internal const val TITLE_FIRST_LINE_ANIMATION_DURATION = 600
internal const val TITLE_SECOND_LINE_ANIMATION_DURATION = 600
internal const val TITLE_SECOND_LINE_ANIMATION_DURATION2 = 800
internal const val TITLE_SECOND_LINE_ANIMATION_START =
  ANIMATION_INIT_DELAY + (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.5).toInt()
internal const val TASK_LIST_ANIMATION_START = TITLE_SECOND_LINE_ANIMATION_START + 110
internal const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
internal const val TASK_CARD_ANIMATION_DURATION = 600
internal const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
internal const val CONTENT_COMPOSABLES_OFFSET_Y = 16

/** Navigation destination data */
private object HomeScreenDestination {
  @StringRes val titleRes = R.string.app_name
}

private val PREDEFINED_CATEGORY_ORDER = listOf(Category.LLM.id, Category.EXPERIMENTAL.id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  onModelsClicked: () -> Unit,
  enableAnimation: Boolean,
  modifier: Modifier = Modifier,
  gm4: Boolean = false,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  // NOTE:
  // This dialog is the home-screen drawer settings entry for the legacy gallery surface.
  // It is NOT the settings page shown by the roleplay bottom "Settings" tab.
  // Roleplay tab settings are rendered by selfgemma.talk.feature.roleplay.settings.RoleplaySettingsScreen.
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val isDevBuild = context.packageName.endsWith(".dev")

  var tasks = uiState.tasks

  val categoryMap: Map<String, CategoryInfo> =
    remember(tasks) { tasks.associateBy { it.category.id }.mapValues { it.value.category } }
  val sortedCategories =
    remember(categoryMap) {
      categoryMap.keys
        .toList()
        .sortedWith { a, b ->
          val indexA = PREDEFINED_CATEGORY_ORDER.indexOf(a)
          val indexB = PREDEFINED_CATEGORY_ORDER.indexOf(b)
          // Check if both categories are in the predefined order
          if (indexA != -1 && indexB != -1) {
            indexA.compareTo(indexB)
          }
          // Check if only category 'a' is in the predefined order
          else if (indexA != -1) {
            -1
          }
          // Check if only category 'b' is in the predefined order
          else if (indexB != -1) {
            1
          }
          // If neither is in the predefined order, sort by label
          else {
            val ca = categoryMap[a]!!
            val cb = categoryMap[b]!!
            val caLabel = getCategoryLabel(context = context, category = ca)
            val cbLabel = getCategoryLabel(context = context, category = cb)
            caLabel.compareTo(cbLabel)
          }
        }
        .map { categoryMap[it]!! }
    }

  // Show home screen content when TOS has been accepted.
  if (!showTosDialog) {
    // The code below manages the display of the model allowlist loading indicator with a debounced
    // delay. It ensures that a progress indicator is only shown if the loading operation
    // (represented by `uiState.loadingModelAllowlist`) takes longer than 200 milliseconds.
    // If the loading completes within 200ms, the indicator is never shown,
    // preventing a "flicker" and improving the perceived responsiveness of the UI.
    // The `loadingModelAllowlistDelayed` state is used to control the actual
    // visibility of the indicator based on this debounced logic.
    var loadingModelAllowlistDelayed by remember { mutableStateOf(false) }
    // This effect runs whenever uiState.loadingModelAllowlist changes
    LaunchedEffect(uiState.loadingModelAllowlist) {
      if (uiState.loadingModelAllowlist) {
        // If loading starts, wait for 200ms
        delay(200)
        // After 200ms, check if loadingModelAllowlist is still true
        if (uiState.loadingModelAllowlist) {
          loadingModelAllowlistDelayed = true
        }
      } else {
        // If loading finishes, immediately hide the indicator
        loadingModelAllowlistDelayed = false
      }
    }

    // Label and spinner to show when in the process of loading model allowlist.
    if (loadingModelAllowlistDelayed) {
      Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.padding(end = 8.dp).size(20.dp),
        )
        Text(
          stringResource(R.string.loading_model_list),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    // Main UI when allowlist is done loading.
    if (!loadingModelAllowlistDelayed && !uiState.loadingModelAllowlist) {
      val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

      val requestPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
          isGranted: Boolean ->
          if (isGranted) {
            // FCM SDK (and your app) can post notifications.
          }
        }

      LaunchedEffect(Unit) {
        delay(2000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
          ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }
      }

      // Close the menu when back button is pressed.
      BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

      ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
          ModalDrawerSheet {
            Column(modifier = Modifier.padding(16.dp)) {
              Row(modifier = Modifier.fillMaxWidth()) {
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_settings_label),
                  description = stringResource(R.string.drawer_settings_description),
                  icon = Icons.Rounded.Settings,
                  onClick = {
                    showSettingsDialog = true
                    scope.launch { drawerState.close() }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[2][0],
                          MaterialTheme.customColors.taskBgGradientColors[2][1],
                        )
                    ),
                )
                Spacer(modifier = Modifier.width(16.dp))
                SquareDrawerItem(
                  label = stringResource(R.string.drawer_models_label),
                  description = stringResource(R.string.drawer_models_description),
                  icon = Icons.AutoMirrored.Rounded.ListAlt,
                  onClick = {
                    scope.launch { drawerState.close() }
                    scope.launch {
                      delay(50)
                      onModelsClicked()
                    }
                  },
                  modifier = Modifier.weight(1f),
                  iconBrush =
                    linearGradient(
                      colors =
                        listOf(
                          MaterialTheme.customColors.taskBgGradientColors[1][0],
                          MaterialTheme.customColors.taskBgGradientColors[1][1],
                        )
                    ),
                )
              }
            }
          }
        },
        gesturesEnabled = drawerState.isOpen,
      ) {
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          topBar = {
            // Top bar animation:
            //
            // Fade in and move down at the same time.
            val progress =
              if (!enableAnimation) 1f
              else
                rememberDelayedAnimationProgress(
                  initialDelay = ANIMATION_INIT_DELAY - 50,
                  animationDurationMs = TOP_APP_BAR_ANIMATION_DURATION,
                  animationLabel = "top bar",
                )
            Box(
              modifier =
                Modifier.graphicsLayer {
                  alpha = progress
                  translationY = ((-16).dp * (1 - progress)).toPx()
                }
            ) {
              AppTopBar(
                title = stringResource(HomeScreenDestination.titleRes),
                leftAction =
                  AppBarAction(
                    actionType = AppBarActionType.MENU,
                    actionFn = {
                      scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                    },
                  ),
              )
            }
          },
        ) { innerPadding ->
          // Outer box for coloring the background edge to edge.
          Box(
            contentAlignment = Alignment.TopCenter,
            modifier =
              Modifier.fillMaxSize()
                .background(
                  if (gm4) {
                    MaterialTheme.colorScheme.surface
                  } else {
                    MaterialTheme.colorScheme.surfaceContainer
                  }
                ),
          ) {
            // Inner box to hold content.
            Box(
              contentAlignment = Alignment.TopCenter,
              modifier =
                Modifier.fillMaxSize()
                  .padding(top = innerPadding.calculateTopPadding())
                  .verticalScroll(rememberScrollState()),
            ) {
              // Background star at top.
              if (gm4) {
                val progress =
                  if (!enableAnimation) {
                    1f
                  } else {
                    rememberDelayedAnimationProgress(
                      initialDelay = ANIMATION_INIT_DELAY,
                      animationDurationMs = 2000,
                      animationLabel = "bg star",
                    )
                  }
                val configuration = LocalConfiguration.current
                val screenWidth = configuration.screenWidthDp.dp
                val targetWidth = screenWidth * 1.5f
                Image(
                  painter = painterResource(id = R.drawable.bg_star),
                  contentDescription = null,
                  modifier =
                    Modifier.requiredWidth(targetWidth)
                      .blur(radius = 35.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                      .offset(x = screenWidth * 0.25f, y = -screenWidth * 0.1f)
                      .graphicsLayer {
                        rotationZ = (1f - progress) * 40f
                        scaleX = 0.4f + 0.6f * progress
                        scaleY = 0.4f + 0.6f * progress
                        alpha = progress * 2f
                      },
                  contentScale = ContentScale.Crop,
                  colorFilter = ColorFilter.tint(MaterialTheme.customColors.bgStarColor),
                )
              }

              Column(modifier = Modifier.fillMaxWidth()) {
                var selectedCategoryIndex by remember { mutableIntStateOf(0) }

                // App title and intro text.
                Column(
                  modifier =
                    Modifier.padding(
                        horizontal = if (gm4) 24.dp else 40.dp,
                        vertical = if (gm4) 0.dp else 48.dp,
                      )
                      .padding(top = 24.dp, bottom = 16.dp)
                      .semantics(mergeDescendants = true) {},
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  if (gm4) {
                    AppTitleGm4(enableAnimation = enableAnimation)
                  } else {
                    AppTitle(enableAnimation = enableAnimation)
                  }
                  IntroText(enableAnimation = enableAnimation, gm4 = gm4)
                  if (gm4) {
                    TryGm4IntroText(enableAnimation = enableAnimation)
                  }
                }

                // Tab header for categories.
                //
                // synchronizes the `pagerState` and the `selectedCategoryIndex` to ensure that
                //  both the tab header and the task list always show the correct category and page.
                val pagerState = rememberPagerState(pageCount = { sortedCategories.size })
                LaunchedEffect(pagerState.settledPage) {
                  selectedCategoryIndex = pagerState.settledPage
                }
                if (sortedCategories.size > 1) {
                  CategoryTabHeader(
                    sortedCategories = sortedCategories,
                    selectedIndex = selectedCategoryIndex,
                    enableAnimation = enableAnimation,
                    onCategorySelected = { index ->
                      selectedCategoryIndex = index
                      scope.launch { pagerState.animateScrollToPage(page = index) }
                    },
                  )
                }

                // Task list in a horizontal pager. Each page shows the list of tasks for the
                // category.
                val grid = gm4
                TaskList(
                  modelManagerViewModel = modelManagerViewModel,
                  pagerState = pagerState,
                  sortedCategories = sortedCategories,
                  tasksByCategories = uiState.tasksByCategory,
                  enableAnimation = enableAnimation,
                  navigateToTaskScreen = navigateToTaskScreen,
                  gm4 = gm4,
                  grid = grid,
                )

                Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding() + 10.dp))
              }
            }

            // Gradient overlay at the bottom.
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .height(innerPadding.calculateBottomPadding())
                  .background(
                    Brush.verticalGradient(
                      colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
                    )
                  )
                  .align(Alignment.BottomCenter)
            )
          }
        }
      }
    }
  }

  // Show TOS dialog for users to accept.
  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  // Settings dialog.
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }

  if (uiState.loadingModelAllowlistError.isNotEmpty()) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = { Text(uiState.loadingModelAllowlistError) },
      text = { Text("Please check your internet connection and try again later.") },
      onDismissRequest = { modelManagerViewModel.loadModelAllowlist() },
      confirmButton = {
        TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) { Text("Retry") }
      },
      dismissButton = {
        TextButton(onClick = { modelManagerViewModel.clearLoadModelAllowlistError() }) {
          Text("Cancel")
        }
      },
    )
  }
}


