package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID
import selfgemma.talk.domain.roleplay.model.MemoryAtom
import selfgemma.talk.domain.roleplay.model.MemoryBranchScope
import selfgemma.talk.domain.roleplay.model.MemoryCategory
import selfgemma.talk.domain.roleplay.model.MemoryEpistemicStatus
import selfgemma.talk.domain.roleplay.model.MemoryItem
import selfgemma.talk.domain.roleplay.model.MemoryNamespace
import selfgemma.talk.domain.roleplay.model.MemoryPlane
import selfgemma.talk.domain.roleplay.model.MemoryStability
import selfgemma.talk.domain.roleplay.model.Message
import selfgemma.talk.domain.roleplay.model.MessageSide
import selfgemma.talk.domain.roleplay.model.OpenThread
import selfgemma.talk.domain.roleplay.model.OpenThreadOwner
import selfgemma.talk.domain.roleplay.model.OpenThreadType
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RuntimeStateSnapshot
import selfgemma.talk.domain.roleplay.model.Session

/**
 * Pure heuristic helpers extracted from [ExtractMemoriesUseCase].
 * All functions are stateless top-level helpers; the use case still owns repository orchestration.
 * Behavior is preserved verbatim.
 */

internal const val EM_MAX_MEMORY_LENGTH = 180
internal const val EM_MAX_THREAD_CONTENT_LENGTH = 180
internal const val EM_MAX_RUNTIME_ACTION_LENGTH = 180

internal fun extractFromUserMessage(content: String): List<MemoryCandidate> {
  return content
    .split(SPLIT_REGEX)
    .map(::sanitizeContent)
    .filter { it.length >= 4 }
    .mapNotNull { line ->
      extractPreferenceCorrection(line)?.replacementCandidate ?: inferCandidate(line, MessageSide.USER)
    }
}

internal fun extractFromAssistantMessage(content: String): List<MemoryCandidate> {
  return splitIntoCandidates(content).mapNotNull { line -> inferCandidate(line, MessageSide.ASSISTANT) }
}

internal fun inferCandidate(content: String, side: MessageSide): MemoryCandidate? {
  val sanitized = sanitizeContent(content)
  if (sanitized.length < 12) {
    return null
  }

  val normalized = sanitized.lowercase()

  val category =
    when {
      normalized.emContainsAny(PREFERENCE_PATTERNS) -> MemoryCategory.PREFERENCE
      normalized.emContainsAny(RELATION_PATTERNS) -> MemoryCategory.RELATION
      normalized.emContainsAny(PLOT_PATTERNS) -> MemoryCategory.PLOT
      normalized.emContainsAny(WORLD_PATTERNS) -> MemoryCategory.WORLD
      side == MessageSide.USER && normalized.startsWith("remember") -> MemoryCategory.TODO
      else -> null
    }
      ?: return null

  val confidence = if (side == MessageSide.USER) 0.78f else 0.62f
  return MemoryCandidate(
    category = category,
    content = sanitized,
    confidence = confidence,
    fromAssistant = side == MessageSide.ASSISTANT,
  )
}

internal fun buildMemory(
  session: Session,
  role: RoleCard,
  candidate: MemoryCandidate,
  sourceMessageIds: List<String>,
  existing: MemoryItem?,
  pinned: Boolean?,
  now: Long,
): MemoryItem {
  val hash = hashContent(candidate.content)

  return MemoryItem(
    id = existing?.id ?: UUID.randomUUID().toString(),
    roleId = role.id,
    sessionId = session.id,
    category = existing?.category ?: candidate.category,
    content = candidate.content,
    normalizedHash = hash,
    confidence = maxOf(existing?.confidence ?: 0f, candidate.confidence),
    pinned = pinned ?: existing?.pinned ?: false,
    active = true,
    sourceMessageIds = (existing?.sourceMessageIds.orEmpty() + sourceMessageIds).distinct(),
    createdAt = existing?.createdAt ?: now,
    updatedAt = now,
    lastUsedAt = existing?.lastUsedAt,
  )
}

internal fun buildMemoryAtom(
  session: Session,
  role: RoleCard,
  candidate: MemoryCandidate,
  sourceSide: MessageSide,
  sourceMessageIds: List<String>,
  existing: MemoryAtom?,
  pinned: Boolean?,
  now: Long,
): MemoryAtom {
  val normalizedObjectValue = normalizeForHash(candidate.content)
  val namespace = candidate.toNamespace()
  val subject = candidate.toSubject()
  val predicate = candidate.toPredicate()

  return MemoryAtom(
    id = existing?.id ?: UUID.randomUUID().toString(),
    sessionId = session.id,
    roleId = role.id,
    plane = candidate.toPlane(),
    namespace = namespace,
    subject = subject,
    predicate = predicate,
    objectValue = candidate.content,
    normalizedObjectValue = normalizedObjectValue,
    stability =
      when {
        pinned == true -> MemoryStability.LOCKED
        sourceSide == MessageSide.USER -> MemoryStability.STABLE
        else -> MemoryStability.CANDIDATE
      },
    epistemicStatus =
      when (sourceSide) {
        MessageSide.USER -> MemoryEpistemicStatus.SELF_REPORT
        MessageSide.ASSISTANT -> MemoryEpistemicStatus.OBSERVED
        MessageSide.SYSTEM -> MemoryEpistemicStatus.INFERRED
      },
    salience = candidate.toSalience(),
    confidence = maxOf(existing?.confidence ?: 0f, candidate.confidence),
    timeStartMessageId = sourceMessageIds.firstOrNull(),
    timeEndMessageId = sourceMessageIds.lastOrNull(),
    branchScope = MemoryBranchScope.ACCEPTED_ONLY,
    sourceMessageIds = (existing?.sourceMessageIds.orEmpty() + sourceMessageIds).distinct(),
    evidenceQuote = candidate.content,
    supersedesMemoryId = null,
    tombstone = false,
    createdAt = existing?.createdAt ?: now,
    updatedAt = now,
    lastUsedAt = existing?.lastUsedAt,
  )
}

internal fun extractLocation(vararg contents: String?): String? {
  contents.forEach { raw ->
    val match = LOCATION_REGEX.find(raw.orEmpty()) ?: return@forEach
    val location = normalizeLocationValue(match.groupValues[1])
    if (location.length >= 3) {
      return location
    }
  }
  return null
}

internal fun extractSceneTime(vararg contents: String?): String? {
  contents.forEach { raw ->
    val normalized = raw.orEmpty().lowercase()
    SCENE_TIME_PATTERNS.firstOrNull { (pattern, _) -> normalized.contains(pattern) }?.let { (_, canonicalValue) ->
      return canonicalValue
    }
  }
  return null
}

internal fun extractGoal(vararg contents: String?): String? {
  contents.forEach { raw ->
    val sentence = firstSentenceContaining(raw.orEmpty(), GOAL_PATTERNS) ?: return@forEach
    return sentence.take(EM_MAX_RUNTIME_ACTION_LENGTH)
  }
  return null
}

internal fun buildRecentAction(userMessage: Message, assistantMessage: Message?): String {
  val source = assistantMessage?.content?.takeIf(String::isNotBlank) ?: userMessage.content
  return sanitizeContent(source).take(EM_MAX_RUNTIME_ACTION_LENGTH)
}

internal fun detectMood(content: String): String? {
  val normalized = content.lowercase()
  return when {
    normalized.emContainsAny(listOf("angry", "furious", "annoyed")) -> "angry"
    normalized.emContainsAny(listOf("worried", "anxious", "afraid", "nervous", "tense", "grim", "urgent")) -> "tense"
    normalized.emContainsAny(listOf("sad", "upset", "hurt")) -> "sad"
    normalized.emContainsAny(listOf("happy", "relieved", "glad")) -> "positive"
    normalized.emContainsAny(listOf("calm", "steady", "composed")) -> "calm"
    else -> null
  }
}

internal fun detectDangerLevel(vararg contents: String?): String? {
  contents.forEach { raw ->
    val normalized = raw.orEmpty().lowercase()
    when {
      normalized.emContainsAny(DANGER_CRITICAL_PATTERNS) -> return "critical"
      normalized.emContainsAny(DANGER_HIGH_PATTERNS) -> return "high"
      normalized.emContainsAny(DANGER_GUARDED_PATTERNS) -> return "guarded"
    }
  }
  return null
}

internal fun extractImportantItems(vararg contents: String?): List<String> {
  val items = linkedSetOf<String>()
  contents.forEach { raw ->
    IMPORTANT_ITEM_REGEX.findAll(raw.orEmpty()).forEach { match ->
      sanitizeItemValue(match.groupValues[1])?.let(items::add)
    }
  }
  return items.take(3)
}

internal fun extractActiveTopic(userContent: String, assistantContent: String?): String? {
  if (userContent.contains("?")) {
    return sanitizeContent(userContent.substringBefore("?") + "?").take(96)
  }
  listOf(userContent, assistantContent.orEmpty()).forEach { raw ->
    val sentence = firstSentenceContaining(raw, ACTIVE_TOPIC_PATTERNS) ?: return@forEach
    return sentence.take(96)
  }
  return sanitizeContent(userContent).take(96).takeIf { it.length >= 8 }
}

internal fun extractPresentEntities(
  roleName: String,
  activeEntities: JsonObject,
  userContent: String,
  assistantContent: String?,
): List<String> {
  val entities = linkedSetOf<String>()
  entities.add("user")
  entities.add(roleName)

  listOf(userContent, assistantContent.orEmpty()).forEach { raw ->
    TITLE_CASE_ENTITY_REGEX.findAll(raw).forEach { match ->
      sanitizeEntityValue(match.value)?.let { candidate ->
        if (!candidate.equals(roleName, ignoreCase = true) && candidate.lowercase() !in ENTITY_STOP_WORDS) {
          entities.add(candidate)
        }
      }
    }
  }

  if (entities.size <= 2) {
    activeEntities.getStringArray("present")
      .filterNot { existing ->
        existing.equals("user", ignoreCase = true) || existing.equals(roleName, ignoreCase = true)
      }.take(2)
      .forEach(entities::add)
  }

  return entities.take(4)
}

internal fun splitIntoCandidates(content: String): List<String> {
  return content
    .split(SPLIT_REGEX)
    .map(::sanitizeContent)
    .filter { it.length in 12..EM_MAX_MEMORY_LENGTH }
}

internal fun firstSentenceContaining(content: String, patterns: List<String>): String? {
  return content
    .split(SPLIT_REGEX)
    .map(::sanitizeContent)
    .firstOrNull { line -> line.lowercase().emContainsAny(patterns) && line.length >= 8 }
}

internal fun sanitizeContent(content: String): String {
  return content.trim().replace(WHITESPACE_REGEX, " ").take(EM_MAX_MEMORY_LENGTH)
}

internal fun extractPreferenceCorrection(content: String): ExtractedPreferenceCorrection? {
  val sanitized = sanitizeContent(content)
  if (sanitized.isBlank()) {
    return null
  }

  PREFERENCE_CORRECTION_REGEXES.forEach { regex ->
    val match = regex.find(sanitized) ?: return@forEach
    val preferredValue = sanitizePreferenceValue(match.groupValues[1])
    val rejectedValue = sanitizePreferenceValue(match.groupValues[2])
    if (preferredValue == null || rejectedValue == null || preferredValue == rejectedValue) {
      return@forEach
    }
    return ExtractedPreferenceCorrection(
      rejectedRawValue = rejectedValue,
      rejectedNormalizedValue = normalizeForHash(rejectedValue),
      replacementCandidate =
        MemoryCandidate(
          category = MemoryCategory.PREFERENCE,
          content = "I prefer $preferredValue.",
          confidence = 0.96f,
          fromAssistant = false,
        ),
    )
  }
  return null
}

internal fun extractLocationCorrection(content: String): String? {
  val match = LOCATION_CORRECTION_REGEX.find(content) ?: return null
  return sanitizeLocationValue(match.groupValues[2])
}

internal fun sanitizePreferenceValue(value: String): String? {
  return sanitizeContent(
    value
      .trim()
      .trim('.', ',', ';', ':')
      .removePrefix("the ")
  ).ifBlank { null }
}

internal fun sanitizeLocationValue(value: String): String? {
  return normalizeLocationValue(value).ifBlank { null }
}

internal fun normalizeLocationValue(value: String): String {
  return sanitizeContent(
    value
      .trim()
      .trim('.', ',', ';', ':')
      .replace(TRAILING_LOCATION_TIME_REGEX, "")
      .trim(),
  )
}

internal fun sanitizeItemValue(value: String): String? {
  val normalized =
    sanitizeContent(
    value
      .trim()
      .trim('.', ',', ';', ':')
      .removePrefix("the ")
      .removePrefix("our ")
      .removePrefix("my ")
      .removePrefix("your "),
  )
  val tokens = normalized.split(WHITESPACE_REGEX).filter(String::isNotBlank)
  if (tokens.isEmpty()) {
    return null
  }
  val keywordIndex =
    tokens.indexOfLast { token ->
      token.lowercase() in IMPORTANT_ITEM_KEYWORDS
    }
  if (keywordIndex == -1) {
    return normalized.ifBlank { null }
  }
  val selectedTokens = mutableListOf(tokens[keywordIndex])
  var index = keywordIndex - 1
  while (index >= 0 && selectedTokens.size < 3) {
    val token = tokens[index]
    val normalizedToken = token.lowercase()
    when {
      normalizedToken in ITEM_DETERMINERS -> {
        index -= 1
        continue
      }
      normalizedToken in ITEM_DESCRIPTOR_STOP_WORDS -> break
      else -> selectedTokens.add(0, token)
    }
    index -= 1
  }
  return selectedTokens.joinToString(separator = " ").ifBlank { null }
}

