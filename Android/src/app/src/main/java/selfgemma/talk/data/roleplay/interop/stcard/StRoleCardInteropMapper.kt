package selfgemma.talk.data.roleplay.interop.stcard

import com.google.gson.JsonObject
import java.util.UUID
import selfgemma.talk.data.roleplay.mapper.toPersistedRoleInteropState
import selfgemma.talk.data.roleplay.mapper.toPersistedRoleMediaProfile
import selfgemma.talk.data.roleplay.mapper.toPersistedRoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.RoleCardExportTarget
import selfgemma.talk.domain.roleplay.model.RoleCardSourceFormat
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.resolvedDescription
import selfgemma.talk.domain.roleplay.model.resolvedFirstMessage
import selfgemma.talk.domain.roleplay.model.resolvedMessageExample
import selfgemma.talk.domain.roleplay.model.resolvedName
import selfgemma.talk.domain.roleplay.model.resolvedPersonality
import selfgemma.talk.domain.roleplay.model.resolvedScenario
import selfgemma.talk.domain.roleplay.model.resolvedExampleDialogues
import selfgemma.talk.domain.roleplay.model.resolvedOpeningLine
import selfgemma.talk.domain.roleplay.model.resolvedPersonaDescription
import selfgemma.talk.domain.roleplay.model.resolvedSummary
import selfgemma.talk.domain.roleplay.model.resolvedSystemPrompt
import selfgemma.talk.domain.roleplay.model.resolvedTags
import selfgemma.talk.domain.roleplay.model.resolvedWorldSettings

internal object StRoleCardInteropMapper {
  fun importedV2ToRoleCard(
    parsed: ParsedStCardV2,
    now: Long,
    existingRole: RoleCard? = null,
    roleId: String = existingRole?.id ?: UUID.randomUUID().toString(),
  ): RoleCard {
    val card = parsed.card
    val data = card.data ?: StCharacterCardData()
    val runtimeProfile = existingRole?.toPersistedRoleRuntimeProfile()
    val interopState = mergeInteropState(parsed.interopState, existingRole)

    return RoleCard(
      id = roleId,
      stCard = card,
      avatarUri = existingRole?.avatarUri,
      coverUri = existingRole?.coverUri,
      safetyPolicy = runtimeProfile?.safetyPolicy?.policyText.orEmpty(),
      defaultModelId = runtimeProfile?.modelParams?.preferredModelId,
      defaultTemperature = runtimeProfile?.modelParams?.temperature,
      defaultTopP = runtimeProfile?.modelParams?.topP,
      defaultTopK = runtimeProfile?.modelParams?.topK,
      enableThinking = runtimeProfile?.modelParams?.enableThinking ?: false,
      summaryTurnThreshold = runtimeProfile?.memoryPolicy?.summaryTurnThreshold ?: 6,
      memoryEnabled = runtimeProfile?.memoryPolicy?.enabled ?: true,
      memoryMaxItems = runtimeProfile?.memoryPolicy?.maxItems ?: 32,
      runtimeProfile = runtimeProfile,
      mediaProfile = existingRole?.toPersistedRoleMediaProfile(),
      interopState = interopState,
      builtIn = existingRole?.builtIn ?: false,
      archived = existingRole?.archived ?: false,
      createdAt = existingRole?.createdAt ?: now,
      updatedAt = now,
    )
  }

