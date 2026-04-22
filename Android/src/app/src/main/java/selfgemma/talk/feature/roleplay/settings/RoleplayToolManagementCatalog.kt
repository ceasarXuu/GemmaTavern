package selfgemma.talk.feature.roleplay.settings

import androidx.annotation.StringRes
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolIds
import selfgemma.talk.domain.roleplay.usecase.RoleplayToolPermissionRequirements

data class RoleplayToolManagementEntry(
  val toolId: String,
  @StringRes val titleResId: Int,
  @StringRes val descriptionResId: Int,
) {
  val requiredPermissions: Set<String>
    get() = RoleplayToolPermissionRequirements.permissionsForTool(toolId)
}

val roleplayToolManagementEntries: List<RoleplayToolManagementEntry> =
  listOf(
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.DEVICE_SYSTEM_TIME,
      titleResId = R.string.roleplay_tool_device_system_time_title,
      descriptionResId = R.string.roleplay_tool_device_system_time_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.DEVICE_BATTERY_STATUS,
      titleResId = R.string.roleplay_tool_device_battery_status_title,
      descriptionResId = R.string.roleplay_tool_device_battery_status_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.DEVICE_NETWORK_STATUS,
      titleResId = R.string.roleplay_tool_device_network_status_title,
      descriptionResId = R.string.roleplay_tool_device_network_status_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.DEVICE_CONTEXT,
      titleResId = R.string.roleplay_tool_device_context_title,
      descriptionResId = R.string.roleplay_tool_device_context_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.APPROXIMATE_LOCATION,
      titleResId = R.string.roleplay_tool_approximate_location_title,
      descriptionResId = R.string.roleplay_tool_approximate_location_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.CALENDAR_SNAPSHOT,
      titleResId = R.string.roleplay_tool_calendar_snapshot_title,
      descriptionResId = R.string.roleplay_tool_calendar_snapshot_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.NEXT_ALARM_HINT,
      titleResId = R.string.roleplay_tool_next_alarm_hint_title,
      descriptionResId = R.string.roleplay_tool_next_alarm_hint_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.QUERY_WIKIPEDIA,
      titleResId = R.string.roleplay_tool_query_wikipedia_title,
      descriptionResId = R.string.roleplay_tool_query_wikipedia_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.WEATHER,
      titleResId = R.string.roleplay_tool_weather_title,
      descriptionResId = R.string.roleplay_tool_weather_description,
    ),
    RoleplayToolManagementEntry(
      toolId = RoleplayToolIds.PLACE_LOOKUP,
      titleResId = R.string.roleplay_tool_place_lookup_title,
      descriptionResId = R.string.roleplay_tool_place_lookup_description,
    ),
  )
