package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "DeviceBatteryTool"
private const val TOOL_NAME = "getDeviceBatteryStatus"

@Singleton
class DeviceBatteryStatusTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : RoleplayToolProviderFactory {
  override val priority: Int = 20

  internal var snapshotProvider: () -> DeviceBatteryStatusSnapshot = {
    DeviceBatteryStatusSnapshot.capture(appContext)
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
    return DeviceBatteryStatusToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface DeviceBatteryStatusToolSetAccess {
    fun getDeviceBatteryStatusForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class DeviceBatteryStatusToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> DeviceBatteryStatusSnapshot,
  ) : ToolSet, DeviceBatteryStatusToolSetAccess {
    @Tool(
      description =
        "Get the device's real-world battery and charging state. Returns battery percent, charging state, charge source, and whether battery saver is enabled. Use this when the user asks about battery life, charging, low power, or whether the device may run out of power soon.",
    )
    fun getDeviceBatteryStatus(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "batteryPercent" to snapshot.batteryPercent,
            "isCharging" to snapshot.isCharging,
            "chargeStatus" to snapshot.chargeStatus,
            "chargeSource" to snapshot.chargeSource,
            "isBatterySaverEnabled" to snapshot.isBatterySaverEnabled,
          )
        val resultSummary =
          buildString {
            append("Battery ")
            append(snapshot.batteryPercent)
            append("%, ")
            append(snapshot.chargeStatus)
            if (snapshot.isCharging && snapshot.chargeSource != "none") {
              append(" via ")
              append(snapshot.chargeSource)
            }
            append(", battery saver ")
            append(if (snapshot.isBatterySaverEnabled) "on" else "off")
          }
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson =
            """{"batteryPercent":${snapshot.batteryPercent},"isCharging":${snapshot.isCharging},"chargeStatus":"${snapshot.chargeStatus}","chargeSource":"${snapshot.chargeSource}","isBatterySaverEnabled":${snapshot.isBatterySaverEnabled}}""",
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Device battery status",
                content =
                  buildString {
                    append("The real-world device battery is at ${snapshot.batteryPercent}%. ")
                    append("Charging state is ${snapshot.chargeStatus}")
                    if (snapshot.isCharging && snapshot.chargeSource != "none") {
                      append(" via ${snapshot.chargeSource}")
                    }
                    append(". Battery saver is ${if (snapshot.isBatterySaverEnabled) "enabled" else "disabled"}. ")
                    append("Use this only for the user's real device state in the current turn.")
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
          errorMessage = error.message ?: "Failed to read battery state.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf(
          "error" to (error.message ?: "Failed to read battery state."),
        )
      }
    }

    override fun getDeviceBatteryStatusForTest(): Map<String, Any> {
      return getDeviceBatteryStatus()
    }
  }
}

data class DeviceBatteryStatusSnapshot(
  val batteryPercent: Int,
  val isCharging: Boolean,
  val chargeStatus: String,
  val chargeSource: String,
  val isBatterySaverEnabled: Boolean,
) {
  companion object {
    fun capture(context: Context): DeviceBatteryStatusSnapshot {
      val batteryIntent =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
          ?: error("Battery status broadcast unavailable.")
      val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      if (level < 0 || scale <= 0) {
        error("Battery level unavailable.")
      }
      val batteryPercent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
      val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
      val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
      val powerManager = context.getSystemService(PowerManager::class.java) ?: error("PowerManager unavailable.")
      return DeviceBatteryStatusSnapshot(
        batteryPercent = batteryPercent,
        isCharging = isChargingStatus(status),
        chargeStatus = formatChargeStatus(status),
        chargeSource = formatChargeSource(plugged),
        isBatterySaverEnabled = powerManager.isPowerSaveMode,
      )
    }

    internal fun formatChargeStatus(status: Int): String {
      return when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
      }
    }

    internal fun formatChargeSource(plugged: Int): String {
      return when {
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
        plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "dock"
        else -> "none"
      }
    }

    private fun isChargingStatus(status: Int): Boolean {
      return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }
  }
}
