package selfgemma.talk.data.roleplay.mapper

import org.junit.Assert.assertEquals
import org.junit.Test
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData

class RoleplayMappersTest {
  @Test
  fun toDomain_prefersPrimaryAvatarFromMediaProfileWhenLegacyAvatarColumnIsBlank() {
    val mediaProfile =
      RoleMediaProfile(
        primaryAvatar =
          RoleMediaAsset(
            id = "avatar-1",
            kind = RoleMediaKind.PRIMARY_AVATAR,
            uri = "content://cards/imported-avatar.png",
            source = RoleMediaSource.ST_PNG_IMPORT,
            createdAt = 1L,
            updatedAt = 1L,
          )
      )

    val entity =
      RoleEntity(
        id = "role-1",
        name = "Iris",
        avatarUri = null,
        coverUri = null,
        summary = "Archivist",
        systemPrompt = "Stay in character.",
        mediaProfileJson = RoleplayInteropJsonCodec.encodeRoleMediaProfile(mediaProfile),
        createdAt = 1L,
        updatedAt = 1L,
      )

    val role = entity.toDomain()

    assertEquals("content://cards/imported-avatar.png", role.avatarUri)
    assertEquals("content://cards/imported-avatar.png", role.mediaProfile?.primaryAvatar?.uri)
  }

  @Test
  fun toDomain_prefersCanonicalStCardProjectionOverLegacyColumns() {
    val core =
      StCharacterCard(
        spec = "chara_card_v2",
        spec_version = "2.0",
        name = "Canon Name",
        description = "Core description",
        personality = "Core persona",
        scenario = "Core world",
        first_mes = "Core opener",
        mes_example = "Ex 1\n\nEx 2",
        data =
          StCharacterCardData(
            name = "Canon Name",
            description = "Core description",
            personality = "Core persona",
            scenario = "Core world",
            first_mes = "Core opener",
            mes_example = "Ex 1\n\nEx 2",
            system_prompt = "Core prompt",
            tags = listOf("core", "st"),
          ),
      )
    val entity =
      RoleEntity(
        id = "role-2",
        name = "Legacy Name",
        summary = "Legacy summary",
        systemPrompt = "Legacy prompt",
        personaDescription = "Legacy persona",
        worldSettings = "Legacy world",
        openingLine = "Legacy opener",
        exampleDialogues = listOf("Legacy example"),
        tags = listOf("legacy"),
        cardCoreJson = RoleplayInteropJsonCodec.encodeRoleCardCore(core),
        createdAt = 1L,
        updatedAt = 1L,
      )

    val role = entity.toDomain()

    assertEquals("Canon Name", role.name)
    assertEquals("Core description", role.summary)
    assertEquals("Core prompt", role.systemPrompt)
    assertEquals("Core persona", role.personaDescription)
    assertEquals("Core world", role.worldSettings)
    assertEquals("Core opener", role.openingLine)
    assertEquals(listOf("Ex 1", "Ex 2"), role.exampleDialogues)
    assertEquals(listOf("core", "st"), role.tags)
  }

  @Test
  fun toEntity_writesProjectionColumnsFromCanonicalStCard() {
    val role =
      RoleCard(
        id = "role-3",
        name = "Legacy Name",
        summary = "Legacy summary",
        systemPrompt = "Legacy prompt",
        personaDescription = "Legacy persona",
        worldSettings = "Legacy world",
        openingLine = "Legacy opener",
        exampleDialogues = listOf("Legacy example"),
        tags = listOf("legacy"),
        cardCore =
          StCharacterCard(
            spec = "chara_card_v2",
            spec_version = "2.0",
            name = "Canon Name",
            description = "Core description",
            personality = "Core persona",
            scenario = "Core world",
            first_mes = "Core opener",
            mes_example = "Ex 1\n\nEx 2",
            data =
              StCharacterCardData(
                name = "Canon Name",
                description = "Core description",
                personality = "Core persona",
                scenario = "Core world",
                first_mes = "Core opener",
                mes_example = "Ex 1\n\nEx 2",
                system_prompt = "Core prompt",
                tags = listOf("core", "st"),
              ),
          ),
        createdAt = 1L,
        updatedAt = 1L,
      )

    val entity = role.toEntity()

    assertEquals("Canon Name", entity.name)
    assertEquals("Core description", entity.summary)
    assertEquals("Core prompt", entity.systemPrompt)
    assertEquals("Core persona", entity.personaDescription)
    assertEquals("Core world", entity.worldSettings)
    assertEquals("Core opener", entity.openingLine)
    assertEquals(listOf("Ex 1", "Ex 2"), entity.exampleDialogues)
    assertEquals(listOf("core", "st"), entity.tags)
  }
}
