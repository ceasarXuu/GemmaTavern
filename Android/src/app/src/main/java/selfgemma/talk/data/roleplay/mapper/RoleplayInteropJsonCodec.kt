package selfgemma.talk.data.roleplay.mapper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import selfgemma.talk.domain.roleplay.model.RoleInteropState
import selfgemma.talk.domain.roleplay.model.RoleMediaProfile
import selfgemma.talk.domain.roleplay.model.RoleRuntimeProfile
import selfgemma.talk.domain.roleplay.model.StCharacterCard
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
    return value?.let(gson::toJson)
  }

  fun decodeStUserProfile(value: String?): StUserProfile? {
    if (value.isNullOrBlank()) {
      return null
    }
    return gson.fromJson(value, StUserProfile::class.java)
  }
}
