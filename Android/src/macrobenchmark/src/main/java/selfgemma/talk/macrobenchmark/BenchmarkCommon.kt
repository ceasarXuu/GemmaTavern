package selfgemma.talk.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import kotlin.math.roundToInt

internal const val TARGET_PACKAGE = "selfgemma.talk"
internal const val STARTUP_ITERATIONS = 8
internal const val FLOW_ITERATIONS = 6
internal const val STRESS_ITERATIONS = 6
internal const val ROLE_CREATION_ITERATIONS = 4

private const val UI_TIMEOUT_MS = 10_000L
private const val APP_FOREGROUND_TIMEOUT_MS = 5_000L
private const val ROLEPLAY_BENCHMARK_SEEDER_ACTIVITY =
  "selfgemma.talk.performance.benchmark.RoleplayBenchmarkSeederActivity"
private const val ROLEPLAY_BENCHMARK_SURFACE_ACTIVITY =
  "selfgemma.talk.performance.benchmark.RoleplayBenchmarkSurfaceActivity"

internal const val ROLEPLAY_BENCHMARK_STRESS_SCENARIO = "roleplay_stress_v1"
internal const val ROLEPLAY_LONG_CHAT_ROLE_NAME = "Benchmark Long Chat Role"
internal const val ROLEPLAY_LONG_CHAT_SESSION_ID = "benchmark-session-long-chat"
internal const val ROLEPLAY_SURFACE_SESSIONS = "sessions"
internal const val ROLEPLAY_SURFACE_ROLES = "roles"
internal const val ROLEPLAY_SURFACE_ROLE_EDITOR = "role_editor"
internal const val ROLEPLAY_SURFACE_CHAT = "chat"
internal const val ROLEPLAY_SESSIONS_FIRST_ITEM_X = 0.5f
internal const val ROLEPLAY_SESSIONS_FIRST_ITEM_Y = 0.24f

private const val ROLE_EDITOR_NAME_TAG = "role_editor_name"
private const val ROLE_EDITOR_DESCRIPTION_TAG = "role_editor_description"
private const val ROLE_EDITOR_SYSTEM_PROMPT_TAG = "role_editor_system_prompt"
private const val ROLE_EDITOR_SAVE_TAG = "role_editor_save"
private const val ROLE_EDITOR_PROMPT_TAB_TAG = "role_editor_tab_prompt"

internal val macrobenchmarkDevice: UiDevice
  get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

internal fun MacrobenchmarkScope.seedRoleplayScenario(scenario: String) {
  macrobenchmarkDevice.ensureDeviceAwake()

  val output =
    macrobenchmarkDevice.executeShellCommand(
      "am start -W -n $TARGET_PACKAGE/$ROLEPLAY_BENCHMARK_SEEDER_ACTIVITY --es scenario $scenario"
    )

  check(output.contains("Status: ok")) {
    "Failed to seed roleplay benchmark scenario '$scenario': $output"
  }
}

internal fun MacrobenchmarkScope.startAppAndWait() {
  macrobenchmarkDevice.ensureDeviceAwake()
  pressHome()
  startActivityAndWait()
  macrobenchmarkDevice.waitForAppToSettle()
  macrobenchmarkDevice.requireTargetAppForeground("start app")
}

internal fun MacrobenchmarkScope.launchRoleplayBenchmarkSurface(
  surface: String,
  sessionId: String? = null,
) {
  macrobenchmarkDevice.ensureDeviceAwake()

  val sessionArgument = sessionId?.let { " --es sessionId $it" } ?: ""
  val output =
    macrobenchmarkDevice.executeShellCommand(
      "am start -S -W -n $TARGET_PACKAGE/$ROLEPLAY_BENCHMARK_SURFACE_ACTIVITY --es surface $surface$sessionArgument"
    )

  check(output.contains("Status: ok")) {
    "Failed to launch roleplay benchmark surface '$surface': $output"
  }

  macrobenchmarkDevice.waitForAppToSettle()
  macrobenchmarkDevice.requireTargetAppForeground("launch surface $surface")
}

internal fun UiDevice.swipeLeftAcrossContent() {
  requireTargetAppForeground("swipe left across content")
  val y = (displayHeight * 0.48f).toInt()
  swipe((displayWidth * 0.82f).toInt(), y, (displayWidth * 0.18f).toInt(), y, 24)
}

internal fun UiDevice.swipeRightAcrossContent() {
  requireTargetAppForeground("swipe right across content")
  val y = (displayHeight * 0.48f).toInt()
  swipe((displayWidth * 0.18f).toInt(), y, (displayWidth * 0.82f).toInt(), y, 24)
}

internal fun UiDevice.swipeUpThroughList() {
  requireTargetAppForeground("swipe up through list")
  val x = displayWidth / 2
  swipe(x, (displayHeight * 0.82f).toInt(), x, (displayHeight * 0.28f).toInt(), 20)
}

