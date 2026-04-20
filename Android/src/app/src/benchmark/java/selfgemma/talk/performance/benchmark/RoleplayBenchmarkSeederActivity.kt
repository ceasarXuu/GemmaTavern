package selfgemma.talk.performance.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "RoleplayBenchmarkSeeder"

@AndroidEntryPoint
class RoleplayBenchmarkSeederActivity : ComponentActivity() {
  @Inject lateinit var fixtureSeeder: RoleplayBenchmarkFixtureSeeder

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val scenario = intent.getStringExtra(ROLEPLAY_BENCHMARK_SCENARIO_EXTRA)
    if (scenario.isNullOrBlank()) {
      setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", "missing scenario extra"))
      finish()
      return
    }

    try {
      val summary = runBlocking(Dispatchers.IO) { fixtureSeeder.seedScenario(scenario) }
      Log.i(TAG, summary)
      setResult(Activity.RESULT_OK, Intent().putExtra("summary", summary))
    } catch (t: Throwable) {
      Log.e(TAG, "seed failed", t)
      setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", t.message ?: t.javaClass.simpleName))
    }

    finishAndRemoveTask()
  }
}