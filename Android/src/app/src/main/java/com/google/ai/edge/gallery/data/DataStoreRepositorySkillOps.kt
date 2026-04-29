/*
 * Copyright 2025 Google LLC
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

package selfgemma.talk.data

import androidx.datastore.core.DataStore
import selfgemma.talk.proto.Settings
import selfgemma.talk.proto.Skill
import selfgemma.talk.proto.Skills
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal fun DataStore<Skills>.addSkillBlocking(skill: Skill) {
  runBlocking {
    updateData { skills ->
      val newSkills = buildList {
        add(skill)
        addAll(skills.skillList)
      }
      skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
    }
  }
}

internal fun DataStore<Skills>.setSkillsBlocking(skills: List<Skill>) {
  runBlocking {
    updateData { curSkills ->
      curSkills.toBuilder().clearSkill().addAllSkill(skills).build()
    }
  }
}

internal fun DataStore<Skills>.setSkillSelectedBlocking(skill: Skill, selected: Boolean) {
  runBlocking {
    updateData { skills ->
      val newSkills = mutableListOf<Skill>()
      for (curSkill in skills.skillList) {
        if (curSkill.name == skill.name) {
          newSkills.add(curSkill.toBuilder().setSelected(selected).build())
        } else {
          newSkills.add(curSkill)
        }
      }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }
}

internal fun DataStore<Skills>.setAllSkillsSelectedBlocking(selected: Boolean) {
  runBlocking {
    updateData { skills ->
      val newSkills = mutableListOf<Skill>()
      for (curSkill in skills.skillList) {
        newSkills.add(curSkill.toBuilder().setSelected(selected).build())
      }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }
}

internal fun DataStore<Skills>.getAllSkillsBlocking(): List<Skill> {
  return runBlocking { data.first().skillList }
}

internal fun DataStore<Skills>.deleteSkillBlocking(name: String) {
  runBlocking {
    updateData { skills ->
      val newSkills = mutableListOf<Skill>()
      for (skill in skills.skillList) {
        if (skill.name != name) {
          newSkills.add(skill)
        }
      }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }
}

internal suspend fun DataStore<Skills>.deleteSkillsAsync(names: Set<String>) {
  updateData { skills ->
    val newSkills = skills.skillList.filter { it.name !in names }
    skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
  }
}

internal fun DataStore<Settings>.setRoleplayToolEnabledBlocking(toolId: String, enabled: Boolean) {
  if (toolId.isBlank()) {
    return
  }
  runBlocking {
    updateData { settings ->
      val disabledToolIds = settings.roleplayDisabledToolIdList.toMutableSet()
      if (enabled) {
        disabledToolIds.remove(toolId)
      } else {
        disabledToolIds.add(toolId)
      }
      settings
        .toBuilder()
        .clearRoleplayDisabledToolId()
        .addAllRoleplayDisabledToolId(disabledToolIds.sorted())
        .build()
    }
  }
}

internal fun DataStore<Settings>.isRoleplayToolEnabledBlocking(toolId: String): Boolean {
  if (toolId.isBlank()) {
    return false
  }
  return runBlocking {
    !data.first().roleplayDisabledToolIdList.contains(toolId)
  }
}

internal fun DataStore<Settings>.setAllRoleplayToolsEnabledBlocking(
  toolIds: Collection<String>,
  enabled: Boolean,
) {
  val normalizedToolIds = toolIds.filter { it.isNotBlank() }.toSet()
  runBlocking {
    updateData { settings ->
      val disabledToolIds = settings.roleplayDisabledToolIdList.toMutableSet()
      if (enabled) {
        disabledToolIds.removeAll(normalizedToolIds)
      } else {
        disabledToolIds.addAll(normalizedToolIds)
      }
      settings
        .toBuilder()
        .clearRoleplayDisabledToolId()
        .addAllRoleplayDisabledToolId(disabledToolIds.sorted())
        .build()
    }
  }
}

internal fun DataStore<Settings>.getDisabledRoleplayToolIdsBlocking(): Set<String> {
  return runBlocking {
    data.first().roleplayDisabledToolIdList.toSet()
  }
}
