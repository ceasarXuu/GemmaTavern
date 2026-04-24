package selfgemma.talk.data.roleplay.mapper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StPersonaConnection
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile

object RoleplayInteropJsonCodec {
  private val gson: Gson = GsonBuilder().create()

  fun encodeRoleCardCore(value: StCharacterCard?): String? {
    return value?.let(gson::toJson)
  }

  fun decodeRoleCardCore(value: String?): StCharacterCard? {
    if (value.isNullOrBlank()) {
      return null
    }
    return gson.fromJson(value, StCharacterCard::class.java)
  }

  fun encodeRoleRuntimeProfile(value: RoleRuntimeProfile?): String? {
    return value?.let(gson::toJson)
  }

  fun decodeRoleRuntimeProfile(value: String?): RoleRuntimeProfile? {
    if (value.isNullOrBlank()) {
      return null
    }
    return gson.fromJson(value, RoleRuntimeProfile::class.java)
  }

  fun encodeRoleInteropState(value: RoleInteropState?): String? {
    return value?.let(gson::toJson)
  }

  fun decodeRoleInteropState(value: String?): RoleInteropState? {
    if (value.isNullOrBlank()) {
      return null
    }
    return gson.fromJson(value, RoleInteropState::class.java)
  }

  fun encodeRoleMediaProfile(value: RoleMediaProfile?): String? {
    return value?.let(gson::toJson)
  }

  fun decodeRoleMediaProfile(value: String?): RoleMediaProfile? {
    if (value.isNullOrBlank()) {
      return null
    }
    return gson.fromJson(value, RoleMediaProfile::class.java)
  }

  fun encodeStUserProfile(value: StUserProfile?): String? {
    return value?.ensureDefaults()?.toStableJsonObject()?.let(gson::toJson)
  }

  fun decodeStUserProfile(value: String?): StUserProfile? {
    if (value.isNullOrBlank()) {
      return null
    }
    return runCatching {
      JsonParser.parseString(value).asJsonObject.toStUserProfile().ensureDefaults()
    }.getOrNull()
  }

  private fun StUserProfile.toStableJsonObject(): JsonObject {
    return JsonObject().apply {
      addProperty("userAvatarId", userAvatarId)
      defaultPersonaId?.let { addProperty("defaultPersonaId", it) }
      add(
        "personas",
        JsonObject().apply {
          personas.forEach { (key, name) -> addProperty(key, name) }
        },
      )
      add(
        "personaDescriptions",
        JsonObject().apply {
          personaDescriptions.forEach { (key, descriptor) -> add(key, descriptor.toStableJsonObject()) }
        },
      )
    }
  }

  private fun StPersonaDescriptor.toStableJsonObject(): JsonObject {
    return JsonObject().apply {
      addProperty("description", description)
      addProperty("title", title)
      addProperty("position", position.rawValue)
      addProperty("depth", depth)
      addProperty("role", role)
      addProperty("lorebook", lorebook)
      add(
        "connections",
        JsonArray().apply {
          connections.forEach { connection ->
            add(
              JsonObject().apply {
                addProperty("type", connection.type)
                addProperty("id", connection.id)
              },
            )
          }
        },
      )
      avatarUri?.let { addProperty("avatarUri", it) }
      avatarEditorSourceUri?.let { addProperty("avatarEditorSourceUri", it) }
      addProperty("avatarCropZoom", avatarCropZoom)
      addProperty("avatarCropOffsetX", avatarCropOffsetX)
      addProperty("avatarCropOffsetY", avatarCropOffsetY)
    }
  }

  private fun JsonObject.toStUserProfile(): StUserProfile {
    return StUserProfile(
      userAvatarId = stringValue("userAvatarId", "a").orEmpty(),
      defaultPersonaId = stringValue("defaultPersonaId", "b")?.takeIf { it.isNotBlank() },
      personas = objectValue("personas", "c").toStringMap(),
      personaDescriptions = objectValue("personaDescriptions", "d").toPersonaDescriptorMap(),
    )
  }

  private fun JsonObject?.toStringMap(): Map<String, String> {
    return this?.entrySet().orEmpty().associate { (key, value) -> key to value.asStringOrBlank() }
  }

  private fun JsonObject?.toPersonaDescriptorMap(): Map<String, StPersonaDescriptor> {
    return this?.entrySet().orEmpty().mapNotNull { (key, value) ->
      val descriptor = value.asJsonObjectOrNull()?.toStPersonaDescriptor() ?: return@mapNotNull null
      key to descriptor
    }.toMap()
  }

  private fun JsonObject.toStPersonaDescriptor(): StPersonaDescriptor {
    return StPersonaDescriptor(
      description = stringValue("description", "a").orEmpty(),
      title = stringValue("title", "b").orEmpty(),
      position = StPersonaDescriptionPosition.fromRawValue(intValue("position", "c") ?: 0),
      depth = intValue("depth", "d") ?: 2,
      role = intValue("role", "e") ?: 0,
      lorebook = stringValue("lorebook", "f").orEmpty(),
      connections = arrayValue("connections", "g").toPersonaConnections(),
      avatarUri = stringValue("avatarUri", "h")?.takeIf { it.isNotBlank() },
      avatarEditorSourceUri = stringValue("avatarEditorSourceUri", "i")?.takeIf { it.isNotBlank() },
      avatarCropZoom = floatValue("avatarCropZoom", "j") ?: 1f,
      avatarCropOffsetX = floatValue("avatarCropOffsetX", "k") ?: 0f,
      avatarCropOffsetY = floatValue("avatarCropOffsetY", "l") ?: 0f,
    )
  }

  private fun JsonArray?.toPersonaConnections(): List<StPersonaConnection> {
    return this?.mapNotNull { element ->
      val item = element.asJsonObjectOrNull() ?: return@mapNotNull null
      val type = item.stringValue("type", "a").orEmpty()
      val id = item.stringValue("id", "b").orEmpty()
      if (type.isBlank() || id.isBlank()) null else StPersonaConnection(type = type, id = id)
    }.orEmpty()
  }

  private fun JsonObject.stringValue(vararg names: String): String? {
    return firstElement(names)?.asStringOrBlank()
  }

  private fun JsonObject.intValue(vararg names: String): Int? {
    return firstElement(names)?.runCatchingNumber { asInt }
  }

  private fun JsonObject.floatValue(vararg names: String): Float? {
    return firstElement(names)?.runCatchingNumber { asFloat }
  }

  private fun JsonObject.objectValue(vararg names: String): JsonObject? {
    return firstElement(names)?.asJsonObjectOrNull()
  }

  private fun JsonObject.arrayValue(vararg names: String): JsonArray? {
    return firstElement(names)?.takeIf { it.isJsonArray }?.asJsonArray
  }

  private fun JsonObject.firstElement(names: Array<out String>): JsonElement? {
    return names.firstNotNullOfOrNull { name -> get(name) }?.takeUnless { it.isJsonNull }
  }

  private fun JsonElement.asStringOrBlank(): String {
    return runCatching { asString }.getOrDefault("")
  }

  private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
    return takeIf { it.isJsonObject }?.asJsonObject
  }

  private fun <T : Number> JsonElement.runCatchingNumber(block: JsonElement.() -> T): T? {
    return runCatching { block() }.getOrNull()
  }
}
