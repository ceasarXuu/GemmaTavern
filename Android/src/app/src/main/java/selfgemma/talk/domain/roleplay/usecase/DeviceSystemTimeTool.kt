package selfgemma.talk.domain.roleplay.usecase

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone as IcuTimeZone
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "DeviceSystemTimeTool"
private const val TOOL_NAME = "getDeviceSystemTime"
private const val SYSTEM_TIME_FACT_TTL_MS = 60_000L
private val GREGORIAN_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val LUNAR_MONTH_NAMES =
  arrayOf(
    "\u6b63\u6708",
    "\u4e8c\u6708",
    "\u4e09\u6708",
    "\u56db\u6708",
    "\u4e94\u6708",
    "\u516d\u6708",
    "\u4e03\u6708",
    "\u516b\u6708",
    "\u4e5d\u6708",
    "\u5341\u6708",
    "\u51ac\u6708",
    "\u814a\u6708",
  )
private val LUNAR_DAY_NAMES =
  arrayOf(
    "\u521d\u4e00",
    "\u521d\u4e8c",
    "\u521d\u4e09",
    "\u521d\u56db",
    "\u521d\u4e94",
    "\u521d\u516d",
    "\u521d\u4e03",
    "\u521d\u516b",
    "\u521d\u4e5d",
    "\u521d\u5341",
    "\u5341\u4e00",
    "\u5341\u4e8c",
    "\u5341\u4e09",
    "\u5341\u56db",
    "\u5341\u4e94",
    "\u5341\u516d",
    "\u5341\u4e03",
    "\u5341\u516b",
    "\u5341\u4e5d",
    "\u4e8c\u5341",
    "\u5eff\u4e00",
    "\u5eff\u4e8c",
    "\u5eff\u4e09",
    "\u5eff\u56db",
    "\u5eff\u4e94",
    "\u5eff\u516d",
    "\u5eff\u4e03",
    "\u5eff\u516b",
    "\u5eff\u4e5d",
    "\u4e09\u5341",
  )

@Singleton
class DeviceSystemTimeTool @Inject constructor() : RoleplayToolProviderFactory {
  override val toolId: String = RoleplayToolIds.DEVICE_SYSTEM_TIME
  override val priority: Int = 10

  internal var snapshotProvider: () -> DeviceSystemTimeSnapshot = { DeviceSystemTimeSnapshot.capture() }

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
    return DeviceSystemTimeToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface DeviceSystemTimeToolSetAccess {
    fun getDeviceSystemTimeForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class DeviceSystemTimeToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> DeviceSystemTimeSnapshot,
  ) : ToolSet, DeviceSystemTimeToolSetAccess {
    @Tool(
      description =
        "Get the device's real-world current system time. Returns Gregorian date, localized weekday, ISO weekday number, lunar date in the Chinese calendar, 24-hour time, hour, minute, epoch milliseconds, and time zone. Use this when the user asks about the actual current date or time outside the roleplay fiction. Do not infer weekday yourself when this tool can answer it.",
    )
    fun getDeviceSystemTime(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val resultJson =
          """{"gregorianDate":"${snapshot.gregorianDate}","weekdayLocalized":"${snapshot.weekdayLocalized}","weekdayIso":${snapshot.weekdayIso},"lunarDate":"${snapshot.lunarDate}","time24h":"${snapshot.time24h}","hour":${snapshot.hour},"minute":${snapshot.minute},"epochMillis":${snapshot.epochMillis},"timeZone":"${snapshot.timeZoneId}"}"""
        val result =
          linkedMapOf<String, Any>(
            "gregorianDate" to snapshot.gregorianDate,
            "weekdayLocalized" to snapshot.weekdayLocalized,
            "weekdayIso" to snapshot.weekdayIso,
            "lunarDate" to snapshot.lunarDate,
            "time24h" to snapshot.time24h,
            "hour" to snapshot.hour,
            "minute" to snapshot.minute,
            "epochMillis" to snapshot.epochMillis,
            "timeZone" to snapshot.timeZoneId,
          )
        val resultSummary =
          "${snapshot.gregorianDate} ${snapshot.weekdayLocalized} ${snapshot.time24h}\uff0c\u519c\u5386${snapshot.lunarDate}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson = resultJson,
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Device system time",
                factKey = "device.system_time",
                factType = "device.system_time",
                content =
                  "Real-world device system time is ${snapshot.gregorianDate} ${snapshot.weekdayLocalized} ${snapshot.time24h} in ${snapshot.timeZoneId}. " +
                    "The lunar date is ${snapshot.lunarDate}. Use this only when the user is asking about the real current date or time, not the story world.",
                structuredValueJson = resultJson,
                capturedAt = snapshot.epochMillis,
                freshnessTtlMillis = SYSTEM_TIME_FACT_TTL_MS,
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
          errorMessage = error.message ?: "Failed to read device system time.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf(
          "error" to (error.message ?: "Failed to read device system time."),
        )
      }
    }

    override fun getDeviceSystemTimeForTest(): Map<String, Any> {
      return getDeviceSystemTime()
    }
  }
}

data class DeviceSystemTimeSnapshot(
  val gregorianDate: String,
  val weekdayLocalized: String,
  val weekdayIso: Int,
  val lunarDate: String,
  val time24h: String,
  val hour: Int,
  val minute: Int,
  val epochMillis: Long,
  val timeZoneId: String,
) {
  companion object {
    fun capture(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): DeviceSystemTimeSnapshot {
      val chineseCalendar = ChineseCalendar(IcuTimeZone.getTimeZone(now.zone.id), Locale.SIMPLIFIED_CHINESE)
      chineseCalendar.timeInMillis = now.toInstant().toEpochMilli()
      val lunarMonth = chineseCalendar.get(ChineseCalendar.MONTH)
      val lunarDay = chineseCalendar.get(ChineseCalendar.DAY_OF_MONTH)
      val isLeapMonth = chineseCalendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1
      return DeviceSystemTimeSnapshot(
        gregorianDate = now.format(GREGORIAN_DATE_FORMATTER),
        weekdayLocalized = formatWeekday(now.dayOfWeek.value),
        weekdayIso = now.dayOfWeek.value,
        lunarDate = formatLunarDate(month = lunarMonth, day = lunarDay, isLeapMonth = isLeapMonth),
        time24h = now.format(TIME_FORMATTER),
        hour = now.hour,
        minute = now.minute,
        epochMillis = now.toInstant().toEpochMilli(),
        timeZoneId = now.zone.id,
      )
    }

    internal fun formatLunarDate(month: Int, day: Int, isLeapMonth: Boolean): String {
      val monthName = LUNAR_MONTH_NAMES.getOrElse(month) { "${month + 1}\u6708" }
      val dayName = LUNAR_DAY_NAMES.getOrElse(day - 1) { day.toString() }
      return buildString {
        if (isLeapMonth) {
          append("\u95f0")
        }
        append(monthName)
        append(dayName)
      }
    }

    internal fun formatWeekday(dayOfWeekIso: Int): String {
      return when (dayOfWeekIso) {
        1 -> "\u661f\u671f\u4e00"
        2 -> "\u661f\u671f\u4e8c"
        3 -> "\u661f\u671f\u4e09"
        4 -> "\u661f\u671f\u56db"
        5 -> "\u661f\u671f\u4e94"
        6 -> "\u661f\u671f\u516d"
        7 -> "\u661f\u671f\u65e5"
        else -> "\u672a\u77e5\u661f\u671f"
      }
    }
  }
}
