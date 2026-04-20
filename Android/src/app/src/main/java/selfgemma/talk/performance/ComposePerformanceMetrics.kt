package selfgemma.talk.performance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.PerformanceMetricsState

@Composable
fun rememberMetricsStateHolder(): PerformanceMetricsState.Holder {
  val view = LocalView.current
  return remember(view) { PerformanceMetricsState.getHolderForHierarchy(view) }
}

@Composable
fun TrackPerformanceState(key: String, value: String?) {
  val holder = rememberMetricsStateHolder()

  DisposableEffect(holder, key, value) {
    val state = holder.state
    if (value.isNullOrBlank()) {
      state?.removeState(key)
    } else {
      state?.putState(key, value)
    }

    onDispose {
      state?.removeState(key)
    }
  }
}