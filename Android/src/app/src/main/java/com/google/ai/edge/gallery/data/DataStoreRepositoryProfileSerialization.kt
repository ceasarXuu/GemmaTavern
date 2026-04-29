/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.data

import selfgemma.talk.proto.StPersonaConnectionSettings
import selfgemma.talk.proto.StPersonaDescriptorSettings
import selfgemma.talk.proto.StUserProfileSettings
import selfgemma.talk.domain.roleplay.model.StPersonaConnection
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptionPosition
import selfgemma.talk.domain.roleplay.model.StPersonaDescriptor
import selfgemma.talk.domain.roleplay.model.StUserProfile

internal fun StUserProfile.toProto(): StUserProfileSettings {
  val profile = this
  val personaDescriptionSettings = mutableMapOf<String, StPersonaDescriptorSettings>()
  profile.personaDescriptions.forEach { (key, descriptorValue) ->
    val descriptor: StPersonaDescriptor = descriptorValue
    personaDescriptionSettings[key] = descriptor.toProto()
  }
  return StUserProfileSettings.newBuilder()
    .setUserAvatarId(profile.resolvedUserAvatarId())
    .apply {
      profile.defaultPersonaId?.takeIf { it.isNotBlank() }?.let(::setDefaultPersonaId)
      putAllPersonas(profile.personas)
      putAllPersonaDescriptions(personaDescriptionSettings)
    }
    .build()
}

internal fun StPersonaDescriptor.toProto(): StPersonaDescriptorSettings {
  val descriptor = this
  return StPersonaDescriptorSettings.newBuilder()
    .setDescription(descriptor.description)
    .setTitle(descriptor.title)
    .setPosition(descriptor.position.rawValue)
    .setDepth(descriptor.depth)
    .setRole(descriptor.role)
    .setLorebook(descriptor.lorebook)
    .apply {
      addAllConnections(descriptor.connections.map { connection ->
        StPersonaConnectionSettings.newBuilder()
          .setType(connection.type)
          .setId(connection.id)
          .build()
      })
      descriptor.avatarUri?.takeIf { it.isNotBlank() }?.let(::setAvatarUri)
      descriptor.avatarEditorSourceUri?.takeIf { it.isNotBlank() }?.let(::setAvatarEditorSourceUri)
      setAvatarCropZoom(descriptor.avatarCropZoom)
      setAvatarCropOffsetX(descriptor.avatarCropOffsetX)
      setAvatarCropOffsetY(descriptor.avatarCropOffsetY)
    }
    .build()
}

internal fun StUserProfileSettings.toDomain(): StUserProfile {
  return StUserProfile(
    userAvatarId = userAvatarId,
    defaultPersonaId = defaultPersonaId.takeIf { it.isNotBlank() },
    personas = personasMap.toMap(),
    personaDescriptions =
      personaDescriptionsMap.mapValues { (_, descriptor) ->
        StPersonaDescriptor(
          description = descriptor.description,
          title = descriptor.title,
          position = StPersonaDescriptionPosition.fromRawValue(descriptor.position),
          depth = descriptor.depth,
          role = descriptor.role,
          lorebook = descriptor.lorebook,
          connections =
            descriptor.connectionsList.map { connection ->
              StPersonaConnection(type = connection.type, id = connection.id)
            },
          avatarUri = descriptor.avatarUri.takeIf { it.isNotBlank() },
          avatarEditorSourceUri = descriptor.avatarEditorSourceUri.takeIf { it.isNotBlank() },
          avatarCropZoom = descriptor.avatarCropZoom.takeIf { it > 0f } ?: 1f,
          avatarCropOffsetX = descriptor.avatarCropOffsetX,
          avatarCropOffsetY = descriptor.avatarCropOffsetY,
        )
      },
  ).ensureDefaults()
}
