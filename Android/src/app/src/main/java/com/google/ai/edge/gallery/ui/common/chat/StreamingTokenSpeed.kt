package selfgemma.talk.ui.common.chat

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import selfgemma.talk.domain.roleplay.usecase.TokenEstimator
import kotlin.math.roundToInt

private const val TOKEN_SPEED_REFRESH_INTERVAL_MS = 1_000L
private const val TOKEN_SPEED_STALE_AFTER_MS = 1_000L
private const val TOKEN_SPEED_COMPLETION_GRACE_MS = 3_000L

@Composable
fun rememberStreamingTokenSpeed(
  streamingText: String,
  isStreaming: Boolean,
  completedText: String = "",
  completedLatencyMs: Double? = null,
  completedAtEpochMs: Long? = null,
): Int? {
  val tokenEstimator = remember { TokenEstimator() }
  var startedAtElapsed by remember { mutableLongStateOf(0L) }
  var lastOutputAtElapsed by remember { mutableLongStateOf(0L) }
  var latestTokenCount by remember { mutableIntStateOf(0) }
  var tokenSpeed by remember { mutableStateOf<Int?>(null) }

  LaunchedEffect(isStreaming) {
    if (!isStreaming) {
      startedAtElapsed = 0L
      lastOutputAtElapsed = 0L
      latestTokenCount = 0
    }
  }

  LaunchedEffect(streamingText, isStreaming) {
    if (!isStreaming) {
      return@LaunchedEffect
    }

    val estimatedTokenCount = tokenEstimator.estimate(streamingText)
    if (estimatedTokenCount <= 0) {
      return@LaunchedEffect
    }

    val now = SystemClock.elapsedRealtime()
    if (startedAtElapsed == 0L) {
      startedAtElapsed = now
    }
    latestTokenCount = estimatedTokenCount
    lastOutputAtElapsed = now
    tokenSpeed =
      calculateTokenSpeed(
        tokenCount = latestTokenCount,
        startedAtElapsed = startedAtElapsed,
        nowElapsed = now,
      )
  }

  LaunchedEffect(isStreaming, startedAtElapsed, latestTokenCount, lastOutputAtElapsed) {
    if (!isStreaming || startedAtElapsed == 0L) {
      return@LaunchedEffect
    }

    while (true) {
      val now = SystemClock.elapsedRealtime()
      tokenSpeed =
        if (
          latestTokenCount > 0 &&
            now - lastOutputAtElapsed < TOKEN_SPEED_STALE_AFTER_MS
        ) {
          calculateTokenSpeed(
            tokenCount = latestTokenCount,
            startedAtElapsed = startedAtElapsed,
            nowElapsed = now,
          )
        } else {
          null
        }
      delay(TOKEN_SPEED_REFRESH_INTERVAL_MS)
    }
  }

  LaunchedEffect(completedText, completedLatencyMs, completedAtEpochMs, isStreaming) {
    if (isStreaming) {
      return@LaunchedEffect
    }

    val completionTime = completedAtEpochMs ?: run {
      tokenSpeed = null
      return@LaunchedEffect
    }
    val latencyMs = completedLatencyMs ?: run {
      tokenSpeed = null
      return@LaunchedEffect
    }
    if (completedText.isBlank() || latencyMs <= 0.0) {
      tokenSpeed = null
      return@LaunchedEffect
    }

    val ageMs = System.currentTimeMillis() - completionTime
    if (ageMs !in 0..TOKEN_SPEED_COMPLETION_GRACE_MS) {
      tokenSpeed = null
      return@LaunchedEffect
    }

    val completedTokenCount = tokenEstimator.estimate(completedText)
    tokenSpeed =
      calculateAverageTokenSpeed(
        tokenCount = completedTokenCount,
        latencyMs = latencyMs,
      )
    delay((TOKEN_SPEED_COMPLETION_GRACE_MS - ageMs).coerceAtLeast(0))
    tokenSpeed = null
  }

  return tokenSpeed
}

private fun calculateTokenSpeed(
  tokenCount: Int,
  startedAtElapsed: Long,
  nowElapsed: Long,
): Int? {
  val elapsedMs = nowElapsed - startedAtElapsed
  if (elapsedMs < TOKEN_SPEED_REFRESH_INTERVAL_MS) {
    return null
  }

  val tokensPerSecond = tokenCount / (elapsedMs / 1000.0)
  return maxOf(1, tokensPerSecond.roundToInt())
}

private fun calculateAverageTokenSpeed(
  tokenCount: Int,
  latencyMs: Double,
): Int? {
  if (tokenCount <= 0 || latencyMs <= 0.0) {
    return null
  }
  val tokensPerSecond = tokenCount / (latencyMs / 1000.0)
  return maxOf(1, tokensPerSecond.roundToInt())
}
