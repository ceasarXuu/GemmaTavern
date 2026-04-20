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
class RoleplayStressBenchmark {
  @get:Rule val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun manyChatsScroll() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingGfxInfoMetric()),
      compilationMode = CompilationMode.Full(),
      iterations = STRESS_ITERATIONS,
      startupMode = null,
      setupBlock = {
        seedRoleplayScenario(ROLEPLAY_BENCHMARK_STRESS_SCENARIO)
        launchRoleplayBenchmarkSurface(surface = ROLEPLAY_SURFACE_SESSIONS)
      },
    ) {
      val device = macrobenchmarkDevice
      repeat(5) {
        device.swipeUpThroughList()
      }
      device.waitForIdle()
    }

  @Test
  fun longConversationHistoryScroll() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingGfxInfoMetric()),
      compilationMode = CompilationMode.Full(),
      iterations = STRESS_ITERATIONS,
      startupMode = null,
      setupBlock = {
        seedRoleplayScenario(ROLEPLAY_BENCHMARK_STRESS_SCENARIO)
        launchRoleplayBenchmarkSurface(
          surface = ROLEPLAY_SURFACE_CHAT,
          sessionId = ROLEPLAY_LONG_CHAT_SESSION_ID,
        )
      },
    ) {
      val device = macrobenchmarkDevice
      repeat(6) {
        device.swipeDownThroughList()
      }
      device.waitForIdle()

      repeat(3) {
        device.swipeUpThroughList()
      }
      device.waitForIdle()
    }

  @Test
  fun manyRolesScroll() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingGfxInfoMetric()),
      compilationMode = CompilationMode.Full(),
      iterations = STRESS_ITERATIONS,
      startupMode = null,
      setupBlock = {
        seedRoleplayScenario(ROLEPLAY_BENCHMARK_STRESS_SCENARIO)
        launchRoleplayBenchmarkSurface(surface = ROLEPLAY_SURFACE_ROLES)
      },
    ) {
      val device = macrobenchmarkDevice
      repeat(5) {
        device.swipeUpThroughList()
      }
      device.waitForIdle()
    }

  @Test
  fun createRoleFromCatalog() =
    benchmarkRule.measureRepeated(
      packageName = TARGET_PACKAGE,
      metrics = listOf(FrameTimingGfxInfoMetric()),
      compilationMode = CompilationMode.Full(),
      iterations = ROLE_CREATION_ITERATIONS,
      startupMode = null,
      setupBlock = {
        seedRoleplayScenario(ROLEPLAY_BENCHMARK_STRESS_SCENARIO)
        launchRoleplayBenchmarkSurface(surface = ROLEPLAY_SURFACE_ROLE_EDITOR)
      },
    ) {
      val device = macrobenchmarkDevice

      device.replaceRoleEditorName("Macrobenchmark Role")
      device.replaceRoleEditorSummary("Role editor performance coverage for dense form layout and save path.")
      device.openRoleEditorPromptTab()
      device.replaceRoleEditorSystemPrompt("Stay in character. Keep continuity. Respond in concise structured paragraphs.")
      device.pressBack()
      device.tapRoleEditorSave()
      device.waitForAppToSettle()
    }
}
