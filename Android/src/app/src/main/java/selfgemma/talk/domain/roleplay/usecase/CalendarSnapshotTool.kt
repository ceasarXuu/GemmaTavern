package selfgemma.talk.domain.roleplay.usecase

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
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

private const val TAG = "CalendarSnapshotTool"
private const val TOOL_NAME = "getCalendarSnapshot"
private const val CALENDAR_WINDOW_HOURS = 72L
private const val CALENDAR_EVENT_LIMIT = 5
private val calendarSnapshotGson = Gson()

@Singleton
class CalendarSnapshotTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
  private val accessPolicy: RoleplayToolAccessPolicy,
) : RoleplayToolProviderFactory {
  override val priority: Int = 60

  internal var isAvailableProvider: () -> Boolean = { accessPolicy.canUseCalendarTools() }
  internal var snapshotProvider: () -> CalendarSnapshot = { CalendarSnapshot.capture(appContext) }

  override fun createToolProvider(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolProvider? {
    if (!isAvailableProvider()) {
      logDebug(
        "calendar tool hidden sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id}",
      )
      return null
    }
    return tool(createToolSetForTurn(pendingMessage = pendingMessage, collector = collector))
  }

  internal fun createToolSetForTurn(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolSet {
    return CalendarSnapshotToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface CalendarSnapshotToolSetAccess {
    fun getCalendarSnapshotForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class CalendarSnapshotToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> CalendarSnapshot,
  ) : ToolSet, CalendarSnapshotToolSetAccess {
    @Tool(
      description =
        "Read a short snapshot of upcoming calendar events from the real device. Returns the next few events within roughly the next three days, including titles, start and end times, all-day flag, and event location when present. Use this only for the user's real schedule context in the current turn.",
    )
    fun getCalendarSnapshot(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "windowStart" to snapshot.windowStart,
            "windowEnd" to snapshot.windowEnd,
            "eventCount" to snapshot.events.size,
            "events" to snapshot.events.map(CalendarEventSnapshot::toMap),
          )
        val resultSummary =
          if (snapshot.events.isEmpty()) {
            "No upcoming calendar events in the next $CALENDAR_WINDOW_HOURS hours"
          } else {
            "Calendar snapshot with ${snapshot.events.size} upcoming events"
          }
        val factContent =
          (
            if (snapshot.events.isEmpty()) {
              "The real-world device calendar shows no upcoming events in the next $CALENDAR_WINDOW_HOURS hours."
            } else {
              "The real-world device calendar shows ${snapshot.events.size} upcoming events. " +
                snapshot.events.joinToString(separator = " ") { event ->
                  "${event.title} starts at ${event.startAt}."
                }
            }
            ) + " Use this only for the user's real device schedule context in the current turn."
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson = calendarSnapshotGson.toJson(result),
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Upcoming calendar snapshot",
                content = factContent,
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
          errorMessage = error.message ?: "Failed to read calendar snapshot.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf("error" to (error.message ?: "Failed to read calendar snapshot."))
      }
    }

    override fun getCalendarSnapshotForTest(): Map<String, Any> {
      return getCalendarSnapshot()
    }
  }
}

data class CalendarSnapshot(
  val windowStart: String,
  val windowEnd: String,
  val events: List<CalendarEventSnapshot>,
) {
  companion object {
    @SuppressLint("MissingPermission")
    fun capture(
      context: Context,
      now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
      horizonHours: Long = CALENDAR_WINDOW_HOURS,
      limit: Int = CALENDAR_EVENT_LIMIT,
    ): CalendarSnapshot {
      val formatter =
        DateTimeFormatter.ofPattern(
          "uuuu-MM-dd HH:mm",
          DeviceContextSnapshot.resolvePrimaryLocale(context),
        )
      val startMillis = now.toInstant().toEpochMilli()
      val endMillis = now.plusHours(horizonHours).toInstant().toEpochMilli()
      val uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
          ContentUris.appendId(builder, startMillis)
          ContentUris.appendId(builder, endMillis)
        }.build()
      val projection =
        arrayOf(
          CalendarContract.Instances.TITLE,
          CalendarContract.Instances.BEGIN,
          CalendarContract.Instances.END,
          CalendarContract.Instances.ALL_DAY,
          CalendarContract.Instances.EVENT_LOCATION,
        )
      val events = mutableListOf<CalendarEventSnapshot>()
      context.contentResolver.query(
        uri,
        projection,
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC",
      )?.use { cursor ->
        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        while (cursor.moveToNext() && events.size < limit) {
          val begin = cursor.getLong(beginIndex)
          val end = cursor.getLong(endIndex)
          val isAllDay = cursor.getInt(allDayIndex) == 1
          val startAt = Instant.ofEpochMilli(begin).atZone(now.zone)
          val endAt = Instant.ofEpochMilli(end).atZone(now.zone)
          events +=
            CalendarEventSnapshot(
              title = cursor.getString(titleIndex).orEmpty().ifBlank { "Untitled event" },
              startAt = if (isAllDay) startAt.toLocalDate().toString() else startAt.format(formatter),
              endAt = if (isAllDay) endAt.toLocalDate().toString() else endAt.format(formatter),
              isAllDay = isAllDay,
              location = cursor.getString(locationIndex).orEmpty(),
            )
        }
      } ?: error("Calendar query unavailable.")
      return CalendarSnapshot(
        windowStart = now.format(formatter),
        windowEnd = now.plusHours(horizonHours).format(formatter),
        events = events,
      )
    }
  }
}

data class CalendarEventSnapshot(
  val title: String,
  val startAt: String,
  val endAt: String,
  val isAllDay: Boolean,
  val location: String,
) {
  fun toMap(): Map<String, Any> {
    return linkedMapOf(
      "title" to title,
      "startAt" to startAt,
      "endAt" to endAt,
      "isAllDay" to isAllDay,
      "location" to location,
    )
  }
}
