package selfgemma.talk.domain.roleplay.usecase

object RoleplayToolIds {
  const val DEVICE_SYSTEM_TIME = "getDeviceSystemTime"
  const val DEVICE_BATTERY_STATUS = "getDeviceBatteryStatus"
  const val DEVICE_NETWORK_STATUS = "getDeviceNetworkStatus"
  const val DEVICE_CONTEXT = "getDeviceContext"
  const val APPROXIMATE_LOCATION = "getApproximateLocation"
  const val CALENDAR_SNAPSHOT = "getCalendarSnapshot"
  const val NEXT_ALARM_HINT = "getNextAlarmHint"
  const val QUERY_WIKIPEDIA = "queryWikipedia"
  const val WEATHER = "getWeather"
  const val PLACE_LOOKUP = "placeLookupOrMapContext"

  val all: List<String> =
    listOf(
      DEVICE_SYSTEM_TIME,
      DEVICE_BATTERY_STATUS,
      DEVICE_NETWORK_STATUS,
      DEVICE_CONTEXT,
      APPROXIMATE_LOCATION,
      CALENDAR_SNAPSHOT,
      NEXT_ALARM_HINT,
      QUERY_WIKIPEDIA,
      WEATHER,
      PLACE_LOOKUP,
    )
}
