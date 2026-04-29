package selfgemma.talk.customtasks.agentchat

import android.util.Log
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import selfgemma.talk.proto.Skill

private const val TAG = "AGSkillManagerEditing"

internal fun SkillManagerViewModel.saveSkillEdit(
  index: Int,
  name: String,
  description: String,
  instructions: String,
  scriptsContent: Map<String, String>,
  onSuccess: () -> Unit,
  onError: (error: String) -> Unit,
) {
  viewModelScope.launch(Dispatchers.IO) {
    try {
      Log.d(TAG, "saveSkillEdit: $name")

      val isNewSkill = index < 0 || index >= uiState.value.skills.size

      if (isNewSkill) {
        saveNewSkill(name, description, instructions, scriptsContent, onSuccess, onError)
      } else {
        saveExistingSkillEdit(
          index = index,
          name = name,
          description = description,
          instructions = instructions,
          scriptsContent = scriptsContent,
          onSuccess = onSuccess,
          onError = onError,
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error saving skill edit", e)
      onError("Failed to save skill: ${e.message}")
    }
  }
}

private fun SkillManagerViewModel.saveNewSkill(
  name: String,
  description: String,
  instructions: String,
  scriptsContent: Map<String, String>,
  onSuccess: () -> Unit,
  onError: (error: String) -> Unit,
) {
  Log.d(TAG, "Saving new skill: $name")

  if (uiState.value.skills.any { it.skill.name == name }) {
    val error = "A skill with the name '${name}' already exists."
    Log.w(TAG, error)
    onError(error)
    return
  }

  val normalizedName = name.replace("\\s+".toRegex(), "-")
  val skillDestDir = appContext.filesDir.resolve("skills/${normalizedName}")
  val scriptDestDir = File(skillDestDir, "scripts")
  if (skillDestDir.exists()) {
    Log.w(
      TAG,
      "Skill destination directory already exists for new skill: ${skillDestDir.path}, deleting.",
    )
    skillDestDir.deleteRecursively()
  }

  skillDestDir.mkdirs()
  scriptDestDir.mkdirs()
  val skillMdFile = File(skillDestDir, "SKILL.md")

  writeSkillMdFile(skillMdFile, normalizedName, description, instructions)
  saveScriptsTo(scriptDestDir, scriptsContent)

  val newSkill =
    Skill.newBuilder()
      .setName(normalizedName)
      .setDescription(description)
      .setInstructions(instructions)
      .setBuiltIn(false)
      .setSelected(true)
      .setSkillUrl("")
      .setImportDirName(skillDestDir.relativeTo(appContext.filesDir).path)
      .build()
  addSkill(newSkill, addToDataStore = true)
  onSuccess()
}

private fun SkillManagerViewModel.saveExistingSkillEdit(
  index: Int,
  name: String,
  description: String,
  instructions: String,
  scriptsContent: Map<String, String>,
  onSuccess: () -> Unit,
  onError: (error: String) -> Unit,
) {
  Log.d(TAG, "Saving skill edit: $name")

  val existingSkillState = uiState.value.skills[index]
  val existingSkill = existingSkillState.skill

  val oldName = existingSkill.name
  val normalizedNewName = name.replace("\\s+".toRegex(), "-")
  val newSkillDestDir = appContext.filesDir.resolve("skills/${normalizedNewName}")
  val newScriptDestDir = File(newSkillDestDir, "scripts")
  val newSkillMdFile = File(newSkillDestDir, "SKILL.md")

  if (existingSkill.builtIn) {
    onError("Cannot edit built-in skills.")
    return
  }

  var updatedImportDirName = existingSkill.importDirName

  if (oldName != normalizedNewName) {
    Log.d(TAG, "Renaming skill from $oldName to $normalizedNewName")

    if (uiState.value.skills.any { it.skill.name == normalizedNewName }) {
      val error = "A skill with the name '${normalizedNewName}' already exists."
      Log.w(TAG, error)
      onError(error)
      return
    }

    val oldSkillDestDir = appContext.filesDir.resolve(existingSkill.importDirName)
    if (oldSkillDestDir.exists()) {
      Log.d(
        TAG,
        "Renaming directory from ${oldSkillDestDir.path} to ${newSkillDestDir.path}",
      )
      if (!oldSkillDestDir.renameTo(newSkillDestDir)) {
        val error =
          "Failed to rename skill directory from ${oldSkillDestDir.name} to ${newSkillDestDir.name}."
        Log.e(TAG, error)
        onError(error)
        return
      }
      updatedImportDirName = newSkillDestDir.relativeTo(appContext.filesDir).path
    } else {
      Log.w(TAG, "Old skill directory not found: ${oldSkillDestDir.path}")
      newSkillDestDir.mkdirs()
    }
  }

  writeSkillMdFile(newSkillMdFile, normalizedNewName, description, instructions)

  newScriptDestDir.deleteRecursively()
  newScriptDestDir.mkdirs()
  saveScriptsTo(newScriptDestDir, scriptsContent)

  val updatedSkill =
    existingSkill
      .toBuilder()
      .setName(normalizedNewName)
      .setDescription(description)
      .setInstructions(instructions)
      .setImportDirName(updatedImportDirName)
      .build()

  mutableUiState.update { currentState ->
    val updatedSkillsList =
      currentState.skills.mapIndexed { i, skillState ->
        if (i == index) SkillState(skill = updatedSkill) else skillState
      }
    currentState.copy(skills = updatedSkillsList)
  }

  applySkillUpdateInDataStore(oldName, updatedSkill)
  onSuccess()
}
