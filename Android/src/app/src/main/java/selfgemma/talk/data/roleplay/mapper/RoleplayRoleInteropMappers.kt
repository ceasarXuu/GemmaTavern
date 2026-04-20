package selfgemma.talk.data.roleplay.mapper

import java.util.UUID
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.domain.roleplay.model.MemoryPolicy
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardExportTarget
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaImportState
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RuntimeModelParams
import selfgemma.talk.domain.roleplay.model.RuntimeSafetyPolicy
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData

internal fun RoleEntity.toRoleCardCoreOrLegacy(): StCharacterCard {
  return cardCoreJson
    ?.takeIf { it.isNotBlank() }
    ?.let(RoleplayInteropJsonCodec::decodeRoleCardCore)
    ?: buildLegacyStCard(
      name = name,
      description = summary,
      personality = personaDescription,
      scenario = worldSettings,
      firstMessage = openingLine,
      messageExample = exampleDialogues.joinToString("\n\n"),
      systemPrompt = systemPrompt,
      tags = tags,
    )
}

internal fun RoleEntity.toRoleRuntimeProfileOrLegacy(): RoleRuntimeProfile {
  return runtimeProfileJson
    ?.takeIf { it.isNotBlank() }
    ?.let(RoleplayInteropJsonCodec::decodeRoleRuntimeProfile)
    ?: RoleRuntimeProfile(
      summary = summary,
      modelParams =
        RuntimeModelParams(
          preferredModelId = defaultModelId,
          temperature = defaultTemperature,
          topP = defaultTopP,
          topK = defaultTopK,
          enableThinking = enableThinking,
        ),
      memoryPolicy =
        MemoryPolicy(
          enabled = memoryEnabled,
          maxItems = memoryMaxItems,
          summaryTurnThreshold = summaryTurnThreshold,
        ),
      safetyPolicy = RuntimeSafetyPolicy(policyText = safetyPolicy),
    )
}

internal fun RoleEntity.toRoleInteropStateOrDefault(): RoleInteropState {
  return interopStateJson
    ?.takeIf { it.isNotBlank() }
    ?.let(RoleplayInteropJsonCodec::decodeRoleInteropState)
    ?: RoleInteropState(
      sourceFormat = RoleCardSourceFormat.INTERNAL,
      sourceSpec = null,
      sourceSpecVersion = null,
      exportTargetDefault = RoleCardExportTarget.ST_V2_JSON,
    )
}

internal fun RoleEntity.toRoleMediaProfileOrLegacy(): RoleMediaProfile {
  return mediaProfileJson
    ?.takeIf { it.isNotBlank() }
    ?.let(RoleplayInteropJsonCodec::decodeRoleMediaProfile)
    ?: RoleMediaProfile(
      primaryAvatar =
        avatarUri?.let { uri ->
          RoleMediaAsset(
            id = UUID.nameUUIDFromBytes("avatar:$id:$uri".toByteArray()).toString(),
            kind = RoleMediaKind.PRIMARY_AVATAR,
            uri = uri,
            displayName = name.ifBlank { "Primary avatar" },
            source =
              if (toRoleInteropStateOrDefault().sourceFormat == RoleCardSourceFormat.ST_PNG) {
                RoleMediaSource.ST_PNG_IMPORT
              } else {
                RoleMediaSource.MIGRATED_LEGACY
              },
            createdAt = createdAt,
            updatedAt = updatedAt,
          )
        },
      coverImage =
        coverUri?.let { uri ->
          RoleMediaAsset(
            id = UUID.nameUUIDFromBytes("cover:$id:$uri".toByteArray()).toString(),
            kind = RoleMediaKind.COVER,
            uri = uri,
            displayName = "$name cover".trim(),
            source = RoleMediaSource.MIGRATED_LEGACY,
            createdAt = createdAt,
            updatedAt = updatedAt,
          )
        },
      importState =
        RoleMediaImportState(
          lastImportedPrimaryAvatarSource = avatarUri,
          importedFromStPng = toRoleInteropStateOrDefault().sourceFormat == RoleCardSourceFormat.ST_PNG,
          lastImportHadEmbeddedImage = !avatarUri.isNullOrBlank(),
        ),
    )
}

internal fun RoleCard.toPersistedRoleCardCore(): StCharacterCard {
  return stCard
}

internal fun RoleCard.toPersistedRoleRuntimeProfile(): RoleRuntimeProfile {
  return runtimeProfile
    ?: RoleRuntimeProfile(
      summary = summary,
      modelParams =
        RuntimeModelParams(
          preferredModelId = defaultModelId,
          temperature = defaultTemperature,
          topP = defaultTopP,
          topK = defaultTopK,
          enableThinking = enableThinking,
        ),
      memoryPolicy =
        MemoryPolicy(
          enabled = memoryEnabled,
          maxItems = memoryMaxItems,
          summaryTurnThreshold = summaryTurnThreshold,
        ),
      safetyPolicy = RuntimeSafetyPolicy(policyText = safetyPolicy),
    )
}

internal fun RoleCard.toPersistedRoleMediaProfile(): RoleMediaProfile {
  return mediaProfile
    ?: RoleMediaProfile(
      primaryAvatar =
        avatarUri?.let { uri ->
          RoleMediaAsset(
            id = UUID.nameUUIDFromBytes("avatar:$id:$uri".toByteArray()).toString(),
            kind = RoleMediaKind.PRIMARY_AVATAR,
            uri = uri,
            displayName = name.ifBlank { "Primary avatar" },
            source =
              if (interopState?.sourceFormat == RoleCardSourceFormat.ST_PNG) {
                RoleMediaSource.ST_PNG_IMPORT
              } else {
                RoleMediaSource.MIGRATED_LEGACY
              },
            createdAt = createdAt,
            updatedAt = updatedAt,
          )
        },
      coverImage =
        coverUri?.let { uri ->
          RoleMediaAsset(
            id = UUID.nameUUIDFromBytes("cover:$id:$uri".toByteArray()).toString(),
            kind = RoleMediaKind.COVER,
            uri = uri,
            displayName = "$name cover".trim(),
            source = RoleMediaSource.MIGRATED_LEGACY,
            createdAt = createdAt,
            updatedAt = updatedAt,
          )
        },
      importState =
        RoleMediaImportState(
          lastImportedPrimaryAvatarSource = avatarUri,
          importedFromStPng = interopState?.sourceFormat == RoleCardSourceFormat.ST_PNG,
          lastImportHadEmbeddedImage = !avatarUri.isNullOrBlank(),
        ),
    )
}

internal fun RoleCard.toPersistedRoleInteropState(): RoleInteropState {
  return interopState
    ?: RoleInteropState(
      sourceFormat = RoleCardSourceFormat.INTERNAL,
      exportTargetDefault = RoleCardExportTarget.ST_V2_JSON,
      migrationNotes = listOf("Role created before ST interop fields were introduced."),
    )
}

private fun buildLegacyStCard(
  name: String,
  description: String,
  personality: String,
  scenario: String,
  firstMessage: String,
  messageExample: String,
  systemPrompt: String,
  tags: List<String>,
): StCharacterCard {
  val data =
    StCharacterCardData(
      name = name,
      description = description,
      personality = personality,
      scenario = scenario,
      first_mes = firstMessage,
      mes_example = messageExample,
      system_prompt = systemPrompt,
      tags = tags,
    )
  return StCharacterCard(
    spec = "chara_card_v2",
    spec_version = "2.0",
    name = name,
    description = description,
    personality = personality,
    scenario = scenario,
    first_mes = firstMessage,
    mes_example = messageExample,
    tags = tags,
    data = data,
  )
}
