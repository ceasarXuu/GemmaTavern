package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact

internal data class RoleplayExternalFactPolicy(
  val factType: String,
  val freshnessTtlMillis: Long? = null,
)

internal fun applyRoleplayExternalFactPolicy(
  toolName: String,
  fact: RoleplayExternalFact,
  resultJson: String?,
  turnId: String,
  toolInvocationId: String,
  finishedAt: Long,
): RoleplayExternalFact {
  val policy = ROLEPLAY_EXTERNAL_FACT_POLICIES[toolName]
  return fact.copy(
    factType = if (fact.factType == "generic") policy?.factType ?: fact.factType else fact.factType,
    structuredValueJson = fact.structuredValueJson ?: resultJson,
    turnId = turnId,
    toolInvocationId = toolInvocationId,
    capturedAt = if (fact.capturedAt <= 0L) finishedAt else fact.capturedAt,
    freshnessTtlMillis = fact.freshnessTtlMillis ?: policy?.freshnessTtlMillis,
  )
}

private val ROLEPLAY_EXTERNAL_FACT_POLICIES =
  mapOf(
    "getDeviceSystemTime" to RoleplayExternalFactPolicy(factType = "device.system_time", freshnessTtlMillis = 60_000L),
    "getDeviceBatteryStatus" to RoleplayExternalFactPolicy(factType = "device.battery_status", freshnessTtlMillis = 300_000L),
    "getDeviceNetworkStatus" to RoleplayExternalFactPolicy(factType = "device.network_status", freshnessTtlMillis = 120_000L),
    "getDeviceContext" to RoleplayExternalFactPolicy(factType = "device.context", freshnessTtlMillis = 43_200_000L),
    "getNextAlarmHint" to RoleplayExternalFactPolicy(factType = "device.next_alarm", freshnessTtlMillis = 1_800_000L),
    "getApproximateLocation" to RoleplayExternalFactPolicy(factType = "device.approximate_location", freshnessTtlMillis = 1_800_000L),
    "getCalendarSnapshot" to RoleplayExternalFactPolicy(factType = "device.calendar_snapshot", freshnessTtlMillis = 900_000L),
    "getWeather" to RoleplayExternalFactPolicy(factType = "world.weather", freshnessTtlMillis = 1_800_000L),
    "placeLookupOrMapContext" to RoleplayExternalFactPolicy(factType = "world.place_lookup", freshnessTtlMillis = 21_600_000L),
    "queryWikipedia" to RoleplayExternalFactPolicy(factType = "world.reference.wikipedia", freshnessTtlMillis = null),
  )
