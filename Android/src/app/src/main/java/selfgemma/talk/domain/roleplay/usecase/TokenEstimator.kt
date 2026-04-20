package selfgemma.talk.domain.roleplay.usecase

import javax.inject.Inject

class TokenEstimator @Inject constructor() {
  fun estimate(text: String): Int {
    if (text.isBlank()) {
      return 0
    }

    val normalized = text.trim().replace(WHITESPACE_REGEX, " ")
    return maxOf(1, normalized.length / 4)
  }

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")
  }
}