package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val ROLEPLAY_TOOL_USER_AGENT = "GemmaTavernRoleplayTools/0.1"

internal fun encodeUrlComponent(value: String): String {
  return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

internal fun getJsonObjectFromUrl(url: String): JsonObject {
  return getJsonElementFromUrl(url).asJsonObject
}

internal fun getJsonArrayFromUrl(url: String): JsonArray {
  return getJsonElementFromUrl(url).asJsonArray
}

private fun getJsonElementFromUrl(url: String) = JsonParser.parseString(getJsonTextFromUrl(url))

private fun getJsonTextFromUrl(url: String): String {
  val connection = (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = "GET"
    connectTimeout = 10000
    readTimeout = 10000
    setRequestProperty("Accept", "application/json")
    setRequestProperty("User-Agent", ROLEPLAY_TOOL_USER_AGENT)
  }
  return try {
    val responseCode = connection.responseCode
    val responseText =
      when {
        responseCode in 200..299 ->
          connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        else ->
          connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
            ?: ""
      }
    if (responseCode !in 200..299) {
      error("HTTP $responseCode for $url${if (responseText.isBlank()) "" else ": $responseText"}")
    }
    responseText
  } finally {
    connection.disconnect()
  }
}
