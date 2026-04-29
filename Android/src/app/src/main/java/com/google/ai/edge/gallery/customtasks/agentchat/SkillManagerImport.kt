package selfgemma.talk.customtasks.agentchat

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGSkillManagerImport"

internal fun SkillManagerViewModel.validateAndAddSkillFromUrl(
  url: String,
  onSuccess: () -> Unit,
  onValidationError: (error: String) -> Unit,
) {
  setValidating(true)
  setValidationError(null)

  viewModelScope.launch(Dispatchers.IO) {
    try {
      Log.d(TAG, "Validating skill from URL: $url")

      var normalizedUrl = url
      if (normalizedUrl.endsWith("/SKILL.md")) {
        normalizedUrl = normalizedUrl.dropLast("/SKILL.md".length)
      }
      if (normalizedUrl.endsWith("/")) {
        normalizedUrl = normalizedUrl.dropLast(1)
      }
      val skillMdUrl = "$normalizedUrl/SKILL.md"
      Log.d(TAG, "Fetching SKILL.md from: $skillMdUrl")

      val mdContent =
        try {
          val connection = URL(skillMdUrl).openConnection()
          InputStreamReader(connection.getInputStream()).use { reader -> reader.readText() }
        } catch (e: Exception) {
          Log.e(TAG, "Error fetching SKILL.md from $skillMdUrl", e)
          val error = "Failed to fetch SKILL.md: ${e.message}"
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

      if (mdContent.isEmpty()) {
        val error = "SKILL.md is empty at $skillMdUrl"
        setValidationError(error)
        onValidationError(error)
        return@launch
      }

      val (skillProto, errors) =
        convertSkillMdToProto(
          mdContent,
          builtIn = false,
          selected = true,
          skillUrl = normalizedUrl,
        )

      if (errors.isNotEmpty()) {
        val error = "Error parsing SKILL.md from $skillMdUrl: ${errors.joinToString(", ")}"
        setValidationError(error)
        onValidationError(error)
        return@launch
      }

      skillProto?.let { skill ->
        if (uiState.value.skills.any { curSkill -> curSkill.skill.name == skill.name }) {
          val error = "A skill with the name '${skill.name}' already exists."
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        addSkill(skill = skill, addToDataStore = true)
        Log.d(TAG, "Successfully added skill from URL: ${skill.name}")
        onSuccess()
      }
    } finally {
      setValidating(false)
    }
  }
}

internal fun SkillManagerViewModel.checkLocalSkillExisted(directoryUri: Uri): Boolean {
  val originalImportDirName = getDisplayName(appContext, directoryUri)
  if (originalImportDirName.isEmpty()) {
    return false
  }
  val destDir = resolveSkillDestinationDir(originalImportDirName)
  return destDir.exists()
}

internal fun SkillManagerViewModel.checkBuiltInSkillExistedForImportedSkill(directoryUri: Uri): Boolean {
  Log.d(TAG, "Checking built-in skill existed for imported skill: $directoryUri")

  val rootFile = DocumentFile.fromTreeUri(appContext, directoryUri)
  val skillMdFile = rootFile?.findFile("SKILL.md")

  if (skillMdFile == null || !skillMdFile.exists()) {
    Log.w(TAG, "SKILL.md not found in the selected directory for built-in check.")
    return false
  }

  val mdContent =
    try {
      appContext.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
        inputStream.bufferedReader().use { it.readText() }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error reading SKILL.md for built-in check", e)
      return false
    } ?: ""

  if (mdContent.isEmpty()) {
    Log.w(TAG, "SKILL.md is empty for built-in check.")
    return false
  }

  val (skillProto, errors) = convertSkillMdToProto(mdContent, builtIn = false, selected = false)

  if (errors.isNotEmpty() || skillProto == null) {
    Log.w(TAG, "Error parsing SKILL.md for built-in check: ${errors.joinToString(", ")}")
    return false
  }

  val importedSkillName = skillProto.name
  return uiState.value.skills.any { it.skill.builtIn && it.skill.name == importedSkillName }
}

internal fun SkillManagerViewModel.validateAndAddSkillFromLocalImport(
  onSuccess: () -> Unit,
  onValidationError: (error: String) -> Unit,
) {
  setValidating(true)
  setValidationError(null)

  val directoryUri = uiState.value.importDirectoryUri
  if (directoryUri == null) {
    setValidating(false)
    val error = "No directory URI set."
    setValidationError(error)
    onValidationError(error)
    return
  }

  viewModelScope.launch(Dispatchers.IO) {
    try {
      Log.d(TAG, "Validating skill from directory URI: $directoryUri")

      val rootFile = DocumentFile.fromTreeUri(appContext, directoryUri)
      val skillMdFile = rootFile?.findFile("SKILL.md")

      if (skillMdFile == null || !skillMdFile.exists()) {
        val error = "SKILL.md not found in the selected directory."
        setValidationError(error)
        onValidationError(error)
        return@launch
      }

      val mdContent =
        try {
          appContext.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error reading SKILL.md", e)
          val error = "Failed to read SKILL.md: ${e.message}"
          setValidationError(error)
          onValidationError(error)
          return@launch
        } ?: ""

      val (skillProto, errors) =
        convertSkillMdToProto(mdContent, builtIn = false, selected = true)

      if (errors.isNotEmpty()) {
        val error = "Error parsing SKILL.md: ${errors.joinToString(", ")}"
        setValidationError(error)
        onValidationError(error)
        return@launch
      }

      skillProto?.let {
        val originalImportDirName = getDisplayName(appContext, directoryUri)
        val destDir = resolveSkillDestinationDir(originalImportDirName)
        val newImportDirName = destDir.relativeTo(appContext.filesDir).path

        if (destDir.exists()) {
          Log.d(TAG, "Destination directory already exists, deleting: ${destDir.path}")
          deleteSkill(name = skillProto.name)
        }
        if (!destDir.exists()) {
          destDir.mkdirs()
        }

        if (uiState.value.skills.any { curSkill -> curSkill.skill.name == skillProto.name }) {
          setValidating(false)
          val error = "A skill with the name '${skillProto.name}' already exists."
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        val sourceDocumentFile = DocumentFile.fromTreeUri(appContext, directoryUri)
        if (sourceDocumentFile == null) {
          Log.e(TAG, "Failed to get DocumentFile from URI: $directoryUri")
          val error = "Failed to access the selected directory."
          setValidationError(error)
          onValidationError(error)
          return@launch
        }

        copyDocumentFileTree(sourceDocumentFile, destDir)

        val skillWithDir = it.toBuilder().setImportDirName(newImportDirName).build()
        addSkill(skill = skillWithDir, addToDataStore = true)
        Log.d(TAG, "Successfully added skill from local import: ${skillWithDir.name}")
        onSuccess()
      }
        ?: run {
          val error = "Unknown error during SKILL.md conversion."
          setValidationError(error)
          onValidationError(error)
        }
    } finally {
      setValidating(false)
      setImportDirectoryUri(null)
    }
  }
}

private fun SkillManagerViewModel.copyDocumentFileTree(source: DocumentFile, dest: File) {
  if (source.isDirectory) {
    dest.mkdirs()
    for (child in source.listFiles()) {
      val childDest = File(dest, child.name!!)
      copyDocumentFileTree(child, childDest)
    }
  } else if (source.isFile) {
    try {
      Log.d(TAG, "Copying file ${source.name} to ${dest.path}")
      appContext.contentResolver.openInputStream(source.uri)?.use { inputStream ->
        dest.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error copying file ${source.name} to ${dest.path}", e)
    }
  }
}
