package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadType

/**
 * Internal helper types, lookup tables, and pattern lists used by [ExtractMemoriesUseCase].
 *
 * They were previously declared inside the use case body; pulling them to the file scope keeps the
 * use case focused on orchestration without changing behavior.
 */

internal data class MemoryCandidate(
  val category: MemoryCategory,
  val content: String,
  val confidence: Float,
  val fromAssistant: Boolean,
)

internal data class ExtractedPreferenceCorrection(
  val rejectedRawValue: String,
  val rejectedNormalizedValue: String,
  val replacementCandidate: MemoryCandidate,
)

internal data class CorrectionResult(
  val correctedLocation: String? = null,
)

internal data class OpenThreadCandidate(
  val type: OpenThreadType,
  val owner: OpenThreadOwner,
  val content: String,
  val priority: Int,
  val sourceMessageIds: List<String>,
)

internal val WHITESPACE_REGEX = Regex("\\s+")
internal val SPLIT_REGEX = Regex("[\\n.!?]")
internal val LOCATION_REGEX = Regex("\\b(?:at|in|inside|outside|near|on)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})")
internal val TRAILING_LOCATION_TIME_REGEX =
  Regex("\\b(?:tonight|today|tomorrow|right now|this morning|this afternoon|this evening|before dawn|by dawn|at dawn|at dusk|at night)\\b.*$", RegexOption.IGNORE_CASE)
internal val LOCATION_CORRECTION_REGEX =
  Regex("\\b(?:we are|we're|i am|i'm)\\s+not\\s+(?:at|in)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})[,.;\\s]+(?:we are|we're|i am|i'm)\\s+(?:at|in)\\s+([A-Za-z][A-Za-z0-9'\\- ]{2,40})", RegexOption.IGNORE_CASE)
internal val TITLE_CASE_ENTITY_REGEX =
  Regex("\\b[A-Z][A-Za-z0-9'\\-]{1,}(?:\\s+[A-Z][A-Za-z0-9'\\-]{1,}){0,2}\\b")
internal val IMPORTANT_ITEM_REGEX =
  Regex("\\b(?:the|a|an|our|my|your)?\\s*([A-Za-z][A-Za-z0-9'\\-]*(?:\\s+[A-Za-z0-9'\\-]+){0,2}\\s+(?:code|key|pass|map|badge|beacon|artifact|device|weapon|dossier|letter|file|ring|amulet|token|book|note))(?!\\s+(?:code|key|pass|map|badge|beacon|artifact|device|weapon|dossier|letter|file|ring|amulet|token|book|note))\\b", RegexOption.IGNORE_CASE)
