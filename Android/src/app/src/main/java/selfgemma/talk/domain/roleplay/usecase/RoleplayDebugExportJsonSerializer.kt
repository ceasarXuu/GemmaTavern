package selfgemma.talk.domain.roleplay.usecase

import com.google.gson.GsonBuilder
import javax.inject.Inject

class RoleplayDebugExportJsonSerializer
@Inject
constructor() {
  private val gson =
    GsonBuilder()
      .disableHtmlEscaping()
      .setPrettyPrinting()
      .create()

  fun toJson(value: Any): String = gson.toJson(value)

  fun toJsonBytes(value: Any): ByteArray = toJson(value).toByteArray(Charsets.UTF_8)
}
