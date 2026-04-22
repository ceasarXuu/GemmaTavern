package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "PlaceLookupTool"
private const val TOOL_NAME = "placeLookupOrMapContext"
private val placeLookupGson = Gson()

@Singleton
class PlaceLookupTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
  private val accessPolicy: RoleplayToolAccessPolicy,
) : RoleplayToolProviderFactory {
  override val priority: Int = 100

  internal var locationBiasProvider: () -> ApproximateLocationSnapshot? = {
    if (accessPolicy.canUseLocationTools()) {
      runCatching { ApproximateLocationSnapshot.capture(appContext) }.getOrNull()
    } else {
      null
    }
  }
  internal var lookupProvider: (String, ApproximateLocationSnapshot?) -> PlaceLookupResult = { query, bias ->
    PlaceLookupResult.lookup(query = query, bias = bias)
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
    return PlaceLookupToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      locationBiasProvider = locationBiasProvider,
      lookupProvider = lookupProvider,
    )
  }

  internal interface PlaceLookupToolSetAccess {
    fun placeLookupOrMapContextForTest(query: String): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class PlaceLookupToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val locationBiasProvider: () -> ApproximateLocationSnapshot?,
    private val lookupProvider: (String, ApproximateLocationSnapshot?) -> PlaceLookupResult,
  ) : ToolSet, PlaceLookupToolSetAccess {
    @Tool(
      description =
        "Look up a place, business, landmark, or address and return map-style context such as display name, area, category, coarse coordinates, and an OpenStreetMap link. Use this for place identification or nearby map context, not for general web search.",
    )
    fun placeLookupOrMapContext(
      @ToolParam(description = "The place name, landmark, business, address, or map-related query to search for.")
      query: String,
    ): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      val trimmedQuery = query.trim()
      return runCatching {
        require(trimmedQuery.isNotEmpty()) { "Place lookup query cannot be blank." }
        val bias = locationBiasProvider()
        val lookup = lookupProvider(trimmedQuery, bias)
        val result =
          linkedMapOf<String, Any>(
            "query" to trimmedQuery,
            "displayName" to lookup.displayName,
            "latitude" to lookup.latitude,
            "longitude" to lookup.longitude,
            "addressSummary" to lookup.addressSummary,
            "category" to lookup.category,
            "type" to lookup.type,
            "mapUrl" to lookup.mapUrl,
            "biasUsed" to lookup.biasUsed,
          )
        val resultSummary = "Place lookup ${lookup.displayName}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = placeLookupGson.toJson(mapOf("query" to trimmedQuery)),
          resultJson = placeLookupGson.toJson(result),
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Place lookup",
                content =
                  "${lookup.displayName} is identified as ${lookup.addressSummary}. " +
                    "Category: ${lookup.category}, type: ${lookup.type}. " +
                    "OpenStreetMap link: ${lookup.mapUrl}. Use this only as place and map context for the current turn.",
              ),
            ),
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool called sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} query=$trimmedQuery displayName=${lookup.displayName}",
        )
        result
      }.getOrElse { error ->
        collector.recordFailed(
          toolName = TOOL_NAME,
          argsJson = placeLookupGson.toJson(mapOf("query" to trimmedQuery)),
          errorMessage = error.message ?: "Failed to look up place context.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} query=$trimmedQuery error=${error.message}",
        )
        mapOf("error" to (error.message ?: "Failed to look up place context."))
      }
    }

    override fun placeLookupOrMapContextForTest(query: String): Map<String, Any> {
      return placeLookupOrMapContext(query)
    }
  }
}

data class PlaceLookupResult(
  val displayName: String,
  val latitude: Double,
  val longitude: Double,
  val addressSummary: String,
  val category: String,
  val type: String,
  val mapUrl: String,
  val biasUsed: Boolean,
) {
  companion object {
    fun lookup(query: String, bias: ApproximateLocationSnapshot?): PlaceLookupResult {
      val url = buildSearchUrl(query = query, bias = bias)
      val results = getJsonArrayFromUrl(url)
      if (results.size() == 0) {
        error("No place result found for \"$query\".")
      }
      val first = results[0].asJsonObject
      val latitude = first.get("lat")?.asDouble ?: error("Place latitude missing.")
      val longitude = first.get("lon")?.asDouble ?: error("Place longitude missing.")
      val address = first.getAsJsonObject("address")
      val addressSummary =
        listOf(
          address?.get("road")?.asString.orEmpty(),
          address?.get("suburb")?.asString.orEmpty(),
          address?.get("city")?.asString.orEmpty(),
          address?.get("state")?.asString.orEmpty(),
          address?.get("country")?.asString.orEmpty(),
        ).filter { it.isNotBlank() }.distinct().joinToString(separator = ", ")
      val displayName =
        first.get("display_name")?.asString?.trim().orEmpty().ifBlank { addressSummary }
      val category = first.get("category")?.asString?.trim().orEmpty().ifBlank { "unknown" }
      val type = first.get("type")?.asString?.trim().orEmpty().ifBlank { "unknown" }
      return PlaceLookupResult(
        displayName = displayName,
        latitude = ApproximateLocationSnapshot.roundCoordinate(latitude),
        longitude = ApproximateLocationSnapshot.roundCoordinate(longitude),
        addressSummary = addressSummary.ifBlank { displayName },
        category = category,
        type = type,
        mapUrl = "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=15/$latitude/$longitude",
        biasUsed = bias != null,
      )
    }

    private fun buildSearchUrl(query: String, bias: ApproximateLocationSnapshot?): String {
      val base =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=1&q=${encodeUrlComponent(query)}"
      if (bias == null) {
        return base
      }
      val latDelta = 0.35
      val lonDelta = 0.35
      return "$base&viewbox=${bias.longitude - lonDelta},${bias.latitude + latDelta},${bias.longitude + lonDelta},${bias.latitude - latDelta}"
    }
  }
}
