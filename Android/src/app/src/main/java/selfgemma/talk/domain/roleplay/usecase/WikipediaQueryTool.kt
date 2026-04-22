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
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.roleplay.model.RoleplayExternalFact
import selfgemma.talk.domain.roleplay.model.ToolExecutionSource

private const val TAG = "WikipediaQueryTool"
private const val TOOL_NAME = "queryWikipedia"
private val wikipediaQueryGson = Gson()

@Singleton
class WikipediaQueryTool @Inject constructor(
  @ApplicationContext private val appContext: Context,
) : RoleplayToolProviderFactory {
  override val toolId: String = RoleplayToolIds.QUERY_WIKIPEDIA
  override val priority: Int = 80

  internal var localeProvider: () -> Locale = { DeviceContextSnapshot.resolvePrimaryLocale(appContext) }
  internal var lookupProvider: (String, String) -> WikipediaLookupResult = { query, languageCode ->
    WikipediaLookupResult.lookup(query = query, preferredLanguageCode = languageCode)
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
    return WikipediaQueryToolSet(
      pendingMessage = pendingMessage,
      collector = collector,
      localeProvider = localeProvider,
      lookupProvider = lookupProvider,
    )
  }

  internal interface WikipediaQueryToolSetAccess {
    fun queryWikipediaForTest(query: String): Map<String, Any>
  }

  private fun logDebug(message: String) {
    runCatching { Log.d(TAG, message) }
  }

  private inner class WikipediaQueryToolSet(
    private val pendingMessage: PendingRoleplayMessage,
    private val collector: RoleplayToolTraceCollector,
    private val localeProvider: () -> Locale,
    private val lookupProvider: (String, String) -> WikipediaLookupResult,
  ) : ToolSet, WikipediaQueryToolSetAccess {
    @Tool(
      description =
        "Look up a topic on Wikipedia and return a short encyclopedic summary with a source URL. Use this for factual background on people, places, organizations, history, science, and similar stable topics. Do not use it for live news or changing real-time events.",
    )
    fun queryWikipedia(
      @ToolParam(description = "The topic, page title, or factual query to look up on Wikipedia.")
      query: String,
    ): Map<String, Any> {
      val startedAt = System.currentTimeMillis()
      val trimmedQuery = query.trim()
      return runCatching {
        require(trimmedQuery.isNotEmpty()) { "Wikipedia query cannot be blank." }
        val preferredLanguageCode = localeProvider().language.ifBlank { "en" }
        val lookup = lookupProvider(trimmedQuery, preferredLanguageCode)
        val result =
          linkedMapOf<String, Any>(
            "query" to trimmedQuery,
            "title" to lookup.title,
            "summary" to lookup.summary,
            "pageUrl" to lookup.pageUrl,
            "languageCode" to lookup.languageCode,
          )
        val resultSummary = "Wikipedia ${lookup.title}"
        collector.recordSucceeded(
          toolName = TOOL_NAME,
          argsJson = wikipediaQueryGson.toJson(mapOf("query" to trimmedQuery)),
          resultJson = wikipediaQueryGson.toJson(result),
          resultSummary = resultSummary,
          source = ToolExecutionSource.NATIVE,
          externalFacts =
            listOf(
              RoleplayExternalFact(
                id = UUID.randomUUID().toString(),
                sourceToolName = TOOL_NAME,
                title = "Wikipedia lookup",
                content =
                  "Wikipedia says ${lookup.title}: ${lookup.summary} Source: ${lookup.pageUrl}. " +
                    "Use this only as encyclopedic background for the current turn.",
              ),
            ),
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool called sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} query=$trimmedQuery title=${lookup.title}",
        )
        result
      }.getOrElse { error ->
        collector.recordFailed(
          toolName = TOOL_NAME,
          argsJson = wikipediaQueryGson.toJson(mapOf("query" to trimmedQuery)),
          errorMessage = error.message ?: "Failed to query Wikipedia.",
          source = ToolExecutionSource.NATIVE,
          startedAt = startedAt,
          finishedAt = System.currentTimeMillis(),
        )
        logDebug(
          "runtime tool failed sessionId=${pendingMessage.session.id} turnId=${pendingMessage.assistantSeed.id} query=$trimmedQuery error=${error.message}",
        )
        mapOf("error" to (error.message ?: "Failed to query Wikipedia."))
      }
    }

    override fun queryWikipediaForTest(query: String): Map<String, Any> {
      return queryWikipedia(query)
    }
  }
}

data class WikipediaLookupResult(
  val title: String,
  val summary: String,
  val pageUrl: String,
  val languageCode: String,
) {
  companion object {
    fun lookup(query: String, preferredLanguageCode: String): WikipediaLookupResult {
      val languageCodes =
        listOf(preferredLanguageCode.lowercase(Locale.ROOT), "en")
          .filter { it.isNotBlank() }
          .distinct()
      for (languageCode in languageCodes) {
        val lookup = runCatching { lookupInLanguage(query = query, languageCode = languageCode) }
        if (lookup.isSuccess) {
          return lookup.getOrThrow()
        }
      }
      error("No Wikipedia result found for \"$query\".")
    }

    private fun lookupInLanguage(query: String, languageCode: String): WikipediaLookupResult {
      val searchUrl =
        "https://$languageCode.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeUrlComponent(query)}&utf8=1&format=json&srlimit=1"
      val searchObject = getJsonObjectFromUrl(searchUrl)
      val searchResults =
        searchObject.getAsJsonObject("query")?.getAsJsonArray("search")
          ?: error("Wikipedia search response missing search results.")
      if (searchResults.size() == 0) {
        error("No Wikipedia result found for \"$query\" in $languageCode.")
      }
      val firstResult = searchResults[0].asJsonObject
      val title = firstResult.get("title")?.asString?.trim().orEmpty()
      if (title.isBlank()) {
        error("Wikipedia returned an empty title for \"$query\".")
      }
      val summaryUrl = "https://$languageCode.wikipedia.org/api/rest_v1/page/summary/${encodeUrlComponent(title)}"
      val summaryObject = getJsonObjectFromUrl(summaryUrl)
      val summary =
        summaryObject.get("extract")?.asString?.trim().orEmpty().ifBlank {
          sanitizeWikipediaSnippet(firstResult.get("snippet")?.asString.orEmpty())
        }
      if (summary.isBlank()) {
        error("Wikipedia summary is empty for \"$title\".")
      }
      val pageUrl =
        summaryObject
          .getAsJsonObject("content_urls")
          ?.getAsJsonObject("desktop")
          ?.get("page")
          ?.asString
          ?.trim()
          .orEmpty()
          .ifBlank { "https://$languageCode.wikipedia.org/wiki/${encodeUrlComponent(title)}" }
      return WikipediaLookupResult(
        title = title,
        summary = summary,
        pageUrl = pageUrl,
        languageCode = languageCode,
      )
    }

    internal fun sanitizeWikipediaSnippet(value: String): String {
      return value.replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").trim()
    }
  }
}
