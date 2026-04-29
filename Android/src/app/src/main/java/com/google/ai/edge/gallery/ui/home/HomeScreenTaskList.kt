package selfgemma.talk.ui.home


// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import android.content.Context
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selfgemma.talk.R
import selfgemma.talk.data.BuiltInTaskId
import selfgemma.talk.data.Category
import selfgemma.talk.data.CategoryInfo
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.TaskIcon
import selfgemma.talk.ui.common.rememberDelayedAnimationProgress
import selfgemma.talk.ui.modelmanager.ModelManagerViewModel
import selfgemma.talk.ui.theme.customColors
import kotlinx.coroutines.delay

private const val TAG = "AGHomeScreenTaskList"

@Composable
internal fun TaskList(
  modelManagerViewModel: ModelManagerViewModel,
  pagerState: PagerState,
  sortedCategories: List<CategoryInfo>,
  tasksByCategories: Map<String, List<Task>>,
  enableAnimation: Boolean,
  navigateToTaskScreen: (Task) -> Unit,
  gm4: Boolean = false,
  grid: Boolean = false,
) {
  // Model list animation:
  //
  // 1.  Slide Up: The entire column of task cards translates upwards,
  // 2.  Fade in one by one: The task card fade in one by one. See TaskCard for details.
  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  // Tracks when the initial animation is done.
  //
  var initialAnimationDone by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    // Use 5 iterations to make sure all visible task cards are animated.
    delay(((TASK_CARD_ANIMATION_DURATION + TASK_CARD_ANIMATION_DELAY_OFFSET) * 5).toLong())
    initialAnimationDone = true
  }

  // The highlighted tiles at the top.
  if (gm4) {
    Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier =
        Modifier.padding(horizontal = 24.dp).graphicsLayer {
          alpha = progress
          translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
        },
    ) {
      val chatToDescription =
        mapOf(
          BuiltInTaskId.LLM_CHAT to "Chat with the latest Gemma 4 model today",
          // use "\u00a0" to make sure the word before and after it should always be together when
          // wrapping lines.
          BuiltInTaskId.LLM_AGENT_CHAT to "Have Gemma 4 complete agentic tasks for\u00A0you",
        )
      for (task in
        listOf(
          modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)!!,
          modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_AGENT_CHAT)!!,
        )) {
        TaskCard(
          task = task,
          index = 0,
          animate = !initialAnimationDone && enableAnimation,
          onClick = { navigateToTaskScreen(task) },
          modifier = Modifier.fillMaxWidth(),
          description = chatToDescription[task.id]!!,
        )
      }

      Text(
        text = "Explore other use cases",
        style =
          MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 24.sp,
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 22.dp, bottom = 16.dp),
      )
    }
  }

  HorizontalPager(
    state = pagerState,
    verticalAlignment = Alignment.Top,
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) { pageIndex ->
    val tasks = tasksByCategories[sortedCategories[pageIndex].id]!!
    if (grid) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
      ) {
        for (i in tasks.indices step 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            // First item in the row
            TaskCard(
              task = tasks[i],
              index = i,
              animate =
                (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
              onClick = { navigateToTaskScreen(tasks[i]) },
              modifier = Modifier.weight(1f),
              square = true,
            )

            // Second item in the row, if it exists
            if (i + 1 < tasks.size) {
              TaskCard(
                task = tasks[i + 1],
                index = i + 1,
                animate =
                  (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
                onClick = { navigateToTaskScreen(tasks[i + 1]) },
                modifier = Modifier.weight(1f),
                square = true,
              )
            } else {
              // Add a spacer to fill the remaining space if there's only one item in the last row
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    } else {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        for ((index, task) in tasks.withIndex()) {
          TaskCard(
            task = task,
            index = index,
            animate =
              (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
            onClick = { navigateToTaskScreen(task) },
            modifier = Modifier.fillMaxWidth(),
            square = false,
          )
        }
      }
    }
  }
}

@Composable
internal fun TaskCard(
  task: Task,
  index: Int,
  animate: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  description: String = "",
  square: Boolean = false,
) {
  // Observes the model count and updates the model count label with a fade-in/fade-out animation
  // whenever the count changes.
  val modelCount by remember {
    derivedStateOf {
      val trigger = task.updateTrigger.value
      if (trigger >= 0) {
        task.models.size
      } else {
        0
      }
    }
  }
  val modelCountLabel by remember {
    derivedStateOf {
      when (modelCount) {
        1 -> "1 Model"
        else -> "%d Models".format(modelCount)
      }
    }
  }
  var curModelCountLabel by remember { mutableStateOf("") }
  var modelCountLabelVisible by remember { mutableStateOf(true) }

  LaunchedEffect(modelCountLabel) {
    if (curModelCountLabel.isEmpty()) {
      curModelCountLabel = modelCountLabel
    } else {
      modelCountLabelVisible = false
      delay(TASK_COUNT_ANIMATION_DURATION.toLong())
      curModelCountLabel = modelCountLabel
      modelCountLabelVisible = true
    }
  }

  // Task card animation:
  //
  // This animation makes the task cards appear with a delayed fade-in effect. Each card will become
  // visible sequentially, starting after an initial delay and then with an additional offset for
  // subsequent cards.
  val progress =
    if (animate)
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START + index * TASK_CARD_ANIMATION_DELAY_OFFSET,
        animationDurationMs = TASK_CARD_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )
    else 1f

  val cbTask = stringResource(R.string.cd_task_card, task.label, task.models.size)
  Card(
    modifier =
      modifier
        .clip(RoundedCornerShape(24.dp))
        .clickable(onClick = onClick)
        .graphicsLayer { alpha = progress }
        .semantics { contentDescription = cbTask },
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (description.isNotEmpty() || square) {
            MaterialTheme.colorScheme.surfaceContainer
          } else {

            MaterialTheme.customColors.taskCardBgColor
          }
      ),
  ) {
    if (square) {
      Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        TaskIcon(task = task, width = 40.dp)
        Column() {
          Text(
            curModelCountLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            modifier = Modifier.clearAndSetSemantics {},
          )
          Text(
            task.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            task.shortDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            modifier = Modifier.clearAndSetSemantics {},
            minLines = 2,
            maxLines = 2,
            autoSize =
              TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp),
          )
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (description.isNotEmpty()) {
          // Icon.
          TaskIcon(task = task, width = 40.dp)

          // Title and description.
          Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.newFeature) {
                Box(
                  modifier =
                    Modifier.offset(y = (-6).dp, x = 6.dp)
                      .clip(RoundedCornerShape(8.dp))
                      .background(MaterialTheme.customColors.newFeatureContainerColor)
                      .padding(horizontal = 12.dp)
                      .height(26.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    "New",
                    color = MaterialTheme.customColors.newFeatureTextColor,
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }
            }
            Text(
              description,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style =
                MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
              modifier = Modifier.clearAndSetSemantics {},
            )
          }
        } else {
          // Title and model count
          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                task.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
              )
              if (task.experimental) {
                Icon(
                  painter = painterResource(R.drawable.ic_experiment),
                  contentDescription = "Experimental",
                  modifier = Modifier.size(20.dp).padding(start = 4.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Text(
              curModelCountLabel,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.clearAndSetSemantics {},
            )
          }

          // Icon.
          TaskIcon(task = task, width = 40.dp)
        }
      }
    }
  }
}

internal fun getCategoryLabel(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) {
    return context.getString(stringRes)
  } else if (label != null) {
    return label
  }
  return context.getString(R.string.category_unlabeled)
}
