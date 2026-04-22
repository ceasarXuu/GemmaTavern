package selfgemma.talk.domain.roleplay.usecase

import android.app.AlarmManager
import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "DeviceNextAlarmTool"
private const val TOOL_NAME = "getNextAlarmHint"
private val ALARM_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)

@Singleton
class DeviceNextAlarmTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : RoleplayToolProviderFactory {
  override val toolId: String = RoleplayToolIds.NEXT_ALARM_HINT
  override val priority: Int = 70

  internal var snapshotProvider: () -> DeviceNextAlarmSnapshot = {
    DeviceNextAlarmSnapshot.capture(appContext)
  }

  override fun createToolProvider(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolProvider {
    return tool(createToolSetForTurn(pendingMessage = pendingMessage, collector = collector))
  }

  internal fun createToolSetForTurn(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolSet {
    return DeviceNextAlarmToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface DeviceNextAlarmToolSetAccess {
    fun getNextAlarmHintForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class DeviceNextAlarmToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> DeviceNextAlarmSnapshot,
  ) : ToolSet, DeviceNextAlarmToolSetAccess {
    @Tool(
      description =
        "Get the device's next scheduled alarm hint. Returns whether an alarm exists, the next alarm date and time, minutes until it rings, and whether it is within the next 24 hours. Use this when the user asks about wake-up plans, alarms, or near-future schedule pressure tied to the device.",
    )
    fun getNextAlarmHint(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "hasNextAlarm" to snapshot.hasNextAlarm,
            "alarmDate" to snapshot.alarmDate,
            "alarmTime" to snapshot.alarmTime,
            "minutesUntilAlarm" to snapshot.minutesUntilAlarm,
            "isWithin24Hours" to snapshot.isWithin24Hours,
            "timeZone" to snapshot.timeZoneId,
          )
        val resultSummary =
          if (snapshot.hasNextAlarm) {
            "Next alarm ${snapshot.alarmDate} ${snapshot.alarmTime} in ${snapshot.minutesUntilAlarm} minutes"
          } else {
            "No upcoming alarm"
          }
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson =
            """{"hasNextAlarm":${snapshot.hasNextAlarm},"alarmDate":"${snapshot.alarmDate}","alarmTime":"${snapshot.alarmTime}","minutesUntilAlarm":${snapshot.minutesUntilAlarm},"isWithin24Hours":${snapshot.isWithin24Hours},"timeZone":"${snapshot.timeZoneId}"}""",
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Next device alarm",
                content =
                  if (snapshot.hasNextAlarm) {
                    "The real-world device has a scheduled alarm for ${snapshot.alarmDate} ${snapshot.alarmTime} in ${snapshot.timeZoneId}, about ${snapshot.minutesUntilAlarm} minutes from now. " +
                      "It is ${if (snapshot.isWithin24Hours) "within" else "outside"} the next 24 hours. " +
                      "Use this only for the user's real device schedule context in the current turn."
                  } else {
                    "The real-world device currently has no visible upcoming alarm. Use this only for the user's real device schedule context in the current turn."
                  },
              )
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
          errorMessage = error.message ?: "Failed to read next alarm hint.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf(
          "error" to (error.message ?: "Failed to read next alarm hint."),
        )
      }
    }

    override fun getNextAlarmHintForTest(): Map<String, Any> {
      return getNextAlarmHint()
    }
  }
}

data class DeviceNextAlarmSnapshot(
  val hasNextAlarm: Boolean,
  val alarmDate: String,
  val alarmTime: String,
  val minutesUntilAlarm: Long,
  val isWithin24Hours: Boolean,
  val timeZoneId: String,
) {
  companion object {
    fun capture(
      context: Context,
      now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): DeviceNextAlarmSnapshot {
      val alarmManager = context.getSystemService(AlarmManager::class.java) ?: error("AlarmManager unavailable.")
      val nextAlarm = alarmManager.nextAlarmClock ?: return empty(now.zone.id)
      val is24HourClock = DateFormat.is24HourFormat(context)
      return fromTriggerTime(
        triggerAtMillis = nextAlarm.triggerTime,
        zoneId = now.zone.id,
        locale = DeviceContextSnapshot.resolvePrimaryLocale(context),
        is24HourClock = is24HourClock,
        now = now,
      )
    }

    internal fun fromTriggerTime(
      triggerAtMillis: Long,
      zoneId: String,
      locale: Locale,
      is24HourClock: Boolean,
      now: ZonedDateTime,
    ): DeviceNextAlarmSnapshot {
      val zone = ZoneId.of(zoneId)
      val alarmTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMillis), zone)
      val minutesUntilAlarm = Duration.between(now.toInstant(), alarmTime.toInstant()).toMinutes().coerceAtLeast(0)
      val timeFormatter =
        DateTimeFormatter.ofPattern(if (is24HourClock) "HH:mm" else "hh:mm a", locale)
      return DeviceNextAlarmSnapshot(
        hasNextAlarm = true,
        alarmDate = alarmTime.format(ALARM_DATE_FORMATTER),
        alarmTime = alarmTime.format(timeFormatter),
        minutesUntilAlarm = minutesUntilAlarm,
        isWithin24Hours = minutesUntilAlarm <= Duration.ofHours(24).toMinutes(),
        timeZoneId = zone.id,
      )
    }

    internal fun empty(timeZoneId: String): DeviceNextAlarmSnapshot {
      return DeviceNextAlarmSnapshot(
        hasNextAlarm = false,
        alarmDate = "",
        alarmTime = "",
        minutesUntilAlarm = -1,
        isWithin24Hours = false,
        timeZoneId = timeZoneId,
      )
    }
  }
}
