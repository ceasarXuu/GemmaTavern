package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class RoleplayToolTurnMetadata(
  val userMessageIds: List<String>,
  val toolNames: List<String>,
  val externalFactIds: List<String>,
  val excludeFromStableSynopsis: Boolean,
  val externalFactCount: Int,
)

internal fun parseRoleplayToolTurnMetadata(metadataJson: String?): RoleplayToolTurnMetadata? {
  if (metadataJson.isNullOrBlank()) {
    return null
  }
  val root = runCatching { JsonParser.parseString(metadataJson).asJsonObject }.getOrNull() ?: return null
  val toolTurn = root.getAsJsonObject(ROLEPLAY_TOOL_TURN_KEY) ?: return null
  return RoleplayToolTurnMetadata(
    userMessageIds = toolTurn.getAsJsonArray("userMessageIds").toStringList(),
    toolNames = toolTurn.getAsJsonArray("toolNames").toStringList(),
    externalFactIds = toolTurn.getAsJsonArray("externalFactIds").toStringList(),
    excludeFromStableSynopsis = toolTurn.get("excludeFromStableSynopsis")?.asBoolean ?: false,
    externalFactCount = toolTurn.get("externalFactCount")?.asInt ?: 0,
  )
}

internal fun mergeRoleplayToolTurnMetadata(
  metadataJson: String?,
  metadata: RoleplayToolTurnMetadata,
): String {
  val root = runCatching { JsonParser.parseString(metadataJson ?: "{}").asJsonObject }.getOrElse { JsonObject() }
  root.add(
    ROLEPLAY_TOOL_TURN_KEY,
    JsonObject().apply {
      add("userMessageIds", metadata.userMessageIds.toJsonArray())
      add("toolNames", metadata.toolNames.toJsonArray())
      add("externalFactIds", metadata.externalFactIds.toJsonArray())
      addProperty("excludeFromStableSynopsis", metadata.excludeFromStableSynopsis)
      addProperty("externalFactCount", metadata.externalFactCount)
    },
  )
  return root.toString()
}

private fun JsonArray?.toStringList(): List<String> {
  return this
    ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }
    .orEmpty()
}

private fun List<String>.toJsonArray(): JsonArray {
  return JsonArray().apply {
    this@toJsonArray.forEach(::add)
  }
}

private const val ROLEPLAY_TOOL_TURN_KEY = "roleplayToolTurn"
