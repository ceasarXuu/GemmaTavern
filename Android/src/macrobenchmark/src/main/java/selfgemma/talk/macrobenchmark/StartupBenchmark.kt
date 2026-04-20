package selfgemma.talk.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
@OptIn(ExperimentalMacrobenchmarkApi::class)
class StartupBenchmark {
  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun coldStartup() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(StartupTimingMetric()),
      compilationMode = CompilationMode.Ignore(),
      startupMode = StartupMode.COLD,
      iterations = STARTUP_ITERATIONS,
    ) {
      pressHome()
      startActivityAndWait()
    }

  @Test
  fun warmStartup() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(StartupTimingMetric()),
      compilationMode = CompilationMode.Ignore(),
      startupMode = StartupMode.WARM,
      iterations = STARTUP_ITERATIONS,
    ) {
      pressHome()
      startActivityAndWait()
    }
}