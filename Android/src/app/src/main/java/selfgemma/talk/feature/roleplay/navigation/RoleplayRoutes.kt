package selfgemma.talk.feature.roleplay.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RoleplayRoutes {
  const val SESSIONS = "roleplay_sessions"
  const val ROLE_CATALOG = "roleplay_roles"
  const val ROLE_EDITOR = "roleplay_role_editor?roleId={roleId}"
  const val PROFILE = "roleplay_profile?slotId={slotId}&edit={edit}"
  const val SETTINGS = "roleplay_settings"
  const val TOOL_MANAGEMENT = "roleplay_tool_management"
  const val CHAT = "roleplay_chat/{sessionId}"

  fun chat(sessionId: String): String {
    return "roleplay_chat/$sessionId"
  }

  fun roleEditor(roleId: String? = null): String {
    return if (roleId.isNullOrBlank()) {
      "roleplay_role_editor"
    } else {
      "roleplay_role_editor?roleId=${encodeQueryValue(roleId)}"
    }
  }

  fun profile(slotId: String? = null, edit: Boolean = false): String {
    val encodedSlotId = slotId?.takeIf { it.isNotBlank() }?.let(::encodeQueryValue)
    return if (encodedSlotId == null) {
      "roleplay_profile?edit=$edit"
    } else {
      "roleplay_profile?slotId=$encodedSlotId&edit=$edit"
    }
  }

  private fun encodeQueryValue(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
  }
}
