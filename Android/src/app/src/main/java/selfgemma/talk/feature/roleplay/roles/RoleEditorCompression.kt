package selfgemma.talk.feature.roleplay.roles

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import selfgemma.talk.common.processLlmResponse
import selfgemma.talk.data.Model
import selfgemma.talk.runtime.runtimeHelper

private const val ROLE_EDITOR_COMPRESSION_INIT_TIMEOUT_MS = 60_000L
private const val ROLE_EDITOR_COMPRESSION_MAX_ATTEMPTS = 3

/**
 * Tracks an in-flight AI compression request for a single role-editor text field. Created when the
 * user taps "AI compress", removed once the job completes or is cancelled.
 */
internal class ActiveRoleEditorCompression(
  val fieldKey: String,
  val originalValue: String,
  val restoreValue: (String) -> Unit,
  val job: Job,
) {
  var completed: Boolean = false
}

internal data class RoleEditorCompressionResult(
  val text: String,
  val attempts: Int,
)

private fun buildRoleEditorCompressionPrompt(
  fieldTitle: String,
  maxChars: Int,
  content: String,
  attempt: Int,
  previousLength: Int? = null,
): String {
  val retryInstructions =
    if (attempt <= 1) {
      ""
    } else {
      """
      Previous rewrite was still too long${previousLength?.let { " ($it characters)" } ?: ""}.
      Compress much more aggressively this time.
      It is acceptable to drop secondary details as long as the core roleplay intent remains.
      This retry must be under $maxChars characters.

      """.trimIndent()
    }
  val hardConstraint =
    """
    HARD CONSTRAINT:
    Output length MUST be between 1 and $maxChars characters (inclusive upper bound), count every character including spaces and line breaks.
    Return only rewritten text. No preface, no suffix, no markdown, no quotes.
    """.trimIndent()
  return """
    You are helping edit a role card field.
    Rewrite the field below and compress it as much as needed.
    Preserve the original meaning, tone, and roleplay intent.
    Keep useful line breaks or list structure when they matter.
    Remove redundancy first. If needed, aggressively shorten until the limit is satisfied.
    $hardConstraint
    $retryInstructions
    Do not use any text that is not part of the rewritten field.

    Field: $fieldTitle
    Target max characters: $maxChars

    Original content:
    $content
  """.trimIndent()
}

internal suspend fun ensureRoleEditorCompressionModelReady(
  context: android.content.Context,
  model: Model,
  coroutineScope: CoroutineScope,
) {
  if (model.instance != null) {
    return
  }

  suspendCancellableCoroutine { continuation ->
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = { error ->
        if (!continuation.isActive) {
          return@initialize
        }
        if (model.instance != null) {
          continuation.resume(Unit)
        } else {
          continuation.resumeWithException(
            IllegalStateException(error.ifBlank { "Failed to initialize editor assistant model." }),
          )
        }
      },
      coroutineScope = coroutineScope,
    )
  }

  withTimeout(ROLE_EDITOR_COMPRESSION_INIT_TIMEOUT_MS) {
    while (model.instance == null) {
      delay(100)
    }
  }
}

private suspend fun runRoleEditorCompressionInference(
  model: Model,
  input: String,
  coroutineScope: CoroutineScope,
): String {
  return suspendCancellableCoroutine { continuation ->
    var response = ""
    model.runtimeHelper.runInference(
      model = model,
      input = input,
      resultListener = { partialResult, done, _ ->
        response = processLlmResponse(response = "$response$partialResult")
        if (done && continuation.isActive) {
          continuation.resume(response)
        }
      },
      cleanUpListener = {},
      onError = { message ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(message))
        }
      },
      coroutineScope = coroutineScope,
    )
    continuation.invokeOnCancellation {
      model.runtimeHelper.stopResponse(model)
    }
  }
}

internal suspend fun compressRoleEditorFieldToTarget(
  model: Model,
  fieldTitle: String,
  maxChars: Int,
  originalContent: String,
  coroutineScope: CoroutineScope,
): RoleEditorCompressionResult {
  var currentText = originalContent
  var attempt = 0
  while (attempt < ROLE_EDITOR_COMPRESSION_MAX_ATTEMPTS) {
    attempt += 1
    model.runtimeHelper.resetConversation(model = model)
    val nextText =
      runRoleEditorCompressionInference(
        model = model,
        input =
          buildRoleEditorCompressionPrompt(
            fieldTitle = fieldTitle,
            maxChars = maxChars,
            content = currentText,
            attempt = attempt,
            previousLength = currentText.length.takeIf { attempt > 1 },
          ),
        coroutineScope = coroutineScope,
      ).trim()
    if (nextText.isBlank()) {
      return RoleEditorCompressionResult(text = nextText, attempts = attempt)
    }
    currentText = nextText
    if (currentText.length <= maxChars) {
      return RoleEditorCompressionResult(text = currentText, attempts = attempt)
    }
  }
  return RoleEditorCompressionResult(text = currentText, attempts = attempt)
}
