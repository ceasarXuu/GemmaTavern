package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "WeatherTool"
private const val TOOL_NAME = "getWeather"
private val weatherToolGson = Gson()

@Singleton
class WeatherTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
  private val accessPolicy: RoleplayToolAccessPolicy,
) : RoleplayToolProviderFactory {
  override val toolId: String = RoleplayToolIds.WEATHER
  override val priority: Int = 90

  internal var isAvailableProvider: () -> Boolean = { accessPolicy.canRegisterTool(toolId) }
  internal var locationSnapshotProvider: () -> ApproximateLocationSnapshot = {
    ApproximateLocationSnapshot.capture(appContext)
  }
  internal var weatherLookupProvider: (Double, Double) -> CurrentWeatherSnapshot = { latitude, longitude ->
    CurrentWeatherSnapshot.lookup(latitude = latitude, longitude = longitude)
  }

  override fun createToolProvider(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolProvider? {
    if (!isAvailableProvider()) {
      logDebug(
        "weather tool hidden sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id}",
      )
      return null
    }
    return tool(createToolSetForTurn(pendingMessage = pendingMessage, collector = collector))
  }

  internal fun createToolSetForTurn(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolSet {
    return WeatherToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      locationSnapshotProvider = locationSnapshotProvider,
      weatherLookupProvider = weatherLookupProvider,
    )
  }

  internal interface WeatherToolSetAccess {
    fun getWeatherForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class WeatherToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val locationSnapshotProvider: () -> ApproximateLocationSnapshot,
    private val weatherLookupProvider: (Double, Double) -> CurrentWeatherSnapshot,
  ) : ToolSet, WeatherToolSetAccess {
    @Tool(
      description =
        "Get the current real-world weather near the device's approximate location. Returns condition, temperature in Celsius, wind speed, day or night, and observation time. Use this only for the user's real local weather in the current turn.",
    )
    fun getWeather(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val location = locationSnapshotProvider()
        val weather = weatherLookupProvider(location.latitude, location.longitude)
        val result =
          linkedMapOf<String, Any>(
            "displayName" to location.displayName,
            "temperatureC" to weather.temperatureC,
            "condition" to weather.condition,
            "weatherCode" to weather.weatherCode,
            "windSpeedKph" to weather.windSpeedKph,
            "isDay" to weather.isDay,
            "observedAt" to weather.observedAt,
            "timeZone" to weather.timeZone,
            "source" to weather.source,
          )
        val resultSummary = "Weather ${location.displayName} ${weather.temperatureC}C ${weather.condition}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson = weatherToolGson.toJson(result),
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Local weather",
                content =
                  "Current weather near ${location.displayName} is ${weather.temperatureC}C with ${weather.condition}. " +
                    "Wind speed is ${weather.windSpeedKph} km/h and it is currently ${if (weather.isDay) "daytime" else "nighttime"}. " +
                    "Use this only for the user's real local weather in the current turn.",
              ),
            ),
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool called sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} summary=$resultSummary",
        )
        result
      }.getOrElse { error ->
        collector.recordFailed(
          toolName = TOOL_NAME,
          argsJson = "{}",
          errorMessage = error.message ?: "Failed to read local weather.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf("error" to (error.message ?: "Failed to read local weather."))
      }
    }

    override fun getWeatherForTest(): Map<String, Any> {
      return getWeather()
    }
  }
}

data class CurrentWeatherSnapshot(
  val temperatureC: Double,
  val condition: String,
  val weatherCode: Int,
  val windSpeedKph: Double,
  val isDay: Boolean,
  val observedAt: String,
  val timeZone: String,
  val source: String = "open-meteo",
) {
  companion object {
    fun lookup(latitude: Double, longitude: Double): CurrentWeatherSnapshot {
      val url =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current_weather=true&timezone=auto"
      val response = getJsonObjectFromUrl(url)
      val current = response.getAsJsonObject("current_weather")
        ?: error("Weather response missing current_weather.")
      return CurrentWeatherSnapshot(
        temperatureC = current.get("temperature")?.asDouble ?: error("Weather temperature missing."),
        condition = describeWeatherCode(current.get("weathercode")?.asInt ?: -1),
        weatherCode = current.get("weathercode")?.asInt ?: -1,
        windSpeedKph = current.get("windspeed")?.asDouble ?: 0.0,
        isDay = (current.get("is_day")?.asInt ?: 1) == 1,
        observedAt = current.get("time")?.asString.orEmpty(),
        timeZone = response.get("timezone")?.asString.orEmpty().ifBlank { "auto" },
      )
    }

    internal fun describeWeatherCode(code: Int): String {
      return when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unknown"
      }
    }
  }
}
