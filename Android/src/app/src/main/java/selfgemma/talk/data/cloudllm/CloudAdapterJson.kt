package selfgemma.talk.data.cloudllm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.cloudllm.CloudContentPart
import selfgemma.talk.domain.cloudllm.CloudMessage
import selfgemma.talk.domain.cloudllm.CloudMessageRole
import selfgemma.talk.domain.cloudllm.CloudProviderError
import selfgemma.talk.domain.cloudllm.CloudProviderErrorType
import selfgemma.talk.domain.cloudllm.CloudProviderId

internal fun String.parseJsonObjectOrNull(): JsonObject? {
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}

internal fun String.parseJsonElementOrNull(): JsonElement? {
  return runCatching { JsonParser.parseString(this) }.getOrNull()
}

internal fun JsonObject.stringOrNull(name: String): String? {
  return get(name)?.takeIf { !it.isJsonNull }?.asString
}

internal fun JsonObject.objOrNull(name: String): JsonObject? {
  return get(name)?.takeIf { it.isJsonObject }?.asJsonObject
}

internal fun JsonObject.arrayOrNull(name: String): JsonArray? {
  return get(name)?.takeIf { it.isJsonArray }?.asJsonArray
}

internal fun extractSseDataLines(body: String): List<String> {
  return body
    .lineSequence()
    .mapNotNull(::extractSseDataLine)
    .toList()
}

internal fun extractSseDataLine(line: String): String? {
  val data = line.trim().takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
  return data?.takeIf { it.isNotBlank() && it != "[DONE]" }
}

internal fun CloudMessageRole.openAiRole(): String {
  return when (this) {
    CloudMessageRole.SYSTEM -> "system"
    CloudMessageRole.USER -> "user"
    CloudMessageRole.ASSISTANT -> "assistant"
    CloudMessageRole.TOOL -> "tool"
  }
}

internal fun CloudMessageRole.claudeRole(): String {
  return when (this) {
    CloudMessageRole.ASSISTANT -> "assistant"
    else -> "user"
  }
}

internal fun CloudMessage.textContent(): String {
  return parts.joinToString(separator = "\n") { part ->
    when (part) {
      is CloudContentPart.Text -> part.text
      is CloudContentPart.AudioTranscript -> part.text
      is CloudContentPart.Image -> part.localContextText.orEmpty()
    }
  }
}

internal fun commonError(
  providerId: CloudProviderId,
  statusCode: Int,
  body: String?,
): CloudProviderError {
  val type =
    when (statusCode) {
      401, 403 -> CloudProviderErrorType.AUTHENTICATION
      408 -> CloudProviderErrorType.TIMEOUT
      429 -> CloudProviderErrorType.RATE_LIMIT
      in 500..599 -> CloudProviderErrorType.SERVER
      400, 404, 422 -> CloudProviderErrorType.INVALID_REQUEST
      else -> CloudProviderErrorType.UNKNOWN
    }
  return CloudProviderError(
    type = type,
    providerId = providerId,
    statusCode = statusCode,
    retryable = type in setOf(CloudProviderErrorType.RATE_LIMIT, CloudProviderErrorType.TIMEOUT, CloudProviderErrorType.SERVER),
    message = extractErrorMessage(body),
  )
}

private fun extractErrorMessage(body: String?): String {
  val json = body?.parseJsonObjectOrNull() ?: return body.orEmpty().take(240)
  return json.objOrNull("error")?.stringOrNull("message")
    ?: json.stringOrNull("message")
    ?: body.orEmpty().take(240)
}
