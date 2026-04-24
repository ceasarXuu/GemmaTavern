package selfgemma.talk.data.roleplay.mapper

import org.junit.Assert.assertEquals
import org.junit.Test
import selfgemma.talk.data.roleplay.db.entity.RoleEntity
import selfgemma.talk.data.roleplay.db.entity.SessionEntity
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.Session
import selfgemma.talk.domain.roleplay.model.StCharacterCard
import selfgemma.talk.domain.roleplay.model.StCharacterCardData
import selfgemma.talk.domain.roleplay.model.StPersonaConnection
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile

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

  @Test
  fun sessionUserProfileJson_roundTripsWithStableFieldNames() {
    val session =
      Session(
        id = "session-1",
        roleId = "role-1",
        title = "Session",
        activeModelId = "gemma",
        createdAt = 1L,
        updatedAt = 1L,
        lastMessageAt = 1L,
        sessionUserProfile =
          StUserProfile(
            userAvatarId = "slot-a",
            defaultPersonaId = "slot-a",
            personas = mapOf("slot-a" to "Alice"),
            personaDescriptions =
              mapOf(
                "slot-a" to
                  StPersonaDescriptor(
                    description = "persona body",
                    title = "Captain",
                    position = StPersonaDescriptionPosition.AT_DEPTH,
                    depth = 4,
                    role = 1,
                    lorebook = "notes",
                    connections = listOf(StPersonaConnection(type = "character", id = "role-1")),
                    avatarUri = "content://avatar",
                    avatarEditorSourceUri = "content://source",
                    avatarCropZoom = 1.5f,
                    avatarCropOffsetX = 0.25f,
                    avatarCropOffsetY = -0.5f,
                  ),
              ),
          ),
      )

    val json = session.toEntity().sessionUserProfileJson.orEmpty()
    val decoded = session.toEntity().toDomain().sessionUserProfile!!

    assertEquals(true, json.contains("\"personaDescriptions\""))
    assertEquals("Alice", decoded.userName)
    assertEquals("persona body", decoded.personaDescription)
    assertEquals(StPersonaDescriptionPosition.AT_DEPTH, decoded.personaDescriptionPosition)
    assertEquals("content://avatar", decoded.activeAvatarUri)
    assertEquals(1.5f, decoded.activeAvatarCropZoom, 0.001f)
  }

  @Test
  fun sessionUserProfileJson_decodesReleaseObfuscatedFields() {
    val obfuscatedJson =
      """
      {
        "a":"slot-a",
        "b":"slot-a",
        "c":{"slot-a":"Alice"},
        "d":{
          "slot-a":{
            "a":"persona body",
            "b":"Captain",
            "c":4,
            "d":4,
            "e":1,
            "f":"notes",
            "g":[{"a":"character","b":"role-1"}],
            "h":"content://avatar",
            "i":"content://source",
            "j":1.5,
            "k":0.25,
            "l":-0.5
          }
        }
      }
      """.trimIndent()
    val entity =
      SessionEntity(
        id = "session-1",
        roleId = "role-1",
        title = "Session",
        activeModelId = "gemma",
        createdAt = 1L,
        updatedAt = 1L,
        lastMessageAt = 1L,
        sessionUserProfileJson = obfuscatedJson,
      )

    val decoded = entity.toDomain().sessionUserProfile!!

    assertEquals("slot-a", decoded.userAvatarId)
    assertEquals("Alice", decoded.userName)
    assertEquals("Captain", decoded.personaTitle)
    assertEquals("persona body", decoded.personaDescription)
    assertEquals(StPersonaDescriptionPosition.AT_DEPTH, decoded.personaDescriptionPosition)
    assertEquals("content://avatar", decoded.activeAvatarUri)
    assertEquals("content://source", decoded.activeAvatarEditorSourceUri)
    assertEquals(1.5f, decoded.activeAvatarCropZoom, 0.001f)
    assertEquals(
      listOf(StPersonaConnection(type = "character", id = "role-1")),
      decoded.activePersonaDescriptor().connections,
    )
  }
}
