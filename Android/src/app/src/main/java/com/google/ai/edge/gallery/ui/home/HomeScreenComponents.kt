package selfgemma.talk.ui.home


// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selfgemma.talk.R
import selfgemma.talk.data.Category
import selfgemma.talk.data.CategoryInfo
import selfgemma.talk.data.Task
import selfgemma.talk.ui.common.RevealingText
import selfgemma.talk.ui.common.SwipingText
import selfgemma.talk.ui.common.buildTrackableUrlAnnotatedString
import selfgemma.talk.ui.common.rememberDelayedAnimationProgress
import selfgemma.talk.ui.theme.customColors
import selfgemma.talk.ui.theme.homePageTitleStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGHomeScreenComponents"

@Composable
internal fun AppTitle(enableAnimation: Boolean) {
  val firstLineText = stringResource(R.string.app_name_first_part)
  val secondLineText = stringResource(R.string.app_name_second_part)
  val titleColor = MaterialTheme.customColors.appTitleGradientColors[1]
  val screenWidthInDp = LocalConfiguration.current.screenWidthDp.dp
  val fontSize = with(LocalDensity.current) { (screenWidthInDp.toPx() * 0.12f).toSp() }
  val titleStyle = homePageTitleStyle.copy(fontSize = fontSize, lineHeight = fontSize)

  // First line text "Google AI" and its animation.
  //
  // The animation starts with the first line of text swiping in from left to right, progressively
  // revealing itself in the title color (blue). Then, after a brief delay, the exact same text, but
  // in the onSurface color (which is black in light mode), begins its own left-to-right swiping
  // animation. This second animation is positioned directly on top of the first, appearing just as
  // the initial reveal is finishing or has just completed, creating a layered and dynamic visual
  // effect.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = ANIMATION_INIT_DELAY
    if (enableAnimation) {
      SwipingText(
        text = firstLineText,
        style = titleStyle,
        color = titleColor,
        animationDelay = delay,
        animationDurationMs = TITLE_FIRST_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.3).toLong()
    }
    SwipingText(
      text = firstLineText,
      style = titleStyle,
      color = MaterialTheme.colorScheme.onSurface,
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_FIRST_LINE_ANIMATION_DURATION else 0,
    )
  }
  // Second line text and its animation.
  //
  // The initial animation is the same as the first line text. Right before it is done, the final
  // text with a gradient is revealed.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = TITLE_SECOND_LINE_ANIMATION_START
    if (enableAnimation) {
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = titleColor,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.3).toInt()
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.6).toInt()
    }
    RevealingText(
      text = secondLineText,
      style =
        titleStyle.copy(
          brush = linearGradient(colors = MaterialTheme.customColors.appTitleGradientColors)
        ),
      modifier = Modifier.offset(x = (-16).dp, y = (-16).dp),
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_SECOND_LINE_ANIMATION_DURATION2 else 0,
    )
  }
}

@Composable
fun AppTitleGm4(enableAnimation: Boolean) {
  val text1 = "SelfGemma"
  val text2 = "Talk"
  val annotatedText = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(text1) }
    append(" ")
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(text2) }
  }

  RevealingText(
    text = "",
    annotatedText = annotatedText,
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
    animationDelay = 0,
    animationDurationMs =
      if (enableAnimation) {
        (TITLE_FIRST_LINE_ANIMATION_DURATION + TITLE_SECOND_LINE_ANIMATION_DURATION)
      } else {
        0
      },
    extraTextPadding = 0.dp,
  )
}

@Composable
internal fun IntroText(enableAnimation: Boolean, gm4: Boolean) {
  val litertUrl = "https://huggingface.co/litert-community"

  // Intro text animation:
  //
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }

  val introText = buildAnnotatedString {
    val gemma4Url = "https://ai.google.dev/gemma"
    if (gm4) {
      append("Discover the power of on-device AI models from the ")
      append(buildTrackableUrlAnnotatedString(url = litertUrl, linkText = "LiteRT community"))
      append(", featuring the all-new ")
      append(buildTrackableUrlAnnotatedString(url = gemma4Url, linkText = "Gemma 4"))
      append(".")
    } else {
      append("${stringResource(R.string.app_intro)} ")
      append(
        buildTrackableUrlAnnotatedString(
          url = litertUrl,
          linkText = stringResource(R.string.litert_community_label),
        )
      )
    }
  }
  Text(
    introText,
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}

@Composable
internal fun TryGm4IntroText(enableAnimation: Boolean) {
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }
  Row(
    modifier =
      Modifier.padding(top = 24.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      ImageVector.vectorResource(R.drawable.gemma_logo),
      contentDescription = null,
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = "Try Gemma 4 today",
      style =
        MaterialTheme.typography.headlineSmall.copy(
          fontWeight = FontWeight.Medium,
          fontSize = 20.sp,
          lineHeight = 24.sp,
        ),
      color = MaterialTheme.colorScheme.onSurface,
    )
  }

  Text(
    "Gemma 4 E2B & E4B are here! Try them in AI Chat, Agent Skills, or the use cases below.",
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}

@Composable
internal fun CategoryTabHeader(
  sortedCategories: List<CategoryInfo>,
  selectedIndex: Int,
  enableAnimation: Boolean,
  onCategorySelected: (Int) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  LazyRow(
    state = listState,
    modifier =
      Modifier.fillMaxWidth().padding(bottom = 32.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item(key = "spacer_start") { Spacer(modifier = Modifier.width(8.dp)) }
    itemsIndexed(items = sortedCategories) { index, category ->
      Row(
        modifier =
          Modifier.height(40.dp)
            .clip(CircleShape)
            .background(
              color =
                if (selectedIndex == index) MaterialTheme.customColors.tabHeaderBgColor
                else Color.Transparent
            )
            .clickable {
              onCategorySelected(index)

              // Scroll to clicked item when the item is not fully inside view.
              scope.launch {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val targetItem = visibleItems.find {
                  // +1 because the first item is the item keyed at spacer_start.
                  it.index == index + 1
                }
                if (
                  targetItem == null ||
                    targetItem.offset < 0 ||
                    targetItem.offset + targetItem.size > listState.layoutInfo.viewportSize.width
                ) {
                  listState.animateScrollToItem(index = index)
                }
              }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Text(
          getCategoryLabel(context = context, category = category),
          modifier = Modifier.padding(horizontal = 16.dp),
          style = MaterialTheme.typography.labelLarge,
          color =
            if (selectedIndex == index) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    item(key = "spacer_end") { Spacer(modifier = Modifier.width(8.dp)) }
  }
}

