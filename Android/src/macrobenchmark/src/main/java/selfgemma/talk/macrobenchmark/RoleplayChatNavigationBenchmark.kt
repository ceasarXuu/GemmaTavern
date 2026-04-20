package selfgemma.talk.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingGfxInfoMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
@OptIn(ExperimentalMacrobenchmarkApi::class, ExperimentalMetricApi::class)
class RoleplayChatNavigationBenchmark {
  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun openAndCloseChatFromSessions() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingGfxInfoMetric()),
      compilationMode = CompilationMode.Full(),
      iterations = FLOW_ITERATIONS,
      startupMode = null,
      setupBlock = {
        seedRoleplayScenario(ROLEPLAY_BENCHMARK_STRESS_SCENARIO)
        startAppAndWait()
      },
    ) {
      val device = macrobenchmarkDevice

      device.tapBottomNavigationItem(index = 0)
      device.waitForAppToSettle()

      device.tapFirstSessionCard()
      device.waitForAppToSettle()

      device.pressBack()
      device.waitForAppToSettle()
    }
}
