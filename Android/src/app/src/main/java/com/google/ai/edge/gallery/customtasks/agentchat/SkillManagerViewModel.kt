/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package selfgemma.talk.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.Tag
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import selfgemma.talk.common.LOCAL_URL_BASE
import selfgemma.talk.common.SkillTryOutChip
import selfgemma.talk.common.getJsonResponse
import selfgemma.talk.data.AllowedSkill
import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.data.SkillAllowlist
import selfgemma.talk.proto.Skill
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import javax.inject.Inject
import kotlin.collections.joinToString
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSkillManagerVM"

private const val SKILL_ALLOWLIST_URL = ""

data class SkillState(val skill: Skill)

data class SkillManagerUiState(
  val loading: Boolean = false,
  val skills: List<SkillState> = listOf(),
  val validating: Boolean = false,
  val validationError: String? = null,
  val importDirectoryUri: Uri? = null,
  val loadingSkillAllowlist: Boolean = false,
  val featuredSkills: List<AllowedSkill> = listOf(),
  val skillAllowlistError: String? = null,
)

@HiltViewModel
class SkillManagerViewModel
@Inject
constructor(
  val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val _uiState = MutableStateFlow(SkillManagerUiState())
  val uiState = _uiState.asStateFlow()
  var skillLoaded = false

  internal val mutableUiState: MutableStateFlow<SkillManagerUiState>
    get() = _uiState
  internal val appContext: Context
    get() = context

  internal fun resolveSkillDestinationDir(originalImportDirName: String): File =
    getSkillDestinationDir(originalImportDirName)

  internal fun writeSkillMdFile(
    skillMdFile: File,
    name: String,
    description: String,
    instructions: String,
  ) = writeSkillMd(skillMdFile, name, description, instructions)

  internal fun saveScriptsTo(scriptDestDir: File, scriptsContent: Map<String, String>) =
    saveScripts(scriptDestDir, scriptsContent)

  internal fun applySkillUpdateInDataStore(oldName: String, updatedSkill: Skill) =
    updateSkillInDataStore(oldName, updatedSkill)

  init {
    if (SKILL_ALLOWLIST_URL.isNotEmpty()) {
      loadSkillAllowlist()
    }
  }

  fun loadSkills(onDone: () -> Unit) = loadSkillsInternal(onDone)

  private fun loadSkillAllowlist() {
    _uiState.update { it.copy(loadingSkillAllowlist = true, skillAllowlistError = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val url = SKILL_ALLOWLIST_URL
        Log.d(TAG, "Fetching skill allowlist from: $url")
        val result =
          getJsonResponse<SkillAllowlist>(url)
            ?: throw Exception("Failed to fetch or parse JSON from $url")

        val allowlist = result.jsonObj
        Log.d(TAG, "Successfully loaded ${allowlist.featuredSkills.size} featured skills.")

        _uiState.update { currentState ->
          currentState.copy(
            loadingSkillAllowlist = false,
            featuredSkills = allowlist.featuredSkills,
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading skill allowlist", e)
        _uiState.update { currentState ->
          currentState.copy(
            loadingSkillAllowlist = false,
            skillAllowlistError = "Failed to load skill list: ${e.message}",
          )
        }
      }
    }
  }

  fun setLoading(loading: Boolean) {
    _uiState.update { currentState -> currentState.copy(loading = loading) }
  }

  fun setValidating(validating: Boolean) {
    _uiState.update { currentState -> currentState.copy(validating = validating) }
  }

  fun setValidationError(error: String?) {
    _uiState.update { currentState -> currentState.copy(validationError = error) }
  }

  fun setImportDirectoryUri(uri: Uri?) {
    _uiState.update { currentState -> currentState.copy(importDirectoryUri = uri) }
  }

  fun addSkill(skill: Skill, addToDataStore: Boolean) {
    Log.d(TAG, "Adding skill: $skill")

    // Update state.
    _uiState.update { currentState ->
      val newSkillState = SkillState(skill = skill)
      if (skill.builtIn) {
        currentState.copy(skills = currentState.skills + newSkillState)
      } else {
        val firstCustomIndex = currentState.skills.indexOfFirst { !it.skill.builtIn }
        val newSkills =
          if (firstCustomIndex == -1) {
            currentState.skills + newSkillState
          } else {
            currentState.skills.toMutableList().apply { add(firstCustomIndex, newSkillState) }
          }
        currentState.copy(skills = newSkills)
      }
    }

    if (addToDataStore) {
      // Add skill to data store.
      viewModelScope.launch(Dispatchers.IO) { dataStoreRepository.addSkill(skill) }
    }
  }

  fun deleteSkill(name: String) {
    // Locate the skill to be deleted.
    val skill = _uiState.value.skills.firstOrNull { it.skill.name == name }?.skill
    if (skill == null) {
      return
    }

    // Update state.
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills.filter { it.skill.name != name })
    }

    viewModelScope.launch(Dispatchers.IO) {
      // Delete imported files from file system.
      if (skill.importDirName.isNotEmpty()) {
        try {
          val skillDir = context.filesDir.resolve(skill.importDirName)
          skillDir.deleteRecursively()
        } catch (e: Exception) {
          Log.w(TAG, "Failed to delete skill directory: ${skill.importDirName}", e)
        }
      }

      // Delete skill from data store.
      dataStoreRepository.deleteSkill(name)
    }
  }

  fun deleteSkills(names: Set<String>) {
    val skillsToDelete =
      _uiState.value.skills.filter { names.contains(it.skill.name) }.map { it.skill }
    if (skillsToDelete.isEmpty()) {
      return
    }

    // Update state.
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills.filter { !names.contains(it.skill.name) })
    }

    viewModelScope.launch(Dispatchers.IO) {
      // Delete all imported files from file system.
      for (skill in skillsToDelete) {
        if (skill.importDirName.isNotEmpty()) {
          try {
            val skillDir = context.filesDir.resolve(skill.importDirName)
            skillDir.deleteRecursively()
          } catch (e: Exception) {
            Log.w(TAG, "Failed to delete skill directory: ${skill.importDirName}", e)
          }
        }
      }

      // Delete skills from data store.
      dataStoreRepository.deleteSkills(names)
    }
  }

  fun setSkillSelected(skill: SkillState, selected: Boolean) {
    // Update state.
    val updatedSkill = skill.skill.toBuilder().setSelected(selected).build()
    val updatedSkills =
      _uiState.value.skills.map { curSkill ->
        if (curSkill.skill.name == skill.skill.name) {
          SkillState(skill = updatedSkill)
        } else {
          curSkill
        }
      }
    _uiState.update { currentState -> currentState.copy(skills = updatedSkills) }

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) {
      dataStoreRepository.setSkillSelected(skill.skill, selected)
    }
  }

  fun setAllSkillsSelected(selected: Boolean) {
    // Update state.
    _uiState.update { currentState ->
      val updatedSkills =
        currentState.skills.map { skillState ->
          SkillState(skill = skillState.skill.toBuilder().setSelected(selected).build())
        }
      currentState.copy(skills = updatedSkills)
    }

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) { dataStoreRepository.setAllSkillsSelected(selected) }
  }

  fun getSelectedSkills(): List<Skill> {
    return _uiState.value.skills.filter { it.skill.selected }.map { it.skill }
  }

  fun getSystemPrompt(baseSystemPrompt: String): Contents {
    // Replace ___SKILLS___ with the following skills list:
    //
    // # Skill name: skill_name_1
    // ##Description: skill_description_1
    // ------
    // Skill name: skill_name_2
    // Description: skill_description_2
    // ------
    // Skill name: skill_name_3
    // Description: skill_description_3
    // ------
    val selectedSkillsNamesAndDescriptions = getSelectedSkillsNamesAndDescriptions()
    val systemPrompt = baseSystemPrompt.replace("___SKILLS___", selectedSkillsNamesAndDescriptions)
    Log.d(TAG, "System prompt:\n$systemPrompt")
    return Contents.of(systemPrompt)
  }

  fun getSkill(name: String): Skill? {
    return _uiState.value.skills.firstOrNull { it.skill.name == name }?.skill
  }

  fun getJsSkillUrl(skillName: String, scriptName: String): String? {
    val skill = getSkill(name = skillName) ?: return null
    var baseUrl = ""
    // Construct a local URL for imported skill and built-in skills.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return null
    }
    return "$baseUrl/scripts/$scriptName"
  }

  fun getJsSkillWebviewUrl(skillName: String, url: String): String {
    val skill = getSkill(name = skillName) ?: return url

    // Return the url if it is an absolute url.
    if (url.startsWith("http")) {
      return url
    }

    var baseUrl = ""
    // Construct a local URL for imported skill.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return url
    }
    return "$baseUrl/assets/$url"
  }

  fun getSelectedSkillsNamesAndDescriptions(): String {
    return this.getSelectedSkills().joinToString("\n") { skill ->
      "- ${skill.name}: ${skill.description}"
    }
  }

  /** Saves or updates a custom skill. */
  /** Loads the content of skill scripts from the local file system. */
  fun loadSkillScriptsContent(skill: Skill, onDone: (Map<String, String>) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      if (skill.importDirName.isEmpty()) {
        Log.d(TAG, "Skill ${skill.name} has no import directory, returning empty scripts.")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")

      if (!scriptDir.exists() || !scriptDir.isDirectory) {
        Log.w(TAG, "Script directory not found for skill ${skill.name}: ${scriptDir.path}")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val scriptsContent = mutableMapOf<String, String>()
      for (file in scriptDir.listFiles() ?: emptyArray()) {
        if (file.isFile && (file.name.endsWith(".html") || file.name.endsWith(".js"))) {
          try {
            val content = file.readText()
            scriptsContent[file.name] = content
            Log.d(TAG, "Loaded script ${file.name} for skill ${skill.name}")
          } catch (e: Exception) {
            Log.e(TAG, "Error reading script file ${file.name} for skill ${skill.name}", e)
            scriptsContent[file.name] = "" // Use empty string on error
          }
        }
      }
      withContext(Dispatchers.Default) { onDone(scriptsContent) }
    }
  }

  /** Deletes a specific script file associated with a locally imported skill. */
  fun deleteSkillScript(skill: Skill, scriptName: String) {
    if (skill.importDirName.isEmpty()) {
      Log.d(TAG, "Skill ${skill.name} is not locally imported, cannot delete script.")
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")
      val scriptFile = File(scriptDir, scriptName)

      if (scriptFile.exists()) {
        try {
          if (scriptFile.delete()) {
            Log.d(TAG, "Successfully deleted script: ${scriptFile.path}")
          } else {
            Log.w(TAG, "Failed to delete script: ${scriptFile.path}")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error deleting script ${scriptFile.path}", e)
        }
      } else {
        Log.d(TAG, "Script file not found, ignoring delete: ${scriptFile.path}")
      }
    }
  }

  /** Checks if a skill with the given [skillName] is currently selected. */
  fun isSkillSelected(skillName: String): Boolean {
    return _uiState.value.skills.firstOrNull { it.skill.name == skillName }?.skill?.selected == true
  }

  private fun writeSkillMd(
    skillMdFile: File,
    name: String,
    description: String,
    instructions: String,
  ) {
    Log.d(TAG, "Writing skill.md: ${skillMdFile.path}")
    val mdContent =
      """
    ---
    name: $name
    description: $description
    ---

    $instructions
    """
        .trimIndent()
    skillMdFile.writeText(mdContent)
  }

  private fun saveScripts(scriptDestDir: File, scriptsContent: Map<String, String>) {
    scriptDestDir.mkdirs() // Ensure directory exists

    // Clear existing files in the script directory
    scriptDestDir.listFiles()?.forEach { it.delete() }

    for ((scriptName, content) in scriptsContent) {
      val scriptFile = File(scriptDestDir, scriptName)
      Log.d(TAG, "Saving script: ${scriptFile.path}")
      try {
        scriptFile.writeText(content)
        Log.d(TAG, "Saved script: ${scriptFile.path}")
      } catch (e: Exception) {
        Log.e(TAG, "Error saving script ${scriptName} to ${scriptFile.path}", e)
      }
    }
  }

  private fun updateSkillInDataStore(oldName: String, updatedSkill: Skill) {
    val allSkills = dataStoreRepository.getAllSkills()
    val updatedList = allSkills.map { if (it.name == oldName) updatedSkill else it }
    dataStoreRepository.setSkills(updatedList)
  }

  private fun getSkillDestinationDir(originalImportDirName: String): File {
    val normalizedDirName = originalImportDirName.replace("\\s+".toRegex(), "-")
    val newImportDirName = "skills/${normalizedDirName}"
    return context.filesDir.resolve(newImportDirName)
  }
}
