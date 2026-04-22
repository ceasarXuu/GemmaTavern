package selfgemma.talk.domain.roleplay.usecase

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "ApproxLocationTool"
private const val TOOL_NAME = "getApproximateLocation"
private val approximateLocationGson = Gson()

@Singleton
class ApproximateLocationTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
  private val accessPolicy: RoleplayToolAccessPolicy,
) : RoleplayToolProviderFactory {
  override val priority: Int = 50

  internal var isAvailableProvider: () -> Boolean = { accessPolicy.canUseLocationTools() }
  internal var snapshotProvider: () -> ApproximateLocationSnapshot = {
    ApproximateLocationSnapshot.capture(appContext)
  }

  override fun createToolProvider(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolProvider? {
    if (!isAvailableProvider()) {
      logDebug(
        "location tool hidden sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id}",
      )
      return null
    }
    return tool(createToolSetForTurn(pendingMessage = pendingMessage, collector = collector))
  }

  internal fun createToolSetForTurn(
    pendingMessage: PendingRoleplayMessage,
    collector: RoleplayToolTraceCollector,
  ): ToolSet {
    return ApproximateLocationToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      snapshotProvider = snapshotProvider,
    )
  }

  internal interface ApproximateLocationToolSetAccess {
    fun getApproximateLocationForTest(): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class ApproximateLocationToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val snapshotProvider: () -> ApproximateLocationSnapshot,
  ) : ToolSet, ApproximateLocationToolSetAccess {
    @Tool(
      description =
        "Get the device's approximate real-world location. Returns coarse latitude and longitude, city or district, region, country, and an approximate accuracy estimate. Use this only for the user's real device context, such as local surroundings, nearby area grounding, or weather follow-up.",
    )
    fun getApproximateLocation(): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      return runCatching {
        val snapshot = snapshotProvider()
        val result =
          linkedMapOf<String, Any>(
            "displayName" to snapshot.displayName,
            "latitude" to snapshot.latitude,
            "longitude" to snapshot.longitude,
            "locality" to snapshot.locality,
            "adminArea" to snapshot.adminArea,
            "countryName" to snapshot.countryName,
            "countryCode" to snapshot.countryCode,
            "accuracyMeters" to snapshot.accuracyMeters,
          )
        val resultSummary = "Approximate location ${snapshot.displayName}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = "{}",
          resultJson = approximateLocationGson.toJson(result),
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Approximate device location",
                content =
                  "The real-world device appears to be around ${snapshot.displayName} " +
                    "at coarse coordinates ${snapshot.latitude}, ${snapshot.longitude}. " +
                    "Approximate accuracy is ${snapshot.accuracyMeters} meters. " +
                    "Use this only for the user's real device context in the current turn.",
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
          errorMessage = error.message ?: "Failed to read approximate location.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} error=${error.message}",
        )
        mapOf("error" to (error.message ?: "Failed to read approximate location."))
      }
    }

    override fun getApproximateLocationForTest(): Map<String, Any> {
      return getApproximateLocation()
    }
  }
}

data class ApproximateLocationSnapshot(
  val displayName: String,
  val latitude: Double,
  val longitude: Double,
  val locality: String,
  val adminArea: String,
  val countryName: String,
  val countryCode: String,
  val accuracyMeters: Int,
) {
  companion object {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun capture(context: Context): ApproximateLocationSnapshot {
      val locationManager = context.getSystemService(LocationManager::class.java)
        ?: error("LocationManager unavailable.")
      val bestLocation =
        locationManager
          .getProviders(true)
          .asSequence()
          .mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
          }
          .maxByOrNull(Location::getTime)
          ?: error("Approximate location unavailable.")
      val locale = DeviceContextSnapshot.resolvePrimaryLocale(context)
      val address =
        if (Geocoder.isPresent()) {
          runCatching {
              Geocoder(context, locale).getFromLocation(
                bestLocation.latitude,
                bestLocation.longitude,
                1,
              ) ?: emptyList()
            }
            .getOrDefault(emptyList())
            .firstOrNull()
        } else {
          null
        }
      val locality = address?.locality ?: address?.subAdminArea.orEmpty()
      val adminArea = address?.adminArea ?: address?.subAdminArea.orEmpty()
      val countryName = address?.countryName.orEmpty()
      val countryCode = address?.countryCode.orEmpty()
      val latitude = roundCoordinate(bestLocation.latitude)
      val longitude = roundCoordinate(bestLocation.longitude)
      val displayName =
        listOf(locality, adminArea, countryName)
          .filter { it.isNotBlank() }
          .distinct()
          .joinToString(separator = ", ")
          .ifBlank { String.format(Locale.ROOT, "%.2f, %.2f", latitude, longitude) }
      return ApproximateLocationSnapshot(
        displayName = displayName,
        latitude = latitude,
        longitude = longitude,
        locality = locality,
        adminArea = adminArea,
        countryName = countryName,
        countryCode = countryCode,
        accuracyMeters = bestLocation.accuracy.roundToInt().coerceAtLeast(0),
      )
    }

    internal fun roundCoordinate(value: Double): Double {
      return (value * 100.0).roundToInt() / 100.0
    }
  }
}
