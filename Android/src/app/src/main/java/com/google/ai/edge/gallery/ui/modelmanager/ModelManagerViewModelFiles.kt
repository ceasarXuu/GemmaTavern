/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package selfgemma.talk.ui.modelmanager

import android.util.Log
import java.io.File
import selfgemma.talk.data.IMPORTS_DIR

private const val TAG = "AGModelManagerVMFiles"

internal fun ModelManagerViewModel.isFileInExternalFilesDir(fileName: String): Boolean {
  val dir = externalFilesDir ?: return false
  return File(dir, fileName).exists()
}

internal fun ModelManagerViewModel.isFileInDataLocalTmpDir(fileName: String): Boolean {
  return File("/data/local/tmp", fileName).exists()
}

internal fun ModelManagerViewModel.deleteFileFromExternalFilesDir(fileName: String) {
  if (isFileInExternalFilesDir(fileName)) {
    File(externalFilesDir, fileName).delete()
  }
}

/**
 * Deletes files from the the model imports directory whose absolute paths start with a given
 * prefix.
 */
internal fun ModelManagerViewModel.deleteFilesFromImportDir(fileName: String) {
  val dir = context.getExternalFilesDir(null) ?: return
  val prefixAbsolutePath = "${context.getExternalFilesDir(null)}${File.separator}$fileName"
  val filesToDelete =
    File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
      File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
    } ?: arrayOf()
  for (file in filesToDelete) {
    Log.d(TAG, "Deleting file: ${file.name}")
    file.delete()
  }
}

internal fun ModelManagerViewModel.deleteDirFromExternalFilesDir(dir: String) {
  if (isFileInExternalFilesDir(dir)) {
    File(externalFilesDir, dir).deleteRecursively()
  }
}

internal fun ModelManagerViewModel.isModelPartiallyDownloaded(
  model: selfgemma.talk.data.Model
): Boolean {
  if (model.localModelFilePathOverride.isNotEmpty()) return false
  val tmpFilePath =
    model.getPath(
      context = context,
      fileName = "${model.downloadFileName}.${selfgemma.talk.data.TMP_FILE_EXT}",
    )
  return File(tmpFilePath).exists()
}

internal fun ModelManagerViewModel.isModelDownloaded(model: selfgemma.talk.data.Model): Boolean {
  val modelRelativePath =
    listOf(model.normalizedName, model.version, model.downloadFileName)
      .joinToString(File.separator)
  val downloadedFileExists =
    model.downloadFileName.isNotEmpty() &&
      ((model.localModelFilePathOverride.isEmpty() &&
        isFileInExternalFilesDir(modelRelativePath)) ||
        (model.localModelFilePathOverride.isNotEmpty() &&
          File(model.localModelFilePathOverride).exists()))

  val unzippedDirectoryExists =
    model.isZip &&
      model.unzipDir.isNotEmpty() &&
      isFileInExternalFilesDir(
        listOf(model.normalizedName, model.version, model.unzipDir).joinToString(File.separator)
      )

  return downloadedFileExists || unzippedDirectoryExists
}
