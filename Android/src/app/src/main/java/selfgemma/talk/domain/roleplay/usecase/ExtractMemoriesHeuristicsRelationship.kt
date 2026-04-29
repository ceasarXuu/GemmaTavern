package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import selfgemma.talk.domain.roleplay.model.Message

internal fun updateRelationshipState(
  relationship: JsonObject,
  userMessage: Message,
  assistantMessage: Message?,
  currentMood: String?,
  dangerLevel: String?,
) {
  val userText = userMessage.content.lowercase()
  val assistantText = assistantMessage?.content.orEmpty().lowercase()
  val combined = listOf(userText, assistantText).filter(String::isNotBlank).joinToString(separator = " ")

  relationship.updateScalar("trust", defaultValue = 2, delta = detectTrustDelta(combined))
  relationship.updateScalar("intimacy", defaultValue = 1, delta = detectIntimacyDelta(combined))
  relationship.updateScalar(
    "tension",
    defaultValue = 1,
    delta = detectTensionDelta(combined = combined, currentMood = currentMood, dangerLevel = dangerLevel),
  )
  relationship.updateScalar("dependence", defaultValue = 1, delta = detectDependenceDelta(combined))
  relationship.updateScalar(
    "initiative",
    defaultValue = 2,
    delta = detectInitiativeDelta(userText = userText, assistantText = assistantText),
  )
  relationship.updateScalar("respect", defaultValue = 2, delta = detectRespectDelta(combined))
  relationship.updateScalar(
    "fear",
    defaultValue = 1,
    delta = detectFearDelta(combined = combined, currentMood = currentMood, dangerLevel = dangerLevel),
  )
}

internal fun detectTrustDelta(combined: String): Int {
  return when {
    combined.emContainsAny(TRUST_NEGATIVE_PATTERNS) -> -1
    combined.emContainsAny(TRUST_POSITIVE_PATTERNS) -> 1
    else -> 0
  }
}

internal fun detectIntimacyDelta(combined: String): Int {
  return when {
    combined.emContainsAny(INTIMACY_NEGATIVE_PATTERNS) -> -1
    combined.emContainsAny(INTIMACY_POSITIVE_PATTERNS) -> 1
    else -> 0
  }
}

internal fun detectTensionDelta(combined: String, currentMood: String?, dangerLevel: String?): Int {
  return when {
    combined.emContainsAny(TENSION_RELIEF_PATTERNS) || currentMood in listOf("calm", "positive") -> -1
    dangerLevel in listOf("critical", "high") || currentMood in listOf("tense", "angry") -> 1
    combined.emContainsAny(TENSION_PRESSURE_PATTERNS) -> 1
    else -> 0
  }
}

internal fun detectDependenceDelta(combined: String): Int {
  return when {
    combined.emContainsAny(DEPENDENCE_NEGATIVE_PATTERNS) -> -1
    combined.emContainsAny(DEPENDENCE_POSITIVE_PATTERNS) -> 1
    else -> 0
  }
}

internal fun detectInitiativeDelta(userText: String, assistantText: String): Int {
  return when {
    assistantText.emContainsAny(INITIATIVE_POSITIVE_PATTERNS) -> 1
    assistantText.isBlank() -> 0
    userText.emContainsAny(INITIATIVE_NEGATIVE_PATTERNS) -> -1
    else -> 0
  }
}

internal fun detectRespectDelta(combined: String): Int {
  return when {
    combined.emContainsAny(RESPECT_NEGATIVE_PATTERNS) -> -1
    combined.emContainsAny(RESPECT_POSITIVE_PATTERNS) -> 1
    else -> 0
  }
}

internal fun detectFearDelta(combined: String, currentMood: String?, dangerLevel: String?): Int {
  return when {
    combined.emContainsAny(FEAR_RELIEF_PATTERNS) || currentMood in listOf("calm", "positive") -> -1
    dangerLevel in listOf("critical", "high") || currentMood == "tense" || combined.emContainsAny(FEAR_POSITIVE_PATTERNS) -> 1
    else -> 0
  }
}
