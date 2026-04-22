package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

private const val TAG = "DeviceNetworkTool"
private const val TOOL_NAME = "getDeviceNetworkStatus"

@Singleton
class DeviceNetworkStatusTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : RoleplayToolProviderFactory {
  override val priority: Int = 30

  internal var snapshotProvider: () -> DeviceNetworkStatusSnapshot = {
    DeviceNetworkStatusSnapshot.capture(appContext)
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
    return DeviceNetworkStatusToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface DeviceNetworkStatusToolSetAccess {
    fun getDeviceNetworkStatusForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class DeviceNetworkStatusToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> DeviceNetworkStatusSnapshot,
  ) : ToolSet, DeviceNetworkStatusToolSetAccess {
    @Tool(
      description =
        "Get the device's real-world network connectivity state. Returns whether the device is connected, the active transport, whether the network is validated, and whether it is metered. Use this when the user asks about internet access, Wi-Fi, mobile data, offline state, or unstable connectivity.",
    )
    fun getDeviceNetworkStatus(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "isConnected" to snapshot.isConnected,
            "transport" to snapshot.transport,
            "isValidated" to snapshot.isValidated,
            "isMetered" to snapshot.isMetered,
          )
        val resultSummary =
          if (snapshot.isConnected) {
            "Network connected over ${snapshot.transport}, validated=${snapshot.isValidated}, metered=${snapshot.isMetered}"
          } else {
            "Network offline"
          }
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson =
            """{"isConnected":${snapshot.isConnected},"transport":"${snapshot.transport}","isValidated":${snapshot.isValidated},"isMetered":${snapshot.isMetered}}""",
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Device network status",
                content =
                  if (snapshot.isConnected) {
                    "The real-world device is currently online over ${snapshot.transport}. " +
                      "Validated internet is ${if (snapshot.isValidated) "available" else "not confirmed"}, " +
                      "and the active network is ${if (snapshot.isMetered) "metered" else "unmetered"}. " +
                      "Use this only for the user's real device connectivity in the current turn."
                  } else {
                    "The real-world device currently appears offline. Use this only for the user's real device connectivity in the current turn."
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
          errorMessage = error.message ?: "Failed to read network state.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf(
          "error" to (error.message ?: "Failed to read network state."),
        )
      }
    }

    override fun getDeviceNetworkStatusForTest(): Map<String, Any> {
      return getDeviceNetworkStatus()
    }
  }
}

data class DeviceNetworkStatusSnapshot(
  val isConnected: Boolean,
  val transport: String,
  val isValidated: Boolean,
  val isMetered: Boolean,
) {
  companion object {
    fun capture(context: Context): DeviceNetworkStatusSnapshot {
      val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
          ?: error("ConnectivityManager unavailable.")
      val activeNetwork = connectivityManager.activeNetwork
      val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
      val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      return DeviceNetworkStatusSnapshot(
        isConnected = hasInternet,
        transport = formatTransport(capabilities),
        isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        isMetered = connectivityManager.isActiveNetworkMeteredSafe(),
      )
    }

    internal fun formatTransport(capabilities: NetworkCapabilities?): String {
      return when {
        capabilities == null -> "none"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
      }
    }

    private fun ConnectivityManager.isActiveNetworkMeteredSafe(): Boolean {
      return runCatching { isActiveNetworkMetered }.getOrDefault(false)
    }
  }
}
