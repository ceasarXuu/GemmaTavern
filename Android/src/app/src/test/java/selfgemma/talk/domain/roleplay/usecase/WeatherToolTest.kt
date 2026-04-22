package selfgemma.talk.domain.roleplay.usecase

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherToolTest {
  @Test
  fun createToolProvider_returnsNullWhenLocationToolsUnavailable() {
    val tool =
      WeatherTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { false }
      }

    val provider =
      tool.createToolProvider(
        pendingMessage = pendingToolMessage("今天天气怎么样"),
        collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2"),
      )

    assertNull(provider)
  }

  @Test
  fun getWeather_recordsInvocationAndExternalFact() {
    val tool =
      WeatherTool(ContextWrapper(null), fakeRoleplayToolAccessPolicy()).apply {
        isAvailableProvider = { true }
        locationSnapshotProvider = {
          ApproximateLocationSnapshot(
            displayName = "Shanghai, China",
            latitude = 31.23,
            longitude = 121.47,
            locality = "Shanghai",
            adminArea = "Shanghai",
            countryName = "China",
            countryCode = "CN",
            accuracyMeters = 1200,
          )
        }
        weatherLookupProvider = { _, _ ->
          CurrentWeatherSnapshot(
            temperatureC = 24.5,
            condition = "clear sky",
            weatherCode = 0,
            windSpeedKph = 11.0,
            isDay = true,
            observedAt = "2026-04-22T15:00",
            timeZone = "Asia/Shanghai",
          )
        }
      }
    val collector = RoleplayToolTraceCollector(sessionId = "session-1", turnId = "assistant-2")
    val toolSet =
      tool.createToolSetForTurn(
        pendingMessage = pendingToolMessage("今天天气呢"),
        collector = collector,
      )

    val result = (toolSet as WeatherTool.WeatherToolSetAccess).getWeatherForTest()
    val invocations = collector.snapshotInvocations()
    val externalFacts = collector.snapshotExternalFacts()

    assertEquals(24.5, result["temperatureC"])
    assertEquals("getWeather", invocations.single().toolName)
    assertTrue(externalFacts.single().content.contains("24.5C"))
  }

  @Test
  fun describeWeatherCode_mapsKnownValue() {
    assertEquals("clear sky", CurrentWeatherSnapshot.describeWeatherCode(0))
  }
}
