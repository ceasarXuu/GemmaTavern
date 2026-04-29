/*
 * Copyright 2026 Google LLC
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

package selfgemma.talk.ui.benchmark

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import kotlin.math.abs
import selfgemma.talk.proto.LlmBenchmarkResult
import selfgemma.talk.proto.ValueSeries
import selfgemma.talk.ui.theme.customColors

@Composable
internal fun StatRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  unit: String = "",
  baselineValue: Double? = null,
  lessIsBetter: Boolean = false,
) {
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(0.6f),
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
    )
    Column(
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.Start,
      modifier = Modifier.weight(0.4f),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          value,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
        AnimatedContent(
          baselineValue,
          contentAlignment = Alignment.CenterStart,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { curBaselineValue ->
          if (curBaselineValue != null) {
            val doubleValue = value.toDouble()
            val pct = (doubleValue - curBaselineValue) / curBaselineValue * 100
            val strPct = String.format(Locale.getDefault(), "%.1f", abs(pct))
            val sign = if (pct >= 0.0) "+" else "-"
            val betterSign = if (lessIsBetter) "-" else "+"
            val color =
              if (sign == betterSign) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.customColors.errorTextColor
              }
            Text("$sign$strPct%", style = MaterialTheme.typography.labelMedium, color = color)
          }
        }
      }
      if (unit.isNotEmpty()) {
        Text(
          unit,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }
}

@Composable
internal fun ValueSeriesRow(
  label: String,
  valueSeries: ValueSeries,
  aggregation: Aggregation,
  modifier: Modifier = Modifier,
  unit: String = "",
  baselineValueSeries: ValueSeries? = null,
  baselineAggregation: Aggregation? = null,
  lessIsBetter: Boolean = false,
) {
  val value = getAggregationValue(valueSeries = valueSeries, aggregation = aggregation)
  var baselineValue: Double? = null
  if (baselineValueSeries != null && baselineAggregation != null) {
    baselineValue =
      getAggregationValue(valueSeries = baselineValueSeries, aggregation = baselineAggregation)
  }
  var showValueSeriesBottomSheet by remember { mutableStateOf(false) }

  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(0.6f),
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
    )
    Column(
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.Start,
      modifier = Modifier.weight(0.4f),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        val linkColor = MaterialTheme.customColors.linkColor
        val isMultipleRuns = valueSeries.valueCount > 1
        val textColor = if (isMultipleRuns) linkColor else MaterialTheme.colorScheme.onSurface
        val textModifier =
          if (isMultipleRuns) {
            Modifier.drawBehind {
                val strokeWidth = 2f
                val y = size.height - strokeWidth
                val dashPath = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                drawLine(
                  color = linkColor,
                  start = Offset(0f, y),
                  end = Offset(size.width, y),
                  strokeWidth = strokeWidth,
                  pathEffect = dashPath,
                )
              }
              .clickable { showValueSeriesBottomSheet = true }
          } else {
            Modifier
          }
        AnimatedContent(value) { curValue ->
          Text(
            String.format(Locale.getDefault(), "%.2f", curValue),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = textModifier,
          )
        }
        AnimatedContent(
          baselineValue,
          contentAlignment = Alignment.CenterStart,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { curBaselineValue ->
          if (curBaselineValue != null && abs(curBaselineValue) > 1e-6) {
            val pct = (value - curBaselineValue) / curBaselineValue * 100
            val strPct = String.format(Locale.getDefault(), "%.1f", abs(pct))
            val sign = if (pct >= 0.0) "+" else "-"
            val betterSign = if (lessIsBetter) "-" else "+"
            val color =
              if (sign == betterSign) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.customColors.errorTextColor
              }
            Text("$sign$strPct%", style = MaterialTheme.typography.labelMedium, color = color)
          }
        }
      }
      if (unit.isNotEmpty()) {
        Text(
          unit,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }

  if (showValueSeriesBottomSheet) {
    BenchmarkValueSeriesViewer(
      title = "$label ($unit)",
      valueSeries = valueSeries,
      onDismiss = { showValueSeriesBottomSheet = false },
    )
  }
}

internal fun getBenchmarkResultCsv(llmResult: LlmBenchmarkResult, aggregation: Aggregation): String {
  val basicInfo = llmResult.baiscInfo
  val stats = llmResult.stats

  val header =
    listOf(
        "start time (ms)",
        "end time (ms)",
        "model name",
        "accelerator",
        "prefill tokens count",
        "decode tokens count",
        "runs count",
        "app version",
        "prefill speed (tokens/sec)",
        "decode speed (tokens/sec)",
        "time to first token (sec)",
        "first init time (ms)",
        "steady init time (ms)",
      )
      .joinToString(",")

  val data =
    listOf(
        basicInfo.startMs,
        basicInfo.endMs,
        basicInfo.modelName,
        basicInfo.accelerator,
        basicInfo.prefillTokens,
        basicInfo.decodeTokens,
        basicInfo.numberOfRuns,
        basicInfo.appVersion,
        getAggregationValue(stats.prefillSpeed, aggregation),
        getAggregationValue(stats.decodeSpeed, aggregation),
        getAggregationValue(stats.timeToFirstToken, aggregation),
        stats.firstInitTimeMs,
        getAggregationValue(stats.nonFirstInitTimeMs, aggregation),
      )
      .joinToString(",")

  return "$header\n$data"
}

internal fun getAggregationValue(valueSeries: ValueSeries, aggregation: Aggregation): Double {
  return when (aggregation) {
    Aggregation.AVG -> valueSeries.avg
    Aggregation.MEDIAN -> valueSeries.medium
    Aggregation.MIN -> valueSeries.min
    Aggregation.MAX -> valueSeries.max
  }
}
