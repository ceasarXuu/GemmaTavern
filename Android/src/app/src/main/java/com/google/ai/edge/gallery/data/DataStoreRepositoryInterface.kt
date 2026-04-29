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

import selfgemma.talk.proto.AccessTokenData
import selfgemma.talk.proto.BenchmarkResult
import selfgemma.talk.proto.Cutout
import selfgemma.talk.proto.ImportedModel
import selfgemma.talk.proto.Skill
import selfgemma.talk.proto.Theme
import selfgemma.talk.domain.cloudllm.CloudModelConfig
import selfgemma.talk.domain.roleplay.model.StUserProfile

interface DataStoreRepository {
  fun saveTextInputHistory(history: List<String>)

  fun readTextInputHistory(): List<String>

  fun saveTheme(theme: Theme)

  fun readTheme(): Theme

  fun saveSecret(key: String, value: String)

  fun readSecret(key: String): String?

  fun deleteSecret(key: String)

  fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)

  fun clearAccessTokenData()

  fun readAccessTokenData(): AccessTokenData?

  fun saveImportedModels(importedModels: List<ImportedModel>)

  fun readImportedModels(): List<ImportedModel>

  fun isTosAccepted(): Boolean

  fun acceptTos()

  fun isGemmaTermsOfUseAccepted(): Boolean

  fun acceptGemmaTermsOfUse()

  fun getHasRunTinyGarden(): Boolean

  fun setHasRunTinyGarden(hasRun: Boolean)

  fun addCutout(cutout: Cutout)

  fun getAllCutouts(): List<Cutout>

  fun setCutout(newCutout: Cutout)

  fun setCutouts(cutouts: List<Cutout>)

  fun setHasSeenBenchmarkComparisonHelp(seen: Boolean)

  fun getHasSeenBenchmarkComparisonHelp(): Boolean

  fun setMessageSoundsEnabled(enabled: Boolean)

  fun areMessageSoundsEnabled(): Boolean

  fun setLiveTokenSpeedEnabled(enabled: Boolean)

  fun isLiveTokenSpeedEnabled(): Boolean

  fun setStreamingOutputEnabled(enabled: Boolean)

  fun isStreamingOutputEnabled(): Boolean

  fun setCloudModelConfig(config: CloudModelConfig)

  fun getCloudModelConfig(): CloudModelConfig

  fun setRoleplayToolDebugOutputEnabled(enabled: Boolean)

  fun isRoleplayToolDebugOutputEnabled(): Boolean

  fun setRoleplayLocationToolsEnabled(enabled: Boolean)

  fun isRoleplayLocationToolsEnabled(): Boolean

  fun setRoleplayCalendarToolsEnabled(enabled: Boolean)

  fun isRoleplayCalendarToolsEnabled(): Boolean

  fun setRoleplayToolEnabled(toolId: String, enabled: Boolean)

  fun isRoleplayToolEnabled(toolId: String): Boolean

  fun setAllRoleplayToolsEnabled(toolIds: Collection<String>, enabled: Boolean)

  fun getDisabledRoleplayToolIds(): Set<String>

  fun setRoleEditorAssistantModelId(modelId: String?)

  fun getRoleEditorAssistantModelId(): String?

  fun setLastUsedLlmModelId(modelId: String?)

  fun getLastUsedLlmModelId(): String?

  fun setStUserProfile(profile: StUserProfile)

  fun getStUserProfile(): StUserProfile

  fun addBenchmarkResult(result: BenchmarkResult)

  fun getAllBenchmarkResults(): List<BenchmarkResult>

  fun deleteBenchmarkResult(index: Int)

  fun addSkill(skill: Skill)

  fun setSkills(skills: List<Skill>)

  fun setSkillSelected(skill: Skill, selected: Boolean)

  fun setAllSkillsSelected(selected: Boolean)

  fun getAllSkills(): List<Skill>

  fun deleteSkill(name: String)

  suspend fun deleteSkills(names: Set<String>)

  /** Records that a promo with the specified ID has been viewed. */
  fun addViewedPromoId(promoId: String)

  /** Removes a viewed promo record. */
  fun removeViewedPromoId(promoId: String)

  /** Returns whether a promo with the specified ID has been viewed. */
  fun hasViewedPromo(promoId: String): Boolean
}
