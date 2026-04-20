package selfgemma.talk.domain.roleplay.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import selfgemma.talk.data.roleplay.interop.stcardpng.StPngRoleCardCodec
import javax.inject.Inject
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardExportTarget
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaImportState
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ImportStRoleCardFromUriUseCase
@Inject
constructor(
  @param:ApplicationContext private val appContext: Context,
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val importStV2RoleCardUseCase: ImportStV2RoleCardUseCase,
) {
  suspend fun importFromUri(
    uri: String,
    existingRole: RoleCard? = null,
    now: Long = System.currentTimeMillis(),
  ): RoleCard {
    val metadata = documentRepository.getMetadata(uri)
    val isPng = metadata.mimeType == "image/png" || metadata.displayName?.endsWith(".png", ignoreCase = true) == true
    val sourceBytes =
      if (isPng) {
        documentRepository.readBytes(uri)
      } else {
        null
      }
    val rawJson =
      if (isPng) {
        StPngRoleCardCodec.extractCardJson(checkNotNull(sourceBytes))
      } else {
        documentRepository.readText(uri)
      }
    val normalizedJson = normalizeSupportedSpec(rawJson)

    val imported =
      importStV2RoleCardUseCase.importFromJson(
        rawJson = normalizedJson,
        existingRole = existingRole,
        now = now,
      )

    if (!isPng) {
      return imported
    }

    val persistedAvatarUri = persistImportedPngAvatar(roleId = imported.id, pngBytes = checkNotNull(sourceBytes))

    return imported.copy(
      avatarUri = persistedAvatarUri,
      mediaProfile =
        (imported.mediaProfile ?: RoleMediaProfile()).copy(
          primaryAvatar =
            RoleMediaAsset(
              id = UUID.nameUUIDFromBytes("st-avatar:${imported.id}".toByteArray()).toString(),
              kind = RoleMediaKind.PRIMARY_AVATAR,
              uri = persistedAvatarUri,
              source = RoleMediaSource.ST_PNG_IMPORT,
              createdAt = imported.createdAt,
              updatedAt = imported.updatedAt,
            ),
          importState =
            RoleMediaImportState(
              lastImportedPrimaryAvatarSource = uri,
              importedFromStPng = true,
              lastImportHadEmbeddedImage = true,
            ),
        ),
      interopState =
        imported.interopState?.copy(
          sourceFormat = RoleCardSourceFormat.ST_PNG,
          exportTargetDefault = RoleCardExportTarget.ST_PNG,
        )
    )
  }

  private fun persistImportedPngAvatar(roleId: String, pngBytes: ByteArray): String {
    val avatarDir = File(appContext.filesDir, "roleplay/st-imported-avatars").apply { mkdirs() }
    val avatarFile = File(avatarDir, "$roleId.png")
    avatarFile.writeBytes(pngBytes)
    return avatarFile.absolutePath
  }

  private fun normalizeSupportedSpec(rawJson: String): String {
    return runCatching {
      val jsonObject = JsonParser.parseString(rawJson).asJsonObject
      when (jsonObject.get("spec")?.asString) {
        "chara_card_v3" -> {
          jsonObject.addProperty("spec", "chara_card_v2")
          jsonObject.addProperty("spec_version", "2.0")
          jsonObject.toString()
        }
        "chara_card_v2" -> rawJson
        else -> normalizeLegacyCard(jsonObject)?.toString() ?: rawJson
      }
    }.getOrDefault(rawJson)
  }

  private fun normalizeLegacyCard(jsonObject: JsonObject): JsonObject? {
    val requiredLegacyFields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
    if (requiredLegacyFields.any { field -> !jsonObject.has(field) }) {
      return null
    }

    val name = jsonObject.stringValue("name")
    val description = jsonObject.stringValue("description")
    val personality = jsonObject.stringValue("personality")
    val scenario = jsonObject.stringValue("scenario")
    val firstMes = jsonObject.stringValue("first_mes")
    val mesExample = jsonObject.stringValue("mes_example")
    val creatorNotes = jsonObject.firstStringValue("creatorcomment", "creator_notes")
    val systemPrompt = jsonObject.firstStringValue("system_prompt")
    val postHistoryInstructions = jsonObject.firstStringValue("post_history_instructions")
    val talkativeness = jsonObject.doubleValue("talkativeness") ?: 0.5
    val fav = jsonObject.booleanValue("fav") ?: false
    val tags = jsonObject.toTagArray()
    val alternateGreetings = jsonObject.toJsonStringArray("alternate_greetings")
    val creator = jsonObject.stringValue("creator")
    val characterVersion = jsonObject.stringValue("character_version")
    val characterBook = jsonObject.objectValue("character_book")
    val topLevelExtensions = jsonObject.objectValue("extensions")

    return JsonObject().apply {
      addProperty("spec", "chara_card_v2")
      addProperty("spec_version", "2.0")
      addProperty("name", name)
      addProperty("description", description)
      addProperty("personality", personality)
      addProperty("scenario", scenario)
      addProperty("first_mes", firstMes)
      addProperty("mes_example", mesExample)
      addProperty("creatorcomment", creatorNotes)
      addProperty("avatar", "none")
      addProperty("chat", "$name - ${humanizedDateTime()}")
      addProperty("talkativeness", talkativeness)
      addProperty("fav", fav)
      add("tags", tags.deepCopy())
      addProperty("creator", creator)
      addProperty("create_date", jsonObject.stringValue("create_date"))
      add(
        "data",
        JsonObject().apply {
          addProperty("name", name)
          addProperty("description", description)
          addProperty("personality", personality)
          addProperty("scenario", scenario)
          addProperty("first_mes", firstMes)
          addProperty("mes_example", mesExample)
          addProperty("creator_notes", creatorNotes)
          addProperty("system_prompt", systemPrompt)
          addProperty("post_history_instructions", postHistoryInstructions)
          add("alternate_greetings", alternateGreetings.deepCopy())
          add("tags", tags.deepCopy())
          addProperty("creator", creator)
          addProperty("character_version", characterVersion)
          characterBook?.let { add("character_book", it.deepCopy()) }
          add(
            "extensions",
            (topLevelExtensions?.deepCopy() ?: JsonObject()).apply {
              addProperty("talkativeness", talkativeness)
              addProperty("fav", fav)
              if (!has("world")) {
                addProperty("world", jsonObject.stringValue("world"))
              }
            },
          )
        },
      )
    }
  }

  private fun JsonObject.stringValue(key: String): String {
    val value = get(key) ?: return ""
    return if (value.isJsonNull) "" else value.asString
  }

  private fun JsonObject.firstStringValue(vararg keys: String): String {
    return keys.firstNotNullOfOrNull { key ->
      get(key)?.takeUnless { it.isJsonNull }?.asString
    }.orEmpty()
  }

  private fun JsonObject.doubleValue(key: String): Double? {
    val value = get(key) ?: return null
    if (value.isJsonNull) {
      return null
    }
    return value.asString.toDoubleOrNull()
  }

  private fun JsonObject.booleanValue(key: String): Boolean? {
    val value = get(key) ?: return null
    if (value.isJsonNull) {
      return null
    }
    return when {
      value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
      value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
        when (value.asString.lowercase()) {
          "true" -> true
          "false" -> false
          else -> null
        }
      }
      else -> null
    }
  }

  private fun JsonObject.toTagArray(): JsonArray {
    val tags = get("tags") ?: return JsonArray()
    return when {
      tags.isJsonArray -> tags.asJsonArray.deepCopy()
      tags.isJsonPrimitive && tags.asJsonPrimitive.isString -> {
        JsonArray().apply {
          tags.asString
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(::add)
        }
      }
      else -> JsonArray()
    }
  }

  private fun JsonObject.toJsonStringArray(key: String): JsonArray {
    val value = get(key) ?: return JsonArray()
    return when {
      value.isJsonArray ->
        JsonArray().apply {
          value.asJsonArray.forEach { element ->
            if (!element.isJsonNull) {
              add(element.asString)
            }
          }
        }
      value.isJsonPrimitive && value.asJsonPrimitive.isString ->
        JsonArray().apply {
          value.asString
            .split("\n")
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(::add)
        }
      else -> JsonArray()
    }
  }

  private fun JsonObject.objectValue(key: String): JsonObject? {
    val value = get(key) ?: return null
    if (value.isJsonNull || !value.isJsonObject) {
      return null
    }
    return value.asJsonObject
  }

  private fun humanizedDateTime(timestamp: Long = System.currentTimeMillis()): String {
    val date = java.util.Date(timestamp)
    val calendar = java.util.Calendar.getInstance().apply { time = date }
    fun pad(value: Int, width: Int = 2): String = value.toString().padStart(width, '0')
    return buildString {
      append(calendar.get(java.util.Calendar.YEAR))
      append('-')
      append(pad(calendar.get(java.util.Calendar.MONTH) + 1))
      append('-')
      append(pad(calendar.get(java.util.Calendar.DAY_OF_MONTH)))
      append('@')
      append(pad(calendar.get(java.util.Calendar.HOUR_OF_DAY)))
      append('h')
      append(pad(calendar.get(java.util.Calendar.MINUTE)))
      append('m')
      append(pad(calendar.get(java.util.Calendar.SECOND)))
      append('s')
      append(pad(calendar.get(java.util.Calendar.MILLISECOND), 3))
      append("ms")
    }
  }
}
