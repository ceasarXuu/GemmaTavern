package selfgemma.talk.feature.roleplay.chat

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

private const val TAG = "RoleplayChatScroll"

@Composable
internal fun RoleplayChatScrollEffects(
  sessionId: String?,
  imeBottom: Int,
  latestListItemIndex: Int,
  timelineItemCount: Int,
  lastTimelineStableId: String?,
  lastMessageStatusKey: String?,
  hasCompletedInitialPositioning: Boolean,
  hasLoggedInitialPositioning: Boolean,
  previousTimelineItemCount: Int,
  screenOpenTimestamp: Long,
  listState: LazyListState,
  onMarkInitialPositioned: () -> Unit,
  onMarkLoggedInitialPositioning: () -> Unit,
  onUpdatePreviousTimelineItemCount: (Int) -> Unit,
) {
  LaunchedEffect(imeBottom, latestListItemIndex, hasCompletedInitialPositioning) {
    if (
      hasCompletedInitialPositioning &&
        imeBottom > 0 &&
        latestListItemIndex >= 0 &&
        shouldKeepLatestMessageVisible(listState, latestListItemIndex)
    ) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
    }
  }

  LaunchedEffect(latestListItemIndex, timelineItemCount) {
    if (latestListItemIndex < 0) {
      onUpdatePreviousTimelineItemCount(0)
      return@LaunchedEffect
    }

    if (!hasCompletedInitialPositioning) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
      onMarkInitialPositioned()
      onUpdatePreviousTimelineItemCount(timelineItemCount)
      if (!hasLoggedInitialPositioning) {
        onMarkLoggedInitialPositioning()
        Log.d(
          TAG,
          "initial chat positioned sessionId=$sessionId itemCount=$timelineItemCount elapsed=${SystemClock.elapsedRealtime() - screenOpenTimestamp}ms",
        )
      }
      return@LaunchedEffect
    }

    val timelineItemCountIncreased = timelineItemCount > previousTimelineItemCount
    onUpdatePreviousTimelineItemCount(timelineItemCount)
    if (timelineItemCountIncreased) {
      Log.d(
        TAG,
        "auto scroll to latest after timeline append sessionId=$sessionId itemCount=$timelineItemCount latestItemIndex=$latestListItemIndex",
      )
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = true)
    }
  }

  LaunchedEffect(lastTimelineStableId, lastMessageStatusKey, hasCompletedInitialPositioning) {
    if (
      hasCompletedInitialPositioning &&
        !listState.isScrollInProgress &&
        latestListItemIndex >= 0 &&
        shouldKeepLatestMessageVisible(listState, latestListItemIndex)
    ) {
      scrollToItem(listState = listState, itemIndex = latestListItemIndex, animate = false)
    }
  }
}
