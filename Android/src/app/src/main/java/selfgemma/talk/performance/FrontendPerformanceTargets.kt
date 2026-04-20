package selfgemma.talk.performance

object FrontendPerformanceTargets {
  const val slowFrameBudgetMs = 16
  const val frozenFrameBudgetMs = 700

  const val maxForegroundJankRate = 0.05
  const val maxForegroundSlowFrameRate = 0.10
  const val maxForegroundP95FrameMs = 16
  const val maxForegroundP99FrameMs = 32
  const val maxForegroundFrozenFrames = 0L

  const val maxScrollableJankRate = 0.03

  const val maxInteractionP95Ms = 150
  const val maxInteractionP99Ms = 220

  const val coldStartupP50Ms = 900
  const val coldStartupP95Ms = 1200
  const val warmStartupP50Ms = 450
  const val warmStartupP95Ms = 650
}