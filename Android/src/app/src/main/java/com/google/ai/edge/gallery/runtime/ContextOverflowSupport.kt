package selfgemma.talk.runtime

private const val ERROR_CODE_3 = "error code 3"
private const val DEFAULT_CONTEXT_OVERFLOW_MESSAGE =
  "Input exceeds the model context window. Shorten the message, reset the session, or use a shorter prompt."

fun isContextOverflowError(message: String?): Boolean {
  val normalized = message.orEmpty().lowercase()
  if (normalized.isBlank()) {
    return false
  }
  return normalized.contains(ERROR_CODE_3) ||
    normalized.contains("input token") ||
    normalized.contains("tokens exceed") ||
    normalized.contains("token exceeds") ||
    normalized.contains("context window") ||
    (normalized.contains("token") && normalized.contains("limit")) ||
    (normalized.contains("input") && normalized.contains("exceed"))
}

fun toUserFacingContextOverflowMessage(message: String?): String {
  return if (isContextOverflowError(message)) {
    DEFAULT_CONTEXT_OVERFLOW_MESSAGE
  } else {
    message.orEmpty()
  }
}
