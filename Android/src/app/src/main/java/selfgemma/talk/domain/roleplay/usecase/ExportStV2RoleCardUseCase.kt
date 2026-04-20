package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stcard.StRoleCardInteropMapper
import selfgemma.talk.data.roleplay.interop.stcard.StV2CardSerializer
import selfgemma.talk.domain.roleplay.model.RoleCard

class ExportStV2RoleCardUseCase @Inject constructor() {
  private val serializer = StV2CardSerializer()

  fun exportToJson(role: RoleCard): String {
    val core = StRoleCardInteropMapper.roleCardToExportCore(role)
    val merged = serializer.toJsonObject(core)
    role.interopState?.rawUnknownTopLevelJson.toJsonObjectOrNull()?.entrySet()?.forEach { (key, value) ->
      merged.add(key, value)
    }
    role.interopState?.rawUnknownDataJson.toJsonObjectOrNull()?.takeIf { it.size() > 0 }?.let { unknownData ->
      val data = merged.getAsJsonObject("data") ?: JsonObject().also { merged.add("data", it) }
      unknownData.entrySet().forEach { (key, value) -> data.add(key, value) }
    }
    role.interopState?.rawUnknownExtensionsJson.toJsonObjectOrNull()?.takeIf { it.size() > 0 }?.let { unknownExtensions ->
      val data = merged.getAsJsonObject("data") ?: JsonObject().also { merged.add("data", it) }
      val extensions = data.getAsJsonObject("extensions") ?: JsonObject().also { data.add("extensions", it) }
      unknownExtensions.entrySet().forEach { (key, value) -> extensions.add(key, value) }
    }
    return serializer.serialize(merged)
  }
}

private fun String?.toJsonObjectOrNull(): JsonObject? {
  if (this.isNullOrBlank()) {
    return null
  }
  return runCatching { JsonParser.parseString(this).asJsonObject }.getOrNull()
}
