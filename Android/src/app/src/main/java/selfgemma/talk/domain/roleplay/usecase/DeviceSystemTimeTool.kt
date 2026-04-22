package selfgemma.talk.domain.roleplay.usecase

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone as IcuTimeZone
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import android.util.Log
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
private val GREGORIAN_DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val LUNAR_MONTH_NAMES = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
private val LUNAR_DAY_NAMES =
  arrayOf(
    "初一",
    "初二",
    "初三",
    "初四",
    "初五",
    "初六",
    "初七",
    "初八",
    "初九",
    "初十",
    "十一",
    "十二",
    "十三",
    "十四",
    "十五",
    "十六",
    "十七",
    "十八",
    "十九",
    "二十",
    "廿一",
    "廿二",
    "廿三",
    "廿四",
    "廿五",
    "廿六",
    "廿七",
    "廿八",
    "廿九",
    "三十",
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
        "Get the device's real-world current system time. Returns Gregorian date, lunar date in the Chinese calendar, 24-hour time, hour, minute, and time zone. Use this when the user asks about the actual current date or time outside the roleplay fiction.",
    )
    fun getDeviceSystemTime(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "gregorianDate" to snapshot.gregorianDate,
            "lunarDate" to snapshot.lunarDate,
            "time24h" to snapshot.time24h,
            "hour" to snapshot.hour,
            "minute" to snapshot.minute,
            "timeZone" to snapshot.timeZoneId,
          )
        val resultSummary = "${snapshot.gregorianDate} ${snapshot.time24h}，农历${snapshot.lunarDate}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson =
            """{"gregorianDate":"${snapshot.gregorianDate}","lunarDate":"${snapshot.lunarDate}","time24h":"${snapshot.time24h}","hour":${snapshot.hour},"minute":${snapshot.minute},"timeZone":"${snapshot.timeZoneId}"}""",
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Device system time",
                content =
                  "Real-world device system time is ${snapshot.gregorianDate} ${snapshot.time24h} in ${snapshot.timeZoneId}. " +
                    "The lunar date is ${snapshot.lunarDate}. Use this only when the user is asking about the real current date or time, not the story world.",
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
  val lunarDate: String,
  val time24h: String,
  val hour: Int,
  val minute: Int,
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
        lunarDate = formatLunarDate(month = lunarMonth, day = lunarDay, isLeapMonth = isLeapMonth),
        time24h = now.format(TIME_FORMATTER),
        hour = now.hour,
        minute = now.minute,
        timeZoneId = now.zone.id,
      )
    }

    internal fun formatLunarDate(month: Int, day: Int, isLeapMonth: Boolean): String {
      val monthName = LUNAR_MONTH_NAMES.getOrElse(month) { "${month + 1}月" }
      val dayName = LUNAR_DAY_NAMES.getOrElse(day - 1) { day.toString() }
      return buildString {
        if (isLeapMonth) {
          append("闰")
        }
        append(monthName)
        append(dayName)
      }
    }
  }
}
