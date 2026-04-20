package selfgemma.talk.domain.roleplay.usecase

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.inject.Inject
import selfgemma.talk.data.roleplay.interop.stcardpng.StPngRoleCardCodec
import selfgemma.talk.domain.roleplay.model.RoleCard
import selfgemma.talk.domain.roleplay.model.primaryAvatarUri
import selfgemma.talk.domain.roleplay.repository.RoleplayInteropDocumentRepository

class ExportStRoleCardToUriUseCase
@Inject
constructor(
  private val documentRepository: RoleplayInteropDocumentRepository,
  private val exportStV2RoleCardUseCase: ExportStV2RoleCardUseCase,
) {
  suspend fun exportToUri(uri: String, role: RoleCard) {
    val metadata = documentRepository.getMetadata(uri)
    val isPng = metadata.mimeType == "image/png" || metadata.displayName?.endsWith(".png", ignoreCase = true) == true
    val rawJson = exportStV2RoleCardUseCase.exportToJson(role)
    if (!isPng) {
      documentRepository.writeText(uri, rawJson)
      return
    }

    val basePngBytes =
      role.primaryAvatarUri()?.let { avatarUri ->
        runCatching { documentRepository.readBytes(avatarUri) }.getOrNull()
      } ?: DEFAULT_PLACEHOLDER_PNG

    documentRepository.writeBytes(uri, StPngRoleCardCodec.embedCardJson(basePngBytes = basePngBytes, json = rawJson))
  }

  private companion object {
    val DEFAULT_PLACEHOLDER_PNG: ByteArray by lazy {
      runCatching {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.parseColor("#D7C7A7"))
        ByteArrayOutputStream().use { output ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
          bitmap.recycle()
          output.toByteArray()
        }
      }
        .getOrElse {
          Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR42mOor6/HAAOEAaY+KhYFAAAAAElFTkSuQmCC"
          )
        }
    }
  }
}
