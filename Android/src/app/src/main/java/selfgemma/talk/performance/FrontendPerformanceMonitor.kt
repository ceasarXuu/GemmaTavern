package selfgemma.talk.performance

import android.os.SystemClock
import android.util.Log
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.StateInfo

private const val TAG = "FrontendPerf"
private const val MAX_FRAME_BUCKET_MS = 2000
private const val MAX_INTERACTION_BUCKET_MS = 5000

object FrontendPerformanceMonitor {
  private val lock = Any()

  private var foregroundSessionActive = false
  private var foregroundSessionReason = "inactive"
  private var foregroundSessionStartedAtMs = 0L

  private var overallFrames = FrameAccumulator()
  private var framesByScope = linkedMapOf<String, FrameAccumulator>()
  private var interactionsByName = linkedMapOf<String, InteractionAccumulator>()

  fun startForegroundSession(reason: String) {
    synchronized(lock) {
      if (foregroundSessionActive) {
        return
      }

      foregroundSessionActive = true
      foregroundSessionReason = reason
      foregroundSessionStartedAtMs = SystemClock.elapsedRealtime()
      resetSamplesLocked()
    }
  }

  fun endForegroundSession(reason: String) {
    val summary = synchronized(lock) {
      if (!foregroundSessionActive) {
        return
      }

      val builtSummary = buildSummaryLocked(reason)
      foregroundSessionActive = false
      foregroundSessionReason = "inactive"
      foregroundSessionStartedAtMs = 0L
      resetSamplesLocked()
      builtSummary
    }

    Log.i(TAG, summary)
  }

  fun recordFrame(frameData: FrameData) {
    val durationMs = nanosToRoundedMillis(frameData.frameDurationUiNanos)
    val scope = extractScope(frameData.states)

    synchronized(lock) {
      if (!foregroundSessionActive) {
        return
      }

      overallFrames.record(durationMs = durationMs, isJank = frameData.isJank)
      framesByScope.getOrPut(scope) { FrameAccumulator() }.record(
        durationMs = durationMs,
        isJank = frameData.isJank,
      )
    }
  }

  fun recordInteraction(name: String, durationMs: Long) {
    synchronized(lock) {
      if (!foregroundSessionActive) {
        return
      }

      val bucket = durationMs.coerceIn(0L, MAX_INTERACTION_BUCKET_MS.toLong()).toInt()
      interactionsByName.getOrPut(name) { InteractionAccumulator() }.record(bucket)
    }
  }

  private fun buildSummaryLocked(reason: String): String {
    val sessionDurationMs =
      if (foregroundSessionStartedAtMs == 0L) {
        0L
      } else {
        SystemClock.elapsedRealtime() - foregroundSessionStartedAtMs
      }

    if (overallFrames.totalFrames == 0L) {
      return buildString {
        append("reason=")
        append(reason)
        append(" session=")
        append(foregroundSessionReason)
        append(" durationMs=")
        append(sessionDurationMs)
        append(" no-frame-samples")
      }
    }

    val worstScopes =
      framesByScope.entries
        .sortedWith(
          compareByDescending<Map.Entry<String, FrameAccumulator>> { it.value.jankRate }
            .thenByDescending { it.value.p95 }
            .thenByDescending { it.value.maxFrameMs },
        )
        .take(3)
        .joinToString(separator = "; ") { (scope, accumulator) ->
          "$scope{${accumulator.summary(isScrollableScope = scope.contains("scrolling"))}}"
        }

    val interactionSummary =
      interactionsByName.entries
        .sortedWith(
          compareByDescending<Map.Entry<String, InteractionAccumulator>> { it.value.p95 }
            .thenByDescending { it.value.maxDurationMs },
        )
        .joinToString(separator = "; ") { (name, accumulator) ->
          "$name{${accumulator.summary()}}"
        }

    return buildString {
      append("reason=")
      append(reason)
      append(" session=")
      append(foregroundSessionReason)
      append(" durationMs=")
      append(sessionDurationMs)
      append(" overall{")
      append(overallFrames.summary(isScrollableScope = false))
      append('}')
      if (worstScopes.isNotEmpty()) {
        append(" hotspots=")
        append(worstScopes)
      }
      if (interactionSummary.isNotEmpty()) {
        append(" interactions=")
        append(interactionSummary)
      }
    }
  }

  private fun resetSamplesLocked() {
    overallFrames = FrameAccumulator()
    framesByScope = linkedMapOf()
    interactionsByName = linkedMapOf()
  }
}

private class FrameAccumulator {
  private val histogram = IntArray(MAX_FRAME_BUCKET_MS + 1)

  var totalFrames = 0L
    private set
  private var jankFrames = 0L
  private var slowFrames = 0L
  private var frozenFrames = 0L
  var maxFrameMs = 0
    private set

  val jankRate: Double
    get() = rateOf(jankFrames, totalFrames)

  val slowRate: Double
    get() = rateOf(slowFrames, totalFrames)

  val p50: Int
    get() = percentile(0.50)

  val p95: Int
    get() = percentile(0.95)

  val p99: Int
    get() = percentile(0.99)