internal fun UiDevice.swipeDownThroughList() {
  requireTargetAppForeground("swipe down through list")
  val x = displayWidth / 2
  swipe(x, (displayHeight * 0.30f).toInt(), x, (displayHeight * 0.78f).toInt(), 20)
}

internal fun UiDevice.tapBottomNavigationItem(index: Int, itemCount: Int = 3) {
  require(index in 0 until itemCount) { "Bottom navigation index out of range: $index" }
  requireTargetAppForeground("tap bottom navigation item $index")

  val x = (displayWidth * ((index * 2f) + 1f) / (itemCount * 2f)).toInt()
  val y = (displayHeight * 0.93f).toInt()
  click(x, y)
}

internal fun UiDevice.tapFirstSessionCard() {
  tapPercent(
    xPercent = ROLEPLAY_SESSIONS_FIRST_ITEM_X,
    yPercent = ROLEPLAY_SESSIONS_FIRST_ITEM_Y,
  )
}

internal fun UiDevice.waitForAppToSettle() {
  waitForIdle()
  waitForIdle(UI_TIMEOUT_MS)
  Thread.sleep(400)
  waitForIdle()
}

internal fun UiDevice.ensureDeviceAwake() {
  if (!isScreenOn) {
    wakeUp()
  }

  executeShellCommand("wm dismiss-keyguard")
  waitForIdle(UI_TIMEOUT_MS)
}

internal fun UiDevice.tapPercent(xPercent: Float, yPercent: Float) {
  requireTargetAppForeground("tap screen percent $xPercent,$yPercent")
  val x = (displayWidth * xPercent).roundToInt()
  val y = (displayHeight * yPercent).roundToInt()
  click(x, y)
  waitForIdle()
}

internal fun UiDevice.tapRoleEditorSave() {
  tapObjectByTag(resourceId = ROLE_EDITOR_SAVE_TAG, description = "role editor save button")
}

internal fun UiDevice.openRoleEditorPromptTab() {
  tapObjectByTag(resourceId = ROLE_EDITOR_PROMPT_TAB_TAG, description = "role editor prompt tab")
}

internal fun UiDevice.replaceRoleEditorName(value: String) {
  replaceRoleEditorText(
    resourceId = ROLE_EDITOR_NAME_TAG,
    value = value,
    description = "role editor name field",
  )
}

internal fun UiDevice.replaceRoleEditorSummary(value: String) {
  replaceRoleEditorText(
    resourceId = ROLE_EDITOR_DESCRIPTION_TAG,
    value = value,
    description = "role editor summary field",
  )
}

internal fun UiDevice.replaceRoleEditorSystemPrompt(value: String) {
  replaceRoleEditorText(
    resourceId = ROLE_EDITOR_SYSTEM_PROMPT_TAG,
    value = value,
    description = "role editor system prompt field",
  )
}

private fun UiDevice.replaceRoleEditorText(
  resourceId: String,
  value: String,
  description: String,
) {
  val target = requireObjectByTag(resourceId = resourceId, description = description)
  requireTargetAppForeground("replace $description")
  target.click()
  waitForIdle()
  target.text = value
  waitForIdle()
}

private fun UiDevice.tapObjectByTag(resourceId: String, description: String) {
  val target = requireObjectByTag(resourceId = resourceId, description = description)
  requireTargetAppForeground("tap $description")
  target.click()
  waitForIdle()
}

private fun UiDevice.requireObjectByTag(resourceId: String, description: String): UiObject2 {
  val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
  var objectRef: UiObject2? = null

  while (objectRef == null && SystemClock.uptimeMillis() < deadline) {
    requireTargetAppForeground("find $description")
    objectRef = findObject(By.res(TARGET_PACKAGE, resourceId)) ?: findObject(By.res(resourceId))
    if (objectRef == null) {
      waitForIdle(250)
    }
  }

  return checkNotNull(objectRef) {
    "Failed to find benchmark object '$description' with resource id '$resourceId'"
  }
}

internal fun UiDevice.requireTargetAppForeground(interactionName: String) {
  val deadline = SystemClock.uptimeMillis() + APP_FOREGROUND_TIMEOUT_MS
  var observedPackageName = currentPackageName

  while (SystemClock.uptimeMillis() < deadline) {
    if (observedPackageName == TARGET_PACKAGE) {
      return
    }

    waitForIdle(250)
    observedPackageName = currentPackageName
  }

  check(observedPackageName == TARGET_PACKAGE) {
    "Refusing to perform benchmark interaction '$interactionName' because $TARGET_PACKAGE is not in the foreground. Current package: ${observedPackageName ?: "unknown"}"
  }
}