  fun roleCardToExportCore(role: RoleCard): StCharacterCard {
    val persistedCore = role.stCard
    val existingData = persistedCore.data ?: StCharacterCardData()
    val roleTags = role.resolvedTags().ifEmpty { existingData.tags ?: persistedCore.tags.orEmpty() }
    val roleExtensions =
      existingData.extensions?.deepCopy() ?: JsonObject().apply {
        if (persistedCore.talkativeness != null) {
          addProperty("talkativeness", persistedCore.talkativeness)
        }
        if (persistedCore.fav != null) {
          addProperty("fav", persistedCore.fav)
        }
      }

    return persistedCore.copy(
      spec = persistedCore.spec ?: StV2CardParser.ST_V2_SPEC,
      spec_version = persistedCore.spec_version ?: StV2CardParser.ST_V2_SPEC_VERSION,
      name = role.resolvedName().ifBlank { persistedCore.name.orEmpty() },
      description = role.resolvedSummary().ifBlank { persistedCore.description.orEmpty() },
      personality = role.resolvedPersonaDescription().ifBlank { persistedCore.personality.orEmpty() },
      scenario = role.resolvedWorldSettings().ifBlank { persistedCore.scenario.orEmpty() },
      first_mes = role.resolvedOpeningLine().ifBlank { persistedCore.first_mes.orEmpty() },
      mes_example = role.resolvedExampleDialogues().toMessageExample().ifBlank { persistedCore.mes_example.orEmpty() },
      tags = roleTags,
      data =
        existingData.copy(
          name = role.resolvedName().ifBlank { existingData.name.orEmpty().ifBlank { persistedCore.name.orEmpty() } },
          description = role.resolvedSummary().ifBlank { existingData.description.orEmpty().ifBlank { persistedCore.description.orEmpty() } },
          personality = role.resolvedPersonaDescription().ifBlank { existingData.personality.orEmpty().ifBlank { persistedCore.personality.orEmpty() } },
          scenario = role.resolvedWorldSettings().ifBlank { existingData.scenario.orEmpty().ifBlank { persistedCore.scenario.orEmpty() } },
          first_mes = role.resolvedOpeningLine().ifBlank { existingData.first_mes.orEmpty().ifBlank { persistedCore.first_mes.orEmpty() } },
          mes_example =
            role.resolvedExampleDialogues()
              .toMessageExample()
              .ifBlank { existingData.mes_example.orEmpty().ifBlank { persistedCore.mes_example.orEmpty() } },
          system_prompt = role.resolvedSystemPrompt().ifBlank { existingData.system_prompt.orEmpty() },
          tags = roleTags,
          extensions = roleExtensions,
        ),
    )
  }

  private fun mergeInteropState(
    parsedState: RoleInteropState,
    existingRole: RoleCard?,
  ): RoleInteropState {
    val existingState = existingRole?.toPersistedRoleInteropState()
    return parsedState.copy(
      importedAt = parsedState.importedAt,
      exportTargetDefault = existingState?.exportTargetDefault ?: RoleCardExportTarget.ST_V2_JSON,
      migrationNotes =
        buildList {
          existingState?.migrationNotes?.let(::addAll)
          add("Imported from ST card and stored as canonical ST schema.")
        },
      compatibilityWarnings =
        buildList {
          existingState?.compatibilityWarnings?.let(::addAll)
          if (parsedState.sourceFormat != RoleCardSourceFormat.ST_JSON) {
            add("Expected ST_JSON source during ST import normalization.")
          }
        },
    )
  }

  private fun buildFallbackCard(role: RoleCard): StCharacterCard {
    val tags = role.resolvedTags()
    val data =
      StCharacterCardData(
        name = role.resolvedName(),
        description = role.resolvedSummary(),
        personality = role.resolvedPersonaDescription(),
        scenario = role.resolvedWorldSettings(),
        first_mes = role.resolvedOpeningLine(),
        mes_example = role.resolvedExampleDialogues().toMessageExample(),
        system_prompt = role.resolvedSystemPrompt(),
        tags = tags,
      )
    return StCharacterCard(
      spec = StV2CardParser.ST_V2_SPEC,
      spec_version = StV2CardParser.ST_V2_SPEC_VERSION,
      name = role.resolvedName(),
      description = role.resolvedSummary(),
      personality = role.resolvedPersonaDescription(),
      scenario = role.resolvedWorldSettings(),
      first_mes = role.resolvedOpeningLine(),
      mes_example = role.resolvedExampleDialogues().toMessageExample(),
      tags = tags,
      data = data,
    )
  }

  private fun List<String>.toMessageExample(): String {
    return map(String::trim).filter(String::isNotBlank).joinToString("\n\n")
  }
}
