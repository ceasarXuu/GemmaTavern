package selfgemma.talk.domain.roleplay.usecase

import android.graphics.Bitmap
import selfgemma.talk.domain.roleplay.model.Message

/**
 * Internal helper types and tuning constants used by [SendRoleplayMessageUseCase] and its
 * collaborators. Kept here so the use case file focuses on orchestration logic.
 */

internal data class ModelReadinessResult(
  val ready: Boolean,
  val interrupted: Boolean = false,
  val errorMessage: String? = null,
)

internal data class InferenceAttemptResult(
  val message: Message,
  val overflowDetected: Boolean,
)

internal data class ConversationPreparationResult(
  val failureMessage: Message? = null,
  val overflowDetected: Boolean = false,
)

internal data class DriftAnalysis(
  val signals: List<String>,
  val tabooMatches: List<String>,
  val currentAverageSentenceLength: Int,
  val recentAverageSentenceLength: Int,
)

internal data class StyleRepairDirective(
  val sourceMessageId: String,
  val driftEventCreatedAt: Long,
  val signals: List<String>,
  val tabooMatches: List<String>,
  val prompt: String,
)

internal data class CurrentTurnMedia(
  val images: List<Bitmap> = emptyList(),
  val audioClips: List<ByteArray> = emptyList(),
  val historicalImageCount: Int = 0,
  val currentImageCount: Int = 0,
  val historicalAudioCount: Int = 0,
  val currentAudioCount: Int = 0,
  val overflowText: String = "",
)

internal const val DEFAULT_BRANCH_ID = "main"
internal const val IMAGE_CONTEXT_TEXT_MAX_CHARS = 240
internal const val STYLE_REPAIR_PROMPT_MAX_CHARS = 420
internal const val STYLE_REPAIR_SAMPLE_MAX_CHARS = 72
internal const val DRIFT_BASELINE_ASSISTANT_COUNT = 3
internal const val DRIFT_MIN_BASELINE_SENTENCE_LENGTH = 18
internal const val DRIFT_MIN_SENTENCE_DELTA = 18

internal const val IMAGE_CONTEXT_SYSTEM_PROMPT =
  "You are extracting persistent visual memory for a continuing roleplay chat. " +
    "Describe only what is visibly present so the chat can remember the image later even when the raw image is not resent."
internal const val IMAGE_CONTEXT_USER_PROMPT =
  "Return one short plain-text sentence with the key visible details, including any readable text or numbers. " +
    "Do not use markdown, bullets, or speculation."
internal const val AUDIO_CONTEXT_SYSTEM_PROMPT =
  "You are extracting persistent audio memory for a continuing roleplay chat. " +
    "Transcribe or summarize only the audible content needed for the next reply."
internal const val AUDIO_CONTEXT_USER_PROMPT =
  "Return one short plain-text sentence with the key spoken content, sound cues, and speaker intent. " +
    "Do not use markdown, bullets, or speculation."

internal val ASSISTANT_META_PATTERNS =
  listOf(
    Regex("\\bas an ai\\b", RegexOption.IGNORE_CASE),
    Regex("\\bas (?:your )?assistant\\b", RegexOption.IGNORE_CASE),
    Regex("\\blanguage model\\b", RegexOption.IGNORE_CASE),
    Regex("\\bi(?:'m| am) here to help\\b", RegexOption.IGNORE_CASE),
    Regex("\\bhow can i (?:help|assist)\\b", RegexOption.IGNORE_CASE),
  )
internal val SYSTEM_EXPLANATION_PATTERNS =
  listOf(
    Regex("\\bsystem prompt\\b", RegexOption.IGNORE_CASE),
    Regex("\\bdeveloper (?:message|instruction)s?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bcontext window\\b", RegexOption.IGNORE_CASE),
    Regex("\\bconversation history\\b", RegexOption.IGNORE_CASE),
    Regex("\\btoken(?:s)?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bprompt injection\\b", RegexOption.IGNORE_CASE),
  )
internal val OOC_PATTERNS =
  listOf(
    Regex("\\[ooc\\]", RegexOption.IGNORE_CASE),
    Regex("\\booc\\s*:", RegexOption.IGNORE_CASE),
    Regex("\\bout of character\\b", RegexOption.IGNORE_CASE),
    Regex("\\bmeta note\\b", RegexOption.IGNORE_CASE),
  )
internal val SENTENCE_BREAK_REGEX = Regex("[.!?。！？\\n]+")
internal val MULTI_SPACE_REGEX = Regex("\\s+")