internal val PREFERENCE_CORRECTION_REGEXES =
  listOf(
    Regex("\\bi\\s+(?:prefer|like|love|want)\\s+(.+?)\\s+(?:instead of|rather than|over)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
    Regex("\\bi\\s+(?:do not|don't)\\s+(?:like|love|want)\\s+(.+?)[,;]\\s*i\\s+(?:prefer|like|love|want)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
    Regex("\\bnot\\s+(.+?)[,;]\\s*i\\s+(?:prefer|like|love|want)\\s+(.+?)(?:[.!?]|$)", RegexOption.IGNORE_CASE),
  )
internal val PREFERENCE_PATTERNS =
  listOf("i like", "i love", "i enjoy", "i prefer", "my favorite", "i hate", "i dislike")
internal val RELATION_PATTERNS =
  listOf("we are", "we're", "you promised", "our bond", "our relationship", "trust me")
internal val PLOT_PATTERNS =
  listOf("we need to", "our mission", "the plan is", "remember that", "the goal is")
internal val WORLD_PATTERNS =
  listOf("my name is", "call me", "i live", "i work", "i study", "i am from", "i'm from")
internal val TASK_PATTERNS = listOf("we need to", "have to", "must", "our mission", "the plan is", "goal is")
internal val PROMISE_PATTERNS = listOf("i will", "i'll", "we will", "we'll", "promise")
internal val MYSTERY_PATTERNS = listOf("don't know", "do not know", "what happened", "who is", "why is", "where is")
internal val EMOTIONAL_PATTERNS = listOf("worried", "afraid", "upset", "angry", "sad", "hurt")
internal val GOAL_PATTERNS = listOf("we need to", "our mission", "the plan is", "the goal is", "must")
internal val ACTIVE_TOPIC_PATTERNS =
  listOf("why", "who", "where", "remember", "need to", "plan", "goal", "code", "key", "promise", "danger")
internal val SCENE_TIME_PATTERNS =
  listOf(
    "before dawn" to "before dawn",
    "by dawn" to "before dawn",
    "at dawn" to "dawn",
    "sunrise" to "sunrise",
    "morning" to "morning",
    "afternoon" to "afternoon",
    "evening" to "evening",
    "sunset" to "sunset",
    "tonight" to "tonight",
    "midnight" to "midnight",
    "night" to "night",
  )
internal val DANGER_CRITICAL_PATTERNS =
  listOf("under attack", "bleeding", "kill", "die", "explod", "alarm", "ambush", "gun", "knife", "fire")
internal val DANGER_HIGH_PATTERNS =
  listOf("danger", "threat", "risk", "chase", "closing in", "tampered", "trap", "hostile", "afraid", "worried")
internal val DANGER_GUARDED_PATTERNS = listOf("careful", "cautious", "tense", "uneasy", "watch out")
internal val TRUST_POSITIVE_PATTERNS =
  listOf("trust you", "trust me", "count on", "watch your back", "i will protect", "i'll protect", "i will help", "i'll help")
internal val TRUST_NEGATIVE_PATTERNS =
  listOf("can't trust", "cannot trust", "do not trust", "don't trust", "betray", "deceived", "lied", "ignored me")
internal val INTIMACY_POSITIVE_PATTERNS =
  listOf("stay with you", "glad you're here", "care about you", "hold you", "hug", "kiss", "close to you")
internal val INTIMACY_NEGATIVE_PATTERNS = listOf("stay away", "back off", "leave me alone", "keep your distance", "don't touch")
internal val TENSION_PRESSURE_PATTERNS =
  listOf("argument", "fight", "pressure", "deadline", "hurry", "closing in", "threat", "alarm")
internal val TENSION_RELIEF_PATTERNS = listOf("resolved", "safe now", "steady now", "we're clear", "all clear", "relieved")
internal val DEPENDENCE_POSITIVE_PATTERNS =
  listOf("need you", "can't do this without", "cannot do this without", "rely on you", "help me", "cover me")
internal val DEPENDENCE_NEGATIVE_PATTERNS =
  listOf("i can handle it alone", "i don't need you", "i do not need you", "without your help")
internal val INITIATIVE_POSITIVE_PATTERNS =
  listOf("i will", "i'll", "let me", "follow me", "move now", "stay behind me", "i can handle")
internal val INITIATIVE_NEGATIVE_PATTERNS = listOf("you need to", "you must", "go do", "listen to me", "remember this")
internal val RESPECT_POSITIVE_PATTERNS =
  listOf("you're right", "you are right", "good call", "smart", "impressive", "captain", "sir", "ma'am", "maam")
internal val RESPECT_NEGATIVE_PATTERNS = listOf("idiot", "stupid", "useless", "pathetic", "incompetent", "fool")
internal val FEAR_POSITIVE_PATTERNS =
  listOf("afraid", "scared", "terrified", "panic", "danger", "threat", "under attack", "bleeding")
internal val FEAR_RELIEF_PATTERNS = listOf("safe now", "all clear", "steady now", "calm down", "we made it")
internal val ENTITY_STOP_WORDS =
  setOf("we", "i", "you", "the", "a", "an", "this", "that", "tonight", "tomorrow", "today", "dawn")
internal val IMPORTANT_ITEM_KEYWORDS =
  setOf("code", "key", "pass", "map", "badge", "beacon", "artifact", "device", "weapon", "dossier", "letter", "file", "ring", "amulet", "token", "book", "note")
internal val ITEM_DETERMINERS = setOf("the", "our", "my", "your", "a", "an")
internal val ITEM_DESCRIPTOR_STOP_WORDS =
  setOf("trade", "keep", "carry", "take", "bring", "find", "grab", "hold", "use", "need", "remember", "about", "with", "before", "after", "to", "so")
internal val THREAD_RESOLUTION_PATTERNS =
  listOf("resolved", "solved", "done", "finished", "answered", "figured out", "no longer", "never mind")
internal val THREAD_STOP_WORDS = setOf("what", "when", "where", "with", "have", "this", "that", "from")

internal fun memoryAtomKey(
  subject: String,
  predicate: String,
  normalizedObjectValue: String,
  namespace: MemoryNamespace,
): String {
  return listOf(namespace.name, subject, predicate, normalizedObjectValue).joinToString(separator = "::")
}
