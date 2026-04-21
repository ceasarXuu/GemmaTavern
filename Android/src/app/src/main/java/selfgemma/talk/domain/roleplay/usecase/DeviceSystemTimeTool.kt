package selfgemma.talk.domain.roleplay.usecase

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone as IcuTimeZone
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
import selfgemma.talk.domain.roleplay.model.ToolInvocation
import selfgemma.talk.domain.roleplay.model.ToolInvocationStatus

private const val TAG = "DeviceSystemTimeTool"
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
class DeviceSystemTimeTool @Inject constructor() : RoleplayToolHandler {
  override val toolName: String = "get_device_system_time"
  override val priority: Int = 10

  internal var snapshotProvider: () -> DeviceSystemTimeSnapshot = { DeviceSystemTimeSnapshot.capture() }

  override suspend fun maybeExecute(
    request: RoleplayToolExecutionRequest,
    stepIndex: Int,
  ): RoleplayToolHandlerResult? {
    val normalizedInput = request.pendingMessage.combinedUserInput.trim()
    if (!isTimeQuery(normalizedInput) || request.isStopRequested()) {
      return null
    }

    val startedAt = System.currentTimeMillis()
    val snapshot = snapshotProvider()
    val resultSummary = "${snapshot.gregorianDate} ${snapshot.time24h}，农历${snapshot.lunarDate}"
    logDebug(
      "matched time query sessionId=${request.pendingMessage.session.id} turnId=${request.pendingMessage.assistantSeed.id} summary=$resultSummary",
    )
    return RoleplayToolHandlerResult(
      toolInvocation =
        ToolInvocation(
          id = UUID.randomUUID().toString(),
          sessionId = request.pendingMessage.session.id,
          turnId = request.pendingMessage.assistantSeed.id,
          toolName = toolName,
          source = ToolExecutionSource.NATIVE,
          status = ToolInvocationStatus.SUCCEEDED,
          stepIndex = stepIndex,
          argsJson = """{"requestedFields":["gregorian_date","lunar_date","hour","minute"]}""",
          resultJson =
            """{"gregorianDate":"${snapshot.gregorianDate}","lunarDate":"${snapshot.lunarDate}","time24h":"${snapshot.time24h}","hour":${snapshot.hour},"minute":${snapshot.minute},"timeZone":"${snapshot.timeZoneId}"}""",
          resultSummary = resultSummary,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        ),
      externalFacts =
        listOf(
          RoleplayExternalFact(
            id = UUID.randomUUID().toString(),
            sourceToolName = toolName,
            title = "Device system time",
            content =
              "Real-world device system time is ${snapshot.gregorianDate} ${snapshot.time24h} in ${snapshot.timeZoneId}. " +
                "The lunar date is ${snapshot.lunarDate}. Use this only when the user is asking about the real current date or time, not the story world.",
          )
        ),
    )
  }

  internal fun isTimeQuery(input: String): Boolean {
    if (input.isBlank()) {
      return false
    }
    return TIME_QUERY_PATTERNS.any { pattern -> pattern.containsMatchIn(input) }
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  companion object {
    private val TIME_QUERY_PATTERNS =
      listOf(
        Regex("(现在|当前|此刻|现实).{0,8}(时间|日期|几点|几号|几月几日|农历|公历)"),
        Regex("(今天|今日).{0,8}(日期|几号|几月几号|几月几日|农历|公历)"),
        Regex("(几点了|现在几点|当前时间|当前日期|系统时间|设备时间|今天几号|今天农历|农历几月几号)"),
        Regex("(?i)\\b(current|device|system|local)\\s+(time|date)\\b"),
        Regex("(?i)\\bwhat(?:'s| is)?\\s+the\\s+(time|date)\\b"),
        Regex("(?i)\\bwhat\\s+time\\s+is\\s+it\\b"),
        Regex("(?i)\\blunar\\s+date\\b"),
      )
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
