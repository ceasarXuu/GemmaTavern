package selfgemma.talk.domain.roleplay.usecase

import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.CompactionCacheEntry
import selfgemma.talk.domain.roleplay.model.CompactionSummaryType
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageKind
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.MessageStatus
import selfgemma.talk.domain.roleplay.model.SessionEvent
import selfgemma.talk.domain.roleplay.model.SessionEventType
import selfgemma.talk.domain.roleplay.model.SessionSummary
import selfgemma.talk.domain.roleplay.model.resolveUserProfile
import selfgemma.talk.domain.roleplay.repository.CompactionCacheRepository
import selfgemma.talk.domain.roleplay.repository.ConversationRepository

private const val SUMMARY_RECENT_MESSAGE_COUNT = 8
private const val SUMMARY_MESSAGE_LINE_LENGTH = 180
private const val MIN_COMPACTION_MESSAGE_COUNT = 12
private const val MIN_SCENE_SHIFT_COMPACTION_MESSAGE_COUNT = 6
private const val COMPACTION_MESSAGE_LINE_LENGTH = 140
private const val COMPACTION_MAX_MESSAGE_SAMPLES = 4

class SummarizeSessionUseCase
@Inject
constructor(
  private val dataStoreRepository: DataStoreRepository,
  private val conversationRepository: ConversationRepository,
  private val compactionCacheRepository: CompactionCacheRepository,
  private val tokenEstimator: TokenEstimator,
) {
  suspend operator fun invoke(sessionId: String) {
    val session = conversationRepository.getSession(sessionId) ?: return
    val existingSummary = conversationRepository.getSummary(sessionId)
    val relevantMessages =
      conversationRepository.listCanonicalMessages(sessionId).filter { message ->
        message.kind == MessageKind.TEXT &&
          message.side != MessageSide.SYSTEM &&
          message.content.isNotBlank() &&
          (message.status == MessageStatus.COMPLETED || message.status == MessageStatus.INTERRUPTED)
      }
    val excludedMessageIds = collectSynopsisExcludedMessageIds(relevantMessages)
    val stableMessages = relevantMessages.filterNot { message -> message.id in excludedMessageIds }

    if (stableMessages.isEmpty()) {
      return
    }

    val recentMessages = stableMessages.takeLast(SUMMARY_RECENT_MESSAGE_COUNT)
    val now = System.currentTimeMillis()
    val userName = session.resolveUserProfile(dataStoreRepository.getStUserProfile()).userName
    val summaryText = buildSummary(recentMessages = recentMessages, userName = userName)
    val summary =
      SessionSummary(
        sessionId = sessionId,
        version = (existingSummary?.version ?: 0) + 1,
        coveredUntilSeq = recentMessages.maxOf { it.seq },
        summaryText = summaryText,
        tokenEstimate = tokenEstimator.estimate(summaryText),
        updatedAt = now,
      )

    conversationRepository.upsertSummary(summary)
    maybeUpsertCompactionCache(
      sessionId = sessionId,
      relevantMessages = relevantMessages,
      userName = userName,
      now = now,
    )
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.SUMMARY_UPDATE,
        payloadJson =
          """{"version":${summary.version},"coveredUntilSeq":${summary.coveredUntilSeq},"stableMessageCount":${stableMessages.size},"excludedMessageCount":${excludedMessageIds.size},"summaryLineCount":${recentMessages.size}}""",
        createdAt = now,
      )
    )
  }

  private fun buildSummary(
    recentMessages: List<selfgemma.talk.domain.roleplay.model.Message>,
    userName: String,
  ): String {
    return buildString {
      appendLine("Stable synopsis:")
      recentMessages.forEach { message ->
        appendLine(
          "- ${message.side.toSpeakerLabel(userName)}: ${message.content.toSummaryLine(SUMMARY_MESSAGE_LINE_LENGTH)}"
        )
      }
    }
      .trim()
  }

  private fun collectSynopsisExcludedMessageIds(messages: List<Message>): Set<String> {
    val excludedIds = linkedSetOf<String>()
    messages
      .filter { message -> message.side == MessageSide.ASSISTANT }
      .forEach { message ->
        val metadata = parseRoleplayToolTurnMetadata(message.metadataJson) ?: return@forEach
        if (!metadata.excludeFromStableSynopsis) {
          return@forEach
        }
        excludedIds += message.id
        excludedIds += metadata.userMessageIds
      }
    return excludedIds
  }

  private suspend fun maybeUpsertCompactionCache(
    sessionId: String,
    relevantMessages: List<Message>,
    userName: String,
    now: Long,
  ) {
    val eligibleMessages = relevantMessages.dropLast(SUMMARY_RECENT_MESSAGE_COUNT)
    if (eligibleMessages.isEmpty()) {
      return
    }

    val existingEntries = compactionCacheRepository.listBySession(sessionId)
    val eligibleIndexById = eligibleMessages.mapIndexed { index, message -> message.id to index }.toMap()
    val lastCoveredIndex =
      existingEntries.mapNotNull { entry ->
        eligibleIndexById[entry.rangeEndMessageId]
      }.maxOrNull() ?: -1
    val uncoveredMessages = eligibleMessages.drop(lastCoveredIndex + 1)
    if (!shouldCreateCompaction(uncoveredMessages)) {
      return
    }

    val summaryType = resolveCompactionSummaryType(uncoveredMessages)
    val compactText = buildCompactionText(messages = uncoveredMessages, userName = userName, summaryType = summaryType)
    if (compactText.isBlank()) {
      return
    }

    val startMessage = uncoveredMessages.first()
    val endMessage = uncoveredMessages.last()
    val sourceHash = hashMessages(uncoveredMessages)
    val entry =
      CompactionCacheEntry(
        id = "$sessionId:${startMessage.id}:${endMessage.id}",
        sessionId = sessionId,
        rangeStartMessageId = startMessage.id,
        rangeEndMessageId = endMessage.id,
        summaryType = summaryType,
        compactText = compactText,
        sourceHash = sourceHash,
        tokenEstimate = tokenEstimator.estimate(compactText),
        updatedAt = now,
      )
    compactionCacheRepository.upsert(entry)
    conversationRepository.appendEvent(
      SessionEvent(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        eventType = SessionEventType.MEMORY_COMPACTION_UPSERTED,
        payloadJson =
          """{"rangeStartMessageId":"${entry.rangeStartMessageId}","rangeEndMessageId":"${entry.rangeEndMessageId}","summaryType":"${entry.summaryType.name}","tokenEstimate":${entry.tokenEstimate}}""",
        createdAt = now,
      )
    )
  }

  private fun shouldCreateCompaction(messages: List<Message>): Boolean {
    if (messages.size >= MIN_COMPACTION_MESSAGE_COUNT) {
      return true
    }
    return messages.size >= MIN_SCENE_SHIFT_COMPACTION_MESSAGE_COUNT && containsSceneShift(messages)
  }

  private fun resolveCompactionSummaryType(messages: List<Message>): CompactionSummaryType {
    return when {
      messages.size >= 32 -> CompactionSummaryType.ARC
      messages.size >= 20 -> CompactionSummaryType.CHAPTER
      else -> CompactionSummaryType.SCENE
    }
  }

  private fun containsSceneShift(messages: List<Message>): Boolean {
    return messages.any { message ->
      val normalized = message.content.lowercase()
      normalized.containsAny(SCENE_SHIFT_PATTERNS)
    }
  }

  private fun buildCompactionText(
    messages: List<Message>,
    userName: String,
    summaryType: CompactionSummaryType,
  ): String {
    val sampledMessages = selectCompactionMessages(messages)
    if (sampledMessages.isEmpty()) {
      return ""
    }

    return buildString {
      appendLine("${summaryType.name.lowercase().replaceFirstChar { it.uppercase() }} window:")
      sampledMessages.forEach { message ->
        appendLine(
          "- ${message.side.toSpeakerLabel(userName)}: ${message.content.toSummaryLine(COMPACTION_MESSAGE_LINE_LENGTH)}"
        )
      }
    }.trim()
  }

  private fun selectCompactionMessages(messages: List<Message>): List<Message> {
    if (messages.size <= COMPACTION_MAX_MESSAGE_SAMPLES) {
      return messages
    }

    val selected = linkedSetOf<Message>()
    selected += messages.first()
    selected += messages[messages.lastIndex / 3]
    selected += messages[(messages.lastIndex * 2) / 3]
    selected += messages.last()
    return selected.toList()
  }

  private fun hashMessages(messages: List<Message>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val raw =
      messages.joinToString(separator = "\n") { message ->
        "${message.id}|${message.seq}|${message.side.name}|${message.content.toSummaryLine(SUMMARY_MESSAGE_LINE_LENGTH)}"
      }
    return digest.digest(raw.toByteArray()).joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun String.toSummaryLine(maxLength: Int): String {
    return trim().replace(WHITESPACE_REGEX, " ").take(maxLength)
  }

  private fun MessageSide.toSpeakerLabel(userName: String): String {
    return when (this) {
      MessageSide.USER -> userName
      MessageSide.ASSISTANT -> "Assistant"
      MessageSide.SYSTEM -> "System"
    }
  }

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val SCENE_SHIFT_PATTERNS =
      listOf("later", "hours later", "meanwhile", "back at", "arrive at", "now at", "at dawn", "at dusk", "scene changes")
  }
}

private fun String.containsAny(patterns: List<String>): Boolean = patterns.any(::contains)
