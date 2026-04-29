package selfgemma.talk.customtasks.agentchat

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.proto.Skill

private const val TAG = "AGSkillManagerLoaders"

internal fun SkillManagerViewModel.loadSkillsInternal(onDone: () -> Unit) {
  if (skillLoaded) {
    onDone()
    return
  }
  setLoading(true)
  viewModelScope.launch(Dispatchers.IO) {
    Log.d(TAG, "Loading skills index...")

    val allDataStoreSkills = dataStoreRepository.getAllSkills()
    val dataStoreBuiltInSkills = allDataStoreSkills.filter { it.builtIn }
    val dataStoreCustomSkills = allDataStoreSkills.filter { !it.builtIn }
    Log.d(
      TAG,
      "data store built-in skills:\n${dataStoreBuiltInSkills.joinToString(separator = "\n") { it.name }}",
    )
    Log.d(
      TAG,
      "data store custom skills:\n${dataStoreCustomSkills.joinToString(separator = "\n") { it.name }}",
    )

    val builtInSelectionMap = dataStoreBuiltInSkills.associate { it.name to it.selected }
    Log.d(TAG, "data store built-in skills selection map: $builtInSelectionMap")

    val builtInSkills = collectBuiltInSkillsFromAssets(builtInSelectionMap)
    Log.d(
      TAG,
      "Final built-in skills:\n${builtInSkills.joinToString(separator = "\n") { "${it.name}(${it.selected})" }}",
    )

    val finalSkills = builtInSkills.toMutableList()
    for (customSkill in dataStoreCustomSkills) {
      if (!finalSkills.any { it.name == customSkill.name }) {
        finalSkills.add(customSkill)
      }
    }

    dataStoreRepository.setSkills(finalSkills)

    mutableUiState.update { currentState ->
      currentState.copy(skills = finalSkills.map { SkillState(skill = it) })
    }

    setLoading(false)
    skillLoaded = true
    withContext(Dispatchers.Default) { onDone() }
  }
}

private fun SkillManagerViewModel.collectBuiltInSkillsFromAssets(
  builtInSelectionMap: Map<String, Boolean>,
): List<Skill> {
  val builtInSkills = mutableListOf<Skill>()
  try {
    val skillAssetDirs = appContext.assets.list("skills") ?: emptyArray()
    for (dirName in skillAssetDirs) {
      val skillMdPath = "skills/$dirName/SKILL.md"
      try {
        appContext.assets.open(skillMdPath).use { inputStream ->
          val mdContent = inputStream.bufferedReader().use { it.readText() }
          val (skillProto, errors) =
            convertSkillMdToProto(
              mdContent,
              builtIn = true,
              selected = true,
              importDir = "assets/skills/$dirName",
            )
          if (errors.isNotEmpty()) {
            Log.w(TAG, "Error parsing asset skill $dirName: ${errors.joinToString(", ")}")
          } else {
            skillProto?.let {
              val selectedState = builtInSelectionMap[it.name] ?: true
              builtInSkills.add(it.toBuilder().setSelected(selectedState).build())
              Log.d(TAG, "Added built-in skill: ${it.name}")
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "SKILL.md not found or error reading for asset skill $dirName", e)
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error listing assets/skills", e)
  }
  return builtInSkills
}
