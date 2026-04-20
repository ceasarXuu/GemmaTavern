package selfgemma.talk.domain.roleplay.model

enum class RoleMediaKind {
  PRIMARY_AVATAR,
  COVER,
  GALLERY,
  SPRITE,
}

enum class RoleMediaSource {
  ST_PNG_IMPORT,
  LOCAL_PICKER,
  APP_GENERATED,
  MIGRATED_LEGACY,
  PLACEHOLDER,
}

enum class RoleMediaUsage {
  UNSPECIFIED,
  PORTRAIT,
  EXPRESSION,
  SCENE,
  REFERENCE,
}

data class RoleMediaAsset(
  val id: String,
  val kind: RoleMediaKind,
  val uri: String,
  val previewUri: String? = null,
  val displayName: String? = null,
  val mimeType: String? = null,
  val source: RoleMediaSource = RoleMediaSource.LOCAL_PICKER,
  val usage: RoleMediaUsage = RoleMediaUsage.UNSPECIFIED,
  val width: Int? = null,
  val height: Int? = null,
  val fileSizeBytes: Long? = null,
  val createdAt: Long,
  val updatedAt: Long,
  val extraJson: String = "{}",
)

data class RoleSpriteAsset(
  val id: String,
  val uri: String,
  val displayName: String? = null,
  val stateTag: String = "neutral",
  val source: RoleMediaSource = RoleMediaSource.LOCAL_PICKER,
  val createdAt: Long,
  val updatedAt: Long,
  val extraJson: String = "{}",
)

data class RoleMediaExportPolicy(
  val exportPrimaryAvatarToStPng: Boolean = true,
  val includeCoverInSelfExtension: Boolean = false,
  val includeGalleryInSelfExtension: Boolean = false,
  val includeSpritesInSelfExtension: Boolean = false,
)

data class RoleMediaImportState(
  val lastImportedPrimaryAvatarSource: String? = null,
  val importedFromStPng: Boolean = false,
  val lastImportHadEmbeddedImage: Boolean = false,
  val importNotes: List<String> = emptyList(),
)

data class RoleMediaProfile(
  val primaryAvatar: RoleMediaAsset? = null,
  val coverImage: RoleMediaAsset? = null,
  val galleryAssets: List<RoleMediaAsset> = emptyList(),
  val spriteAssets: List<RoleSpriteAsset> = emptyList(),
  val exportPolicy: RoleMediaExportPolicy = RoleMediaExportPolicy(),
  val importState: RoleMediaImportState = RoleMediaImportState(),
)

fun RoleCard.primaryAvatarUri(): String? {
  return mediaProfile?.primaryAvatar?.uri ?: avatarUri
}

fun RoleCard.coverImageUri(): String? {
  return mediaProfile?.coverImage?.uri ?: coverUri
}
