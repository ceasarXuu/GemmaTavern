package selfgemma.talk.feature.roleplay.roles

import java.util.UUID
import selfgemma.talk.R
import selfgemma.talk.domain.roleplay.model.RoleMediaAsset
import selfgemma.talk.domain.roleplay.model.RoleMediaKind
import selfgemma.talk.domain.roleplay.model.RoleMediaSource
import selfgemma.talk.domain.roleplay.model.RoleMediaUsage
import selfgemma.talk.domain.roleplay.model.RoleSpriteAsset

internal fun RoleEditorViewModel.updateAvatarUriAction(value: String?) {
  val now = System.currentTimeMillis()
  mutateEditorState {
    it.copy(
      avatarUri = value,
      avatarSource = if (value.isNullOrBlank()) null else RoleMediaSource.LOCAL_PICKER,
      errorMessage = null,
      statusMessage =
        if (value.isNullOrBlank()) {
          appContext.getString(R.string.role_editor_status_avatar_cleared)
        } else {
          appContext.getString(R.string.role_editor_status_avatar_updated)
        },
      importedFromStPng = false,
    )
  }
  syncPrimaryAvatarAsset(uri = value, source = RoleMediaSource.LOCAL_PICKER, now = now)
}

internal fun RoleEditorViewModel.updateCoverUriAction(value: String?) {
  val now = System.currentTimeMillis()
  mutateEditorState {
    it.copy(
      coverUri = value,
      coverSource = if (value.isNullOrBlank()) null else RoleMediaSource.LOCAL_PICKER,
      errorMessage = null,
      statusMessage =
        if (value.isNullOrBlank()) {
          appContext.getString(R.string.role_editor_status_cover_cleared)
        } else {
          appContext.getString(R.string.role_editor_status_cover_updated)
        },
    )
  }
  syncCoverImageAsset(uri = value, now = now)
}

internal fun RoleEditorViewModel.addGalleryAssetsAction(uris: List<String>) {
  if (uris.isEmpty()) {
    return
  }
  val now = System.currentTimeMillis()
  val newAssets =
    uris.distinct().map { uri ->
      RoleMediaAsset(
        id = UUID.randomUUID().toString(),
        kind = RoleMediaKind.GALLERY,
        uri = uri,
        displayName = uri.substringAfterLast('/').substringBefore('?').ifBlank { null },
        source = RoleMediaSource.LOCAL_PICKER,
        createdAt = now,
        updatedAt = now,
      )
    }
  mutateEditorState {
    it.copy(
      galleryAssets = it.galleryAssets + newAssets,
      statusMessage = appContext.getString(R.string.role_editor_status_gallery_added, newAssets.size),
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.removeGalleryAssetAction(assetId: String) {
  mutateEditorState {
    val removedAsset = it.galleryAssets.firstOrNull { asset -> asset.id == assetId }
    it.copy(
      galleryAssets = it.galleryAssets.filterNot { asset -> asset.id == assetId },
      avatarUri = if (removedAsset?.uri == it.avatarUri) null else it.avatarUri,
      avatarSource = if (removedAsset?.uri == it.avatarUri) null else it.avatarSource,
      coverUri = if (removedAsset?.uri == it.coverUri) null else it.coverUri,
      coverSource = if (removedAsset?.uri == it.coverUri) null else it.coverSource,
      importedFromStPng = if (removedAsset?.uri == it.avatarUri) false else it.importedFromStPng,
      statusMessage = appContext.getString(R.string.role_editor_status_gallery_removed),
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.updateGalleryAssetEntry(assetId: String, transformer: (RoleMediaAsset) -> RoleMediaAsset) {
  mutateEditorState {
    it.copy(
      galleryAssets =
        it.galleryAssets.map { asset ->
          if (asset.id == assetId) {
            transformer(asset)
          } else {
            asset
          }
        },
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.updateGalleryAssetNameAction(assetId: String, value: String) {
  updateGalleryAssetEntry(assetId) { asset ->
    asset.copy(displayName = value.ifBlank { null }, updatedAt = System.currentTimeMillis())
  }
}

internal fun RoleEditorViewModel.updateGalleryAssetUsageAction(assetId: String, usage: RoleMediaUsage) {
  updateGalleryAssetEntry(assetId) { asset ->
    asset.copy(usage = usage, updatedAt = System.currentTimeMillis())
  }
}

internal fun RoleEditorViewModel.setGalleryAssetAsAvatarAction(assetId: String) {
  val asset = uiState.value.galleryAssets.firstOrNull { it.id == assetId } ?: return
  val now = System.currentTimeMillis()
  mutateEditorState {
    it.copy(
      avatarUri = asset.uri,
      avatarSource = asset.source,
      statusMessage = appContext.getString(R.string.role_editor_status_gallery_avatar),
      errorMessage = null,
      importedFromStPng = asset.source == RoleMediaSource.ST_PNG_IMPORT,
    )
  }
  syncPrimaryAvatarAsset(uri = asset.uri, source = asset.source, now = now)
}

internal fun RoleEditorViewModel.setGalleryAssetAsCoverAction(assetId: String) {
  val asset = uiState.value.galleryAssets.firstOrNull { it.id == assetId } ?: return
  val now = System.currentTimeMillis()
  mutateEditorState {
    it.copy(
      coverUri = asset.uri,
      coverSource = asset.source,
      statusMessage = appContext.getString(R.string.role_editor_status_gallery_cover),
      errorMessage = null,
    )
  }
  syncCoverImageAsset(uri = asset.uri, now = now, source = asset.source)
}

internal fun RoleEditorViewModel.addSpriteAssetsAction(uris: List<String>) {
  if (uris.isEmpty()) {
    return
  }
  val now = System.currentTimeMillis()
  val newAssets =
    uris.distinct().map { uri ->
      val displayName = uri.substringAfterLast('/').substringBefore('?').ifBlank { null }
      RoleSpriteAsset(
        id = UUID.randomUUID().toString(),
        uri = uri,
        displayName = displayName,
        stateTag = displayName?.substringBeforeLast('.')?.ifBlank { "neutral" } ?: "neutral",
        source = RoleMediaSource.LOCAL_PICKER,
        createdAt = now,
        updatedAt = now,
      )
    }
  mutateEditorState {
    it.copy(
      spriteAssets = it.spriteAssets + newAssets,
      statusMessage = appContext.getString(R.string.role_editor_status_sprite_added, newAssets.size),
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.removeSpriteAssetAction(assetId: String) {
  mutateEditorState {
    it.copy(
      spriteAssets = it.spriteAssets.filterNot { asset -> asset.id == assetId },
      statusMessage = appContext.getString(R.string.role_editor_status_sprite_removed),
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.updateSpriteAssetNameAction(assetId: String, value: String) {
  mutateEditorState {
    it.copy(
      spriteAssets =
        it.spriteAssets.map { asset ->
          if (asset.id == assetId) {
            asset.copy(displayName = value.ifBlank { null }, updatedAt = System.currentTimeMillis())
          } else {
            asset
          }
        },
      errorMessage = null,
    )
  }
}

internal fun RoleEditorViewModel.updateSpriteStateTagAction(assetId: String, value: String) {
  mutateEditorState {
    it.copy(
      spriteAssets =
        it.spriteAssets.map { asset ->
          if (asset.id == assetId) {
            asset.copy(stateTag = value.ifBlank { "neutral" }, updatedAt = System.currentTimeMillis())
          } else {
            asset
          }
        },
      errorMessage = null,
    )
  }
}
