package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Package-shared helpers consolidated from previously duplicated file-private definitions across
 * the [selfgemma.talk.domain.roleplay.usecase] package. Each helper preserves the exact behavior
 * of the original copy. Visibility is intentionally `internal` so all use-cases in this package
 * can reference the same implementation without re-declaration.
 *
 * The canonical [WHITESPACE_REGEX] for this package lives in [ExtractMemoriesInternals.kt].
 */

/**
 * Trims and collapses interior whitespace runs, treating null as the empty string. Mirrors the
 * previously duplicated `String?.normalizeWhitespace()` / `String.normalizeWhitespace()` helpers in
 * [PromptMaterialBuilder], [CompileRoleplayMemoryContextUseCase] and [CompileRuntimeRoleProfileUseCase].
 */
internal fun String?.normalizeWhitespace(): String {
  return this.orEmpty().trim().replace(WHITESPACE_REGEX, " ")
}

/**
 * Parses a JSON string as a [JsonObject], returning null on parse failure or when blank/null.
 * Mirrors the previously duplicated `String?.toJsonObjectOrNull()` / `String.toJsonObjectOrNull()`
 * helpers in [PromptMaterialBuilder], [SendRoleplayMessageUseCase] and [ExportStV2RoleCardUseCase].
 */
internal fun String?.toJsonObjectOrNull(): JsonObject? {
  if (this.isNullOrBlank()) {
    return null
  }
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}

/**
 * Maps a SillyTavern role integer to the canonical prompt role name. Mirrors the previously
 * duplicated `Int?.toPromptRoleName()` helpers in [PromptAssembler] and [StCharacterBookRuntime].
 */
internal fun Int?.toPromptRoleName(): String {
  return when (this) {
    1 -> "user"
    2 -> "assistant"
    else -> "system"
  }
}
