package selfgemma.talk.domain.roleplay.usecase

import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadType

internal fun extractOpenThreadCandidates(
  userMessage: Message,
  assistantMessage: Message?,
): List<OpenThreadCandidate> {
  return buildList {
      extractTaskCandidate(userMessage, assistantMessage)?.let(::add)
      extractPromiseCandidate(assistantMessage)?.let(::add)
      extractMysteryCandidate(userMessage, assistantMessage)?.let(::add)
      extractEmotionalCandidate(userMessage, assistantMessage)?.let(::add)
    }
    .distinctBy { normalizeForHash(it.content) }
}

internal fun extractTaskCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
  val sourceMessage = assistantMessage?.takeIf { it.content.lowercase().emContainsAny(TASK_PATTERNS) } ?: userMessage
  val sentence = firstSentenceContaining(sourceMessage.content, TASK_PATTERNS) ?: return null
  return OpenThreadCandidate(
    type = OpenThreadType.TASK,
    owner = OpenThreadOwner.SHARED,
    content = sentence.take(EM_MAX_THREAD_CONTENT_LENGTH),
    priority = 88,
    sourceMessageIds = listOf(sourceMessage.id),
  )
}

internal fun extractPromiseCandidate(message: Message?): OpenThreadCandidate? {
  val sourceMessage = message ?: return null
  val sentence = firstSentenceContaining(sourceMessage.content, PROMISE_PATTERNS) ?: return null
  return OpenThreadCandidate(
    type = OpenThreadType.PROMISE,
    owner = OpenThreadOwner.ASSISTANT,
    content = sentence.take(EM_MAX_THREAD_CONTENT_LENGTH),
    priority = 90,
    sourceMessageIds = listOf(sourceMessage.id),
  )
}

internal fun extractMysteryCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
  val sourceMessage =
    listOfNotNull(userMessage, assistantMessage)
      .firstOrNull { message -> message.content.lowercase().emContainsAny(MYSTERY_PATTERNS) }
      ?: return null
  val sentence = firstSentenceContaining(sourceMessage.content, MYSTERY_PATTERNS) ?: return null
  return OpenThreadCandidate(
    type = OpenThreadType.MYSTERY,
    owner = OpenThreadOwner.SHARED,
    content = sentence.take(EM_MAX_THREAD_CONTENT_LENGTH),
    priority = 84,
    sourceMessageIds = listOf(sourceMessage.id),
  )
}

internal fun extractEmotionalCandidate(userMessage: Message, assistantMessage: Message?): OpenThreadCandidate? {
  val sourceMessage =
    listOfNotNull(userMessage, assistantMessage)
      .firstOrNull { message -> message.content.lowercase().emContainsAny(EMOTIONAL_PATTERNS) }
      ?: return null
  val sentence = firstSentenceContaining(sourceMessage.content, EMOTIONAL_PATTERNS) ?: return null
  return OpenThreadCandidate(
    type = OpenThreadType.EMOTIONAL,
    owner = if (sourceMessage.side == MessageSide.USER) OpenThreadOwner.USER else OpenThreadOwner.ASSISTANT,
    content = sentence.take(EM_MAX_THREAD_CONTENT_LENGTH),
    priority = 76,
    sourceMessageIds = listOf(sourceMessage.id),
  )
}

internal fun shouldResolveThread(thread: OpenThread, resolutionText: String): Boolean {
  val threadTerms =
    normalizeForHash(thread.content)
      .split(WHITESPACE_REGEX)
      .filter { term -> term.length >= 4 && term !in THREAD_STOP_WORDS }
  val overlap = threadTerms.count { term -> resolutionText.contains(term) }
  return overlap >= 1
}
