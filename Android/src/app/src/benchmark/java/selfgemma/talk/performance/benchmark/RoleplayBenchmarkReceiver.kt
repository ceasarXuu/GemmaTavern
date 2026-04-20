package selfgemma.talk.performance.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "RoleplayBenchmarkReceiver"

@AndroidEntryPoint
class RoleplayBenchmarkReceiver : BroadcastReceiver() {
  @Inject lateinit var fixtureSeeder: RoleplayBenchmarkFixtureSeeder

  override fun onReceive(context: Context, intent: Intent) {
    val scenario = intent.getStringExtra(ROLEPLAY_BENCHMARK_SCENARIO_EXTRA)
    if (scenario.isNullOrBlank()) {
      setResultCode(Activity.RESULT_CANCELED)
      setResultData("missing scenario extra")
      return
    }

    try {
      val summary =
        runBlocking(Dispatchers.IO) {
          fixtureSeeder.seedScenario(scenario)
        }
      Log.i(TAG, summary)
      setResultCode(Activity.RESULT_OK)
      setResultData(summary)
    } catch (t: Throwable) {
      val message = "seed failed: ${t.message ?: t.javaClass.simpleName}"
      Log.e(TAG, message, t)
      setResultCode(Activity.RESULT_CANCELED)
      setResultData(message)
    }
  }
}