package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.math.roundToInt
import selfgemma.talk.domain.roleplay.model.CharacterKernel
import selfgemma.talk.domain.roleplay.model.MemoryPolicy
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RuntimeModelParams
import selfgemma.talk.domain.roleplay.model.RuntimeSafetyPolicy
import selfgemma.talk.domain.roleplay.model.cardDataOrEmpty
import selfgemma.talk.domain.roleplay.model.resolvedExampleDialogues
import selfgemma.talk.domain.roleplay.model.resolvedName
import selfgemma.talk.domain.roleplay.model.resolvedPersonaDescription
import selfgemma.talk.domain.roleplay.model.resolvedSummary
import selfgemma.talk.domain.roleplay.model.resolvedSystemPrompt
import selfgemma.talk.domain.roleplay.model.resolvedTags
import selfgemma.talk.domain.roleplay.model.resolvedWorldSettings

private const val CORE_PROMPT_CHAR_LIMIT = 1200
private const val PERSONA_PROMPT_CHAR_LIMIT = 720
private const val WORLD_PROMPT_CHAR_LIMIT = 720
private const val STYLE_PROMPT_CHAR_LIMIT = 360
private const val EXAMPLE_DIGEST_CHAR_LIMIT = 480
private const val COMPILED_RUNTIME_ROLE_WARNING_TOKENS = 768

class CompileRuntimeRoleProfileUseCase @Inject constructor(private val tokenEstimator: TokenEstimator) {
  operator fun invoke(role: RoleCard, now: Long = System.currentTimeMillis()): RoleCard {
    val existingProfile = role.runtimeProfile ?: buildDefaultRuntimeProfile(role)
    val compiledCorePrompt = buildCorePrompt(role).fitToLimit(CORE_PROMPT_CHAR_LIMIT)
    val compiledPersonaPrompt = role.resolvedPersonaDescription().fitToLimit(PERSONA_PROMPT_CHAR_LIMIT)
    val compiledWorldPrompt = role.resolvedWorldSettings().fitToLimit(WORLD_PROMPT_CHAR_LIMIT)
    val compiledStylePrompt = buildStylePrompt(role).fitToLimit(STYLE_PROMPT_CHAR_LIMIT)
    val compiledExampleDigest = buildExampleDigest(role).fitToLimit(EXAMPLE_DIGEST_CHAR_LIMIT)
    val sourceFingerprint = computeSourceFingerprint(role)
    val corePromptTokenEstimate = tokenEstimator.estimate(compiledCorePrompt)
    val personaPromptTokenEstimate = tokenEstimator.estimate(compiledPersonaPrompt)
    val worldPromptTokenEstimate = tokenEstimator.estimate(compiledWorldPrompt)
    val stylePromptTokenEstimate = tokenEstimator.estimate(compiledStylePrompt)
    val exampleDigestTokenEstimate = tokenEstimator.estimate(compiledExampleDigest)
    val compiledTotalTokenEstimate =
      corePromptTokenEstimate +
        personaPromptTokenEstimate +
        worldPromptTokenEstimate +
        stylePromptTokenEstimate +
        exampleDigestTokenEstimate
    val characterKernel =
      buildCharacterKernel(
        role = role,
        existingKernel = existingProfile.characterKernel,
        previousFingerprint = existingProfile.sourceFingerprint,
        sourceFingerprint = sourceFingerprint,
        compiledCorePrompt = compiledCorePrompt,
        compiledPersonaPrompt = compiledPersonaPrompt,
        compiledWorldPrompt = compiledWorldPrompt,
        compiledStylePrompt = compiledStylePrompt,
        compiledExampleDigest = compiledExampleDigest,
        now = now,
      )

    return role.copy(
      runtimeProfile =
        existingProfile.copy(
          summary = existingProfile.summary.ifBlank { role.resolvedSummary() },
          characterKernel = characterKernel,
          compiledCorePrompt = compiledCorePrompt,
          compiledPersonaPrompt = compiledPersonaPrompt,
          compiledWorldPrompt = compiledWorldPrompt,
          compiledStylePrompt = compiledStylePrompt,
          compiledExampleDigest = compiledExampleDigest,
          corePromptTokenEstimate = corePromptTokenEstimate,
          personaPromptTokenEstimate = personaPromptTokenEstimate,
          worldPromptTokenEstimate = worldPromptTokenEstimate,
          stylePromptTokenEstimate = stylePromptTokenEstimate,
          exampleDigestTokenEstimate = exampleDigestTokenEstimate,
          compiledTotalTokenEstimate = compiledTotalTokenEstimate,
          oversizeWarning = compiledTotalTokenEstimate > COMPILED_RUNTIME_ROLE_WARNING_TOKENS,
          sourceFingerprint = sourceFingerprint,
          compiledAt = now,
        )
    )
  }

  private fun buildDefaultRuntimeProfile(role: RoleCard): RoleRuntimeProfile {
    return RoleRuntimeProfile(
      summary = role.summary,
      modelParams =
        RuntimeModelParams(
          preferredModelId = role.defaultModelId,
          temperature = role.defaultTemperature,
          topP = role.defaultTopP,
          topK = role.defaultTopK,
          enableThinking = role.enableThinking,
        ),
      memoryPolicy =
        MemoryPolicy(
          enabled = role.memoryEnabled,
          maxItems = role.memoryMaxItems,
          summaryTurnThreshold = role.summaryTurnThreshold,
        ),
      safetyPolicy = RuntimeSafetyPolicy(policyText = role.safetyPolicy),
    )
  }

  private fun buildCorePrompt(role: RoleCard): String {
    val systemPrompt = role.resolvedSystemPrompt().trim()
    val summary = role.resolvedSummary().trim()
    return buildString {
      append("You are roleplaying as ")
      append(role.resolvedName().ifBlank { "the character" })
      append(".")
      if (systemPrompt.isNotBlank()) {
        append("\n")
        append(systemPrompt)
      }
      if (summary.isNotBlank()) {
        append("\n")
        append("Core character: ")
        append(summary)
      }
    }
  }

  private fun buildStylePrompt(role: RoleCard): String {
    val openingLine = role.openingLine.fitToLimit(140)
    val creatorNotes = role.stCard.cardDataOrEmpty().creator_notes.fitToLimit(180)
    val tags = role.resolvedTags().take(6).joinToString(", ").fitToLimit(120)
    return buildString {
      if (openingLine.isNotBlank()) {
        append("Opening tone: ")
        append(openingLine)
      }
      if (creatorNotes.isNotBlank()) {
        if (isNotBlank()) {
          append("\n")
        }
        append("Creator notes: ")
        append(creatorNotes)
      }
      if (tags.isNotBlank()) {
        if (isNotBlank()) {
          append("\n")
        }
        append("Tags: ")
        append(tags)
      }
    }
  }

  private fun buildExampleDigest(role: RoleCard): String {
    val examples =
      role
        .resolvedExampleDialogues()
        .map { it.normalizeWhitespace() }
        .filter(String::isNotBlank)
        .take(2)
    if (examples.isEmpty()) {
      return ""
    }
    return buildString {
      append("Example cues:\n")
      examples.forEachIndexed { index, example ->
        append("- ")
        append(example.fitToLimit(200))
        if (index != examples.lastIndex) {
          append("\n")
        }
      }
    }
  }

  private fun buildCharacterKernel(
    role: RoleCard,
    existingKernel: CharacterKernel?,
    previousFingerprint: String,
    sourceFingerprint: String,
    compiledCorePrompt: String,
    compiledPersonaPrompt: String,
    compiledWorldPrompt: String,
    compiledStylePrompt: String,
    compiledExampleDigest: String,
    now: Long,
  ): CharacterKernel {
    val identityJson =
      JsonObject().apply {
        addProperty("name", role.resolvedName().ifBlank { "the character" })
        deriveRoleDescriptor(role)?.let { addProperty("role", it) }
        deriveCoreMotive(role)?.let { addProperty("core_motive", it) }
        deriveWorldview(role)?.let { addProperty("worldview", it) }
      }
    val speechStyleJson =
      JsonObject().apply {
        addProperty("tone", deriveTone(role, compiledPersonaPrompt, compiledStylePrompt))
        addProperty("sentence_length", inferSentenceLength(role))
        addProperty("directness", inferDirectness(role))
        add("taboo_words", deriveTabooWords(role).toJsonArray())
        add("recurring_patterns", deriveRecurringPatterns(role).toJsonArray())
      }
    val invariants =
      buildList {
        add("never breaks character")
        add("never becomes generic assistant")
        add("prioritize in-character response")
        add("relationship evolves gradually")
        if (role.resolvedWorldSettings().isNotBlank()) {
          add("respect established world logic")
        }
        deriveTabooWords(role).take(2).forEach { add("avoid: $it") }
      }.distinct().take(MAX_KERNEL_INVARIANTS)
    val invariantsJson = JsonObject().apply { add("rules", invariants.toJsonArray()) }
    val microExemplar = buildMicroExemplar(role, compiledExampleDigest, compiledStylePrompt).fitToLimit(MAX_MICRO_EXEMPLAR_LENGTH)
    val tokenBudget =
      tokenEstimator.estimate(
        renderCharacterKernel(
          identityJson = identityJson,
          speechStyleJson = speechStyleJson,
          invariants = invariants,
          microExemplar = microExemplar,
        ),
      )
    val version =
      if (existingKernel != null && previousFingerprint == sourceFingerprint) {
        existingKernel.version
      } else {
        (existingKernel?.version ?: 0) + 1
      }
    return CharacterKernel(
      roleId = role.id,
      version = version,
      identityJson = identityJson.toString(),
      speechStyleJson = speechStyleJson.toString(),
      invariantsJson = invariantsJson.toString(),
      microExemplar = microExemplar,
      tokenBudget = tokenBudget,
      compiledAt = now,
    )
  }

  private fun renderCharacterKernel(
    identityJson: JsonObject,
    speechStyleJson: JsonObject,
    invariants: List<String>,
    microExemplar: String,
  ): String {
    return buildString {
      appendLine("Identity")
      identityJson.entrySet().forEach { (key, value) ->
        append(key.replace('_', ' '))
        append(": ")
        appendLine(value.asString)
      }
      appendLine("Style")
      speechStyleJson.entrySet().forEach { (key, value) ->
        val renderedValue =
          if (value.isJsonArray) {
            value.asJsonArray.joinToString(", ") { item -> item.asString }
          } else {
            value.asString
          }
        append(key.replace('_', ' '))
        append(": ")
        appendLine(renderedValue)
      }
      appendLine("Invariants")
      invariants.forEach { invariant -> appendLine("- $invariant") }
      if (microExemplar.isNotBlank()) {
        appendLine("Micro exemplar")
        append(microExemplar)
      }
    }.trim()
  }

  private fun deriveRoleDescriptor(role: RoleCard): String? {
    return firstUsefulSentence(role.resolvedSummary(), role.resolvedPersonaDescription())?.fitToLimit(120)
  }

  private fun deriveCoreMotive(role: RoleCard): String? {
    return firstPatternSentence(
      sources = listOf(role.resolvedSystemPrompt(), role.resolvedSummary(), role.resolvedPersonaDescription()),
      patterns = MOTIVE_PATTERNS,
    )?.fitToLimit(120)
      ?: firstUsefulSentence(role.resolvedSummary(), role.resolvedSystemPrompt())?.fitToLimit(120)
  }

  private fun deriveWorldview(role: RoleCard): String? {
    return firstUsefulSentence(role.resolvedWorldSettings(), role.resolvedSummary())?.fitToLimit(120)
  }

  private fun deriveTone(role: RoleCard, compiledPersonaPrompt: String, compiledStylePrompt: String): String {
    return firstUsefulSentence(compiledPersonaPrompt, compiledStylePrompt, role.openingLine)
      ?.fitToLimit(80)
      .orEmpty()
      .ifBlank { "grounded and in-character" }
  }

  private fun inferSentenceLength(role: RoleCard): String {
    val samples =
      buildList {
        add(role.openingLine)
        addAll(role.resolvedExampleDialogues())
      }.map(::countWords).filter { it > 0 }
    if (samples.isEmpty()) {
      return "medium"
    }
    val averageWords = samples.average()
    return when {
      averageWords <= 12 -> "short"
      averageWords <= 22 -> "medium"
      else -> "long"
    }
  }

  private fun inferDirectness(role: RoleCard): String {
    val samples =
      buildList {
        add(role.openingLine)
        addAll(role.resolvedExampleDialogues())
      }.map { value -> value.normalizeWhitespace() }.filter(String::isNotBlank)
    if (samples.isEmpty()) {
      return "balanced"
    }
    val directScore =
      samples
        .map { sample ->
          when {
            sample.endsWith("!") || sample.contains("?") -> 0.9
            countWords(sample) <= 10 -> 0.8
            countWords(sample) <= 18 -> 0.6
            else -> 0.35
          }
        }
        .average()
    return when {
      directScore >= 0.78 -> "direct"
      directScore >= 0.55 -> "balanced"
      else -> "measured"
    }
  }

  private fun deriveTabooWords(role: RoleCard): List<String> {
    return TABOO_REGEXES
      .flatMap { regex ->
        regex
          .findAll(role.resolvedSystemPrompt())
          .mapNotNull { match -> match.groupValues.getOrNull(1)?.fitToLimit(48)?.takeIf(String::isNotBlank) }
          .toList()
      }
      .map { phrase ->
        phrase
          .trim()
          .trim('.', ',', ';', ':')
          .removePrefix("to ")
      }
      .distinct()
      .take(3)
  }

  private fun deriveRecurringPatterns(role: RoleCard): List<String> {
    return buildList {
      firstUsefulSentence(role.openingLine)?.fitToLimit(72)?.let(::add)
      firstExampleAssistantReply(role)?.fitToLimit(72)?.let(::add)
      role.resolvedTags().take(2).forEach { tag -> add("tag:$tag") }
    }.distinct().take(3)
  }

  private fun buildMicroExemplar(role: RoleCard, compiledExampleDigest: String, compiledStylePrompt: String): String {
    firstExampleAssistantReply(role)?.let { reply ->
      return "${role.resolvedName().ifBlank { "Character" }}: ${reply.fitToLimit(120)}"
    }
    val openingLine = role.openingLine.fitToLimit(120)
    if (openingLine.isNotBlank()) {
      return "${role.resolvedName().ifBlank { "Character" }}: $openingLine"
    }
    return firstUsefulSentence(compiledExampleDigest, compiledStylePrompt)?.fitToLimit(140).orEmpty()
  }

  private fun firstPatternSentence(sources: List<String>, patterns: List<String>): String? {
    return sources
      .asSequence()
      .flatMap { source -> source.toSentences().asSequence() }
      .firstOrNull { sentence ->
        val normalized = sentence.lowercase()
        patterns.any { pattern -> normalized.contains(pattern) }
      }
  }

  private fun firstUsefulSentence(vararg sources: String?): String? {
    return sources
      .asSequence()
      .map { it.normalizeWhitespace() }
      .flatMap { source -> source.toSentences().asSequence() }
      .map(String::trim)
      .firstOrNull { it.isNotBlank() }
  }

  private fun firstExampleAssistantReply(role: RoleCard): String? {
    val name = role.resolvedName().ifBlank { "assistant" }
    val labels = listOf("$name:", "Assistant:", "{{char}}:")
    return role
      .resolvedExampleDialogues()
      .asSequence()
      .flatMap { example -> example.lines().asSequence() }
      .map(String::trim)
      .firstOrNull { line -> labels.any { label -> line.startsWith(label, ignoreCase = true) } }
      ?.substringAfter(':')
      ?.normalizeWhitespace()
      ?.takeIf(String::isNotBlank)
  }

  private fun countWords(value: String?): Int {
    return value.normalizeWhitespace().split(WHITESPACE_REGEX).count { token -> token.isNotBlank() }
  }

  private fun List<String>.toJsonArray(): JsonArray {
    return JsonArray().apply { this@toJsonArray.forEach(::add) }
  }

  private fun computeSourceFingerprint(role: RoleCard): String {
    val source =
      listOf(
        role.resolvedName(),
        role.resolvedSummary(),
        role.resolvedSystemPrompt(),
        role.resolvedPersonaDescription(),
        role.resolvedWorldSettings(),
        role.openingLine,
        role.resolvedExampleDialogues().joinToString(separator = "\n\n"),
      ).joinToString(separator = "\u001f") { it.normalizeWhitespace() }
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(source.toByteArray()).joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun String?.fitToLimit(maxLength: Int): String {
    return normalizeWhitespace().take(maxLength)
  }

  private fun String.toSentences(): List<String> {
    return split(SENTENCE_SPLIT_REGEX).map { value -> value.normalizeWhitespace() }.filter(String::isNotBlank)
  }

  private fun String?.normalizeWhitespace(): String {
    return this.orEmpty().trim().replace(WHITESPACE_REGEX, " ")
  }

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val SENTENCE_SPLIT_REGEX = Regex("[\\n.!?]")
    private val MOTIVE_PATTERNS =
      listOf("protect", "solve", "survive", "seek", "pursue", "want", "needs", "need", "must", "mission", "goal")
    private val TABOO_REGEXES =
      listOf(
        Regex("\\bnever\\s+([^.,;\\n]+)", RegexOption.IGNORE_CASE),
        Regex("\\bdo not\\s+([^.,;\\n]+)", RegexOption.IGNORE_CASE),
        Regex("\\bdon't\\s+([^.,;\\n]+)", RegexOption.IGNORE_CASE),
        Regex("\\bavoid\\s+([^.,;\\n]+)", RegexOption.IGNORE_CASE),
      )
    private const val MAX_KERNEL_INVARIANTS = 6
    private const val MAX_MICRO_EXEMPLAR_LENGTH = 180
  }
}
