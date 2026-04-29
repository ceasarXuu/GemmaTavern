package selfgemma.talk.feature.roleplay.profile

import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.StringRes
import selfgemma.talk.R

internal const val MY_PROFILE_TAG = "MyProfileScreen"
internal const val PERSONA_NAME_MAX_CHARS = 120
internal const val PERSONA_DESCRIPTION_MAX_CHARS = 600
internal const val PERSONA_DEPTH_MAX_CHARS = 4

internal object PersonaCardImageCache {
  private val cache = LruCache<String, Bitmap>(24)

  fun get(key: String): Bitmap? = cache.get(key)

  fun put(key: String, bitmap: Bitmap) {
    cache.put(key, bitmap)
  }
}

internal data class PersonaTextFieldSpec(
  val maxChars: Int? = null,
)

internal enum class PersonaHelpTopic(@StringRes val titleRes: Int, @StringRes val bodyRes: Int) {
  AVATAR(R.string.my_profile_avatar_title, R.string.my_profile_help_avatar_body),
  NAME(R.string.my_profile_persona_name_title, R.string.my_profile_help_name_body),
  DESCRIPTION(R.string.my_profile_persona_description_title, R.string.my_profile_help_description_body),
  POSITION(R.string.my_profile_persona_position_title, R.string.my_profile_help_position_body),
  DEPTH(R.string.my_profile_persona_depth_title, R.string.my_profile_help_depth_body),
  ROLE(R.string.my_profile_persona_role_title, R.string.my_profile_help_role_body),
}

internal fun personaTextFieldSpec(topic: PersonaHelpTopic?): PersonaTextFieldSpec? =
  when (topic) {
    PersonaHelpTopic.NAME -> PersonaTextFieldSpec(maxChars = PERSONA_NAME_MAX_CHARS)
    PersonaHelpTopic.DESCRIPTION -> PersonaTextFieldSpec(maxChars = PERSONA_DESCRIPTION_MAX_CHARS)
    PersonaHelpTopic.DEPTH -> PersonaTextFieldSpec(maxChars = PERSONA_DEPTH_MAX_CHARS)
    else -> null
  }

internal fun takeReadPermission(context: android.content.Context, uri: android.net.Uri) {
  runCatching {
    context.contentResolver.takePersistableUriPermission(
      uri,
      android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
  }
}