internal fun sanitizeEntityValue(value: String): String? {
  return sanitizeContent(value.trim().trim('.', ',', ';', ':')).ifBlank { null }
}

internal fun normalizeForHash(content: String): String {
  return sanitizeContent(content).lowercase()
}

internal fun hashContent(content: String): String {
  val digest = MessageDigest.getInstance("SHA-256")
  return digest.digest(normalizeForHash(content).toByteArray()).joinToString(separator = "") { byte ->
    "%02x".format(byte)
  }
}

internal fun String?.toMutableJsonObject(): JsonObject {
  if (this.isNullOrBlank()) {
    return JsonObject()
  }
  return runCatching { JsonParser.parseString(this).asJsonObject.deepCopy() }.getOrElse { JsonObject() }
}

internal fun JsonObject.getStringArray(field: String): List<String> {
  val element = get(field) ?: return emptyList()
  if (!element.isJsonArray) {
    return emptyList()
  }
  return element.asJsonArray.mapNotNull { value ->
    value.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
  }
}

internal fun JsonObject.putStringArray(field: String, values: List<String>) {
  add(
    field,
    JsonArray().apply {
      values.filter(String::isNotBlank).forEach(::add)
    },
  )
}

internal fun JsonObject.updateScalar(field: String, defaultValue: Int, delta: Int) {
  val currentValue = get(field)?.takeIf { it.isJsonPrimitive }?.asInt ?: defaultValue
  addProperty(field, (currentValue + delta).coerceIn(0, 5))
}

internal fun String.emContainsAny(patterns: List<String>): Boolean {
  return patterns.any(::contains)
}

internal fun RuntimeStateSnapshot?.extractSceneField(field: String): String? {
  val snapshot = this ?: return null
  val scene = runCatching { JsonParser.parseString(snapshot.sceneJson).asJsonObject }.getOrNull() ?: return null
  return scene.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { null }
}

internal fun MemoryCandidate.atomKey(): String {
  return memoryAtomKey(toSubject(), toPredicate(), normalizeForHash(content), toNamespace())
}

internal fun MemoryAtom.hasSameAtomKey(candidate: MemoryCandidate): Boolean {
  return memoryAtomKey(subject, predicate, normalizedObjectValue, namespace) == candidate.atomKey()
}

internal fun MemoryCandidate.toNamespace(): MemoryNamespace {
  return when (category) {
    MemoryCategory.PREFERENCE,
    MemoryCategory.RELATION,
    MemoryCategory.RULE -> MemoryNamespace.SEMANTIC
    MemoryCategory.WORLD -> MemoryNamespace.WORLD
    MemoryCategory.PLOT -> MemoryNamespace.EPISODIC
    MemoryCategory.TODO -> MemoryNamespace.PROMISE
  }
}

internal fun MemoryCandidate.toSubject(): String {
  return when (category) {
    MemoryCategory.PREFERENCE -> "user"
    MemoryCategory.RELATION -> "relationship"
    MemoryCategory.WORLD -> "world"
    MemoryCategory.PLOT -> "scene"
    MemoryCategory.TODO -> "thread"
    MemoryCategory.RULE -> "rule"
  }
}

internal fun MemoryCandidate.toPredicate(): String {
  return when (category) {
    MemoryCategory.PREFERENCE -> "preference"
    MemoryCategory.RELATION -> "state"
    MemoryCategory.WORLD -> "fact"
    MemoryCategory.PLOT -> "event"
    MemoryCategory.TODO -> "pending"
    MemoryCategory.RULE -> "constraint"
  }
}

internal fun MemoryCandidate.toPlane(): MemoryPlane {
  return when (category) {
    MemoryCategory.WORLD,
    MemoryCategory.PLOT,
    MemoryCategory.RELATION,
    MemoryCategory.TODO -> MemoryPlane.IC
    MemoryCategory.PREFERENCE,
    MemoryCategory.RULE -> MemoryPlane.SHARED
  }
}

internal fun MemoryCandidate.toSalience(): Float {
  return when (category) {
    MemoryCategory.TODO -> 0.95f
    MemoryCategory.RELATION -> 0.9f
    MemoryCategory.PREFERENCE -> 0.86f
    MemoryCategory.WORLD -> 0.82f
    MemoryCategory.RULE -> 0.88f
    MemoryCategory.PLOT -> 0.78f
  }
}
