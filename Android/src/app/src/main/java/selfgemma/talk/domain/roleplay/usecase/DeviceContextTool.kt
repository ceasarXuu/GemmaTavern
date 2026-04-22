package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "DeviceContextTool"
private const val TOOL_NAME = "getDeviceContext"
private val DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)

@Singleton
class DeviceContextTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : RoleplayToolProviderFactory {
  override val toolId: String = RoleplayToolIds.DEVICE_CONTEXT
  override val priority: Int = 40

  internal var snapshotProvider: () -> DeviceContextSnapshot = {
    DeviceContextSnapshot.capture(appContext)
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
    return DeviceContextToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface DeviceContextToolSetAccess {
    fun getDeviceContextForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class DeviceContextToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> DeviceContextSnapshot,
  ) : ToolSet, DeviceContextToolSetAccess {
    @Tool(
      description =
        "Get the device's real-world locale and formatting context. Returns locale tag, language, region, localized weekday, 12-hour or 24-hour clock preference, and time zone. Use this when the user asks about the device's regional settings, language, weekday, or local formatting conventions.",
    )
    fun getDeviceContext(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "localeTag" to snapshot.localeTag,
            "languageCode" to snapshot.languageCode,
            "regionCode" to snapshot.regionCode,
            "weekday" to snapshot.weekday,
            "weekdayLocalized" to snapshot.weekdayLocalized,
            "uses24HourClock" to snapshot.uses24HourClock,
            "hourCycle" to snapshot.hourCycle,
            "timeZone" to snapshot.timeZoneId,
            "currentDate" to snapshot.currentDate,
          )
        val resultSummary =
          "Locale ${snapshot.localeTag}, ${snapshot.weekdayLocalized}, ${snapshot.hourCycle}, ${snapshot.timeZoneId}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson =
            """{"localeTag":"${snapshot.localeTag}","languageCode":"${snapshot.languageCode}","regionCode":"${snapshot.regionCode}","weekday":"${snapshot.weekday}","weekdayLocalized":"${snapshot.weekdayLocalized}","uses24HourClock":${snapshot.uses24HourClock},"hourCycle":"${snapshot.hourCycle}","timeZone":"${snapshot.timeZoneId}","currentDate":"${snapshot.currentDate}"}""",
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Device locale context",
                content =
                  "The real-world device locale is ${snapshot.localeTag} (${snapshot.localeDisplayName}). " +
                    "Today is ${snapshot.weekdayLocalized} on ${snapshot.currentDate}, " +
                    "the device uses ${snapshot.hourCycle}, and the system time zone is ${snapshot.timeZoneId}. " +
                    "Use this only for the user's real device context in the current turn.",
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
          errorMessage = error.message ?: "Failed to read device locale context.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf(
          "error" to (error.message ?: "Failed to read device locale context."),
        )
      }
    }

    override fun getDeviceContextForTest(): Map<String, Any> {
      return getDeviceContext()
    }
  }
}

data class DeviceContextSnapshot(
  val localeTag: String,
  val localeDisplayName: String,
  val languageCode: String,
  val regionCode: String,
  val weekday: String,
  val weekdayLocalized: String,
  val uses24HourClock: Boolean,
  val hourCycle: String,
  val timeZoneId: String,
  val currentDate: String,
) {
  companion object {
    fun capture(
      context: Context,
      now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): DeviceContextSnapshot {
      val locale = resolvePrimaryLocale(context)
      val uses24HourClock = DateFormat.is24HourFormat(context)
      return DeviceContextSnapshot(
        localeTag = locale.toLanguageTag(),
        localeDisplayName = locale.getDisplayName(locale).ifBlank { locale.getDisplayName(Locale.ENGLISH) },
        languageCode = locale.language.ifBlank { "und" },
        regionCode = locale.country,
        weekday = now.dayOfWeek.name.lowercase(Locale.ROOT),
        weekdayLocalized = now.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
        uses24HourClock = uses24HourClock,
        hourCycle = if (uses24HourClock) "24h" else "12h",
        timeZoneId = now.zone.id,
        currentDate = now.format(DATE_FORMATTER),
      )
    }

    internal fun resolvePrimaryLocale(context: Context): Locale {
      val locales = context.resources.configuration.locales
      return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }
  }
}