  fun record(durationMs: Int, isJank: Boolean) {
    val clampedDuration = durationMs.coerceIn(0, MAX_FRAME_BUCKET_MS)
    totalFrames += 1
    histogram[clampedDuration] += 1
    if (isJank) {
      jankFrames += 1
    }
    if (clampedDuration > FrontendPerformanceTargets.slowFrameBudgetMs) {
      slowFrames += 1
    }
    if (clampedDuration > FrontendPerformanceTargets.frozenFrameBudgetMs) {
      frozenFrames += 1
    }
    if (clampedDuration > maxFrameMs) {
      maxFrameMs = clampedDuration
    }
  }

  fun summary(isScrollableScope: Boolean): String {
    val targetJankRate =
      if (isScrollableScope) {
        FrontendPerformanceTargets.maxScrollableJankRate
      } else {
        FrontendPerformanceTargets.maxForegroundJankRate
      }
    val verdict =
      if (
        jankRate <= targetJankRate &&
          slowRate <= FrontendPerformanceTargets.maxForegroundSlowFrameRate &&
          frozenFrames <= FrontendPerformanceTargets.maxForegroundFrozenFrames &&
          p95 <= FrontendPerformanceTargets.maxForegroundP95FrameMs &&
          p99 <= FrontendPerformanceTargets.maxForegroundP99FrameMs
      ) {
        "PASS"
      } else {
        "FAIL"
      }

    return buildString {
      append("frames=")
      append(totalFrames)
      append(" jank=")
      append(formatRate(jankFrames, totalFrames))
      append(" slow=")
      append(formatRate(slowFrames, totalFrames))
      append(" frozen=")
      append(frozenFrames)
      append(" p50=")
      append(p50)
      append("ms p95=")
      append(p95)
      append("ms p99=")
      append(p99)
      append("ms max=")
      append(maxFrameMs)
      append("ms verdict=")
      append(verdict)
    }
  }

  private fun percentile(percentile: Double): Int {
    if (totalFrames == 0L) {
      return 0
    }

    val targetRank = kotlin.math.ceil(totalFrames * percentile).toLong().coerceAtLeast(1L)
    var cumulative = 0L
    for (bucket in histogram.indices) {
      cumulative += histogram[bucket]
      if (cumulative >= targetRank) {
        return bucket
      }
    }
    return MAX_FRAME_BUCKET_MS
  }
}

private class InteractionAccumulator {
  private val histogram = IntArray(MAX_INTERACTION_BUCKET_MS + 1)

  private var totalCount = 0L
  var maxDurationMs = 0
    private set

  val p50: Int
    get() = percentile(0.50)

  val p95: Int
    get() = percentile(0.95)

  val p99: Int
    get() = percentile(0.99)

  fun record(durationMs: Int) {
    val clampedDuration = durationMs.coerceIn(0, MAX_INTERACTION_BUCKET_MS)
    totalCount += 1
    histogram[clampedDuration] += 1
    if (clampedDuration > maxDurationMs) {
      maxDurationMs = clampedDuration
    }
  }

  fun summary(): String {
    val verdict =
      if (
        p95 <= FrontendPerformanceTargets.maxInteractionP95Ms &&
          p99 <= FrontendPerformanceTargets.maxInteractionP99Ms
      ) {
        "PASS"
      } else {
        "FAIL"
      }

    return buildString {
      append("count=")
      append(totalCount)
      append(" p50=")
      append(p50)
      append("ms p95=")
      append(p95)
      append("ms p99=")
      append(p99)
      append("ms max=")
      append(maxDurationMs)
      append("ms verdict=")
      append(verdict)
    }
  }

  private fun percentile(percentile: Double): Int {
    if (totalCount == 0L) {
      return 0
    }

    val targetRank = kotlin.math.ceil(totalCount * percentile).toLong().coerceAtLeast(1L)
    var cumulative = 0L
    for (bucket in histogram.indices) {
      cumulative += histogram[bucket]
      if (cumulative >= targetRank) {
        return bucket
      }
    }
    return MAX_INTERACTION_BUCKET_MS
  }
}

private fun nanosToRoundedMillis(nanos: Long): Int {
  if (nanos <= 0L) {
    return 0
  }

  return ((nanos + 999_999L) / 1_000_000L).coerceAtMost(MAX_FRAME_BUCKET_MS.toLong()).toInt()
}

private fun extractScope(states: List<StateInfo>): String {
  var route: String? = null
  var mainTab: String? = null
  var listState: String? = null

  for (state in states) {
    when (state.key) {
      "Route" -> route = state.value
      "MainTab" -> mainTab = state.value
      "SessionsList", "RoleCatalogList", "RoleplayChatList" -> listState = "${state.key}=${state.value}"
    }
  }

  return listOfNotNull(
      route?.let { "Route=$it" },
      mainTab?.let { "MainTab=$it" },
      listState,
    )
    .joinToString(separator = "|")
    .ifBlank { "Route=unknown" }
}

private fun formatRate(numerator: Long, denominator: Long): String {
  return "$numerator (${String.format("%.1f", rateOf(numerator, denominator) * 100)}%)"
}

private fun rateOf(numerator: Long, denominator: Long): Double {
  if (denominator == 0L) {
    return 0.0
  }
  return numerator.toDouble() / denominator.toDouble()
}