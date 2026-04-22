package selfgemma.talk.testing

import selfgemma.talk.data.DataStoreRepository
import selfgemma.talk.domain.roleplay.model.StUserProfile
import selfgemma.talk.proto.AccessTokenData
import selfgemma.talk.proto.BenchmarkResult
import selfgemma.talk.proto.Cutout
import selfgemma.talk.proto.ImportedModel
import selfgemma.talk.proto.Skill
import selfgemma.talk.proto.Theme

class FakeDataStoreRepository(
  private var stUserProfile: StUserProfile = StUserProfile(),
) : DataStoreRepository {
  private var textInputHistory: List<String> = emptyList()
  private var theme: Theme = Theme.THEME_AUTO
  private val secrets = linkedMapOf<String, String>()
  private var accessTokenData: AccessTokenData? = null
  private var importedModels: List<ImportedModel> = emptyList()
  private var tosAccepted = false
  private var gemmaTermsAccepted = false
  private var hasRunTinyGarden = false
  private val cutouts = mutableListOf<Cutout>()
  private var hasSeenBenchmarkComparisonHelp = false
  private var messageSoundsEnabled = true
  private var liveTokenSpeedEnabled = true
  private var streamingOutputEnabled = true
  private var roleplayToolDebugOutputEnabled = false
  private var roleplayLocationToolsEnabled = false
  private var roleplayCalendarToolsEnabled = false
  private val disabledRoleplayToolIds = linkedSetOf<String>()
  private var roleEditorAssistantModelId: String? = null
  private var lastUsedLlmModelId: String? = null
  private val benchmarkResults = mutableListOf<BenchmarkResult>()
  private val skills = mutableListOf<Skill>()
  private val viewedPromoIds = linkedSetOf<String>()

  override fun saveTextInputHistory(history: List<String>) {
    textInputHistory = history
  }

  override fun readTextInputHistory(): List<String> = textInputHistory

  override fun saveTheme(theme: Theme) {
    this.theme = theme
  }

  override fun readTheme(): Theme = theme

  override fun saveSecret(key: String, value: String) {
    secrets[key] = value
  }

  override fun readSecret(key: String): String? = secrets[key]

  override fun deleteSecret(key: String) {
    secrets.remove(key)
  }

  override fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    accessTokenData =
      AccessTokenData.newBuilder()
        .setAccessToken(accessToken)
        .setRefreshToken(refreshToken)
        .setExpiresAtMs(expiresAt)
        .build()
  }

  override fun clearAccessTokenData() {
    accessTokenData = null
  }

  override fun readAccessTokenData(): AccessTokenData? = accessTokenData

  override fun saveImportedModels(importedModels: List<ImportedModel>) {
    this.importedModels = importedModels
  }

  override fun readImportedModels(): List<ImportedModel> = importedModels

  override fun isTosAccepted(): Boolean = tosAccepted

  override fun acceptTos() {
    tosAccepted = true
  }

  override fun isGemmaTermsOfUseAccepted(): Boolean = gemmaTermsAccepted

  override fun acceptGemmaTermsOfUse() {
    gemmaTermsAccepted = true
  }

  override fun getHasRunTinyGarden(): Boolean = hasRunTinyGarden

  override fun setHasRunTinyGarden(hasRun: Boolean) {
    hasRunTinyGarden = hasRun
  }

  override fun addCutout(cutout: Cutout) {
    cutouts += cutout
  }

  override fun getAllCutouts(): List<Cutout> = cutouts.toList()

  override fun setCutout(newCutout: Cutout) {
    val index = cutouts.indexOfFirst { it.id == newCutout.id }
    if (index >= 0) {
      cutouts[index] = newCutout
    }
  }

  override fun setCutouts(cutouts: List<Cutout>) {
    this.cutouts.clear()
    this.cutouts.addAll(cutouts)
  }

  override fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) {
    hasSeenBenchmarkComparisonHelp = seen
  }

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean = hasSeenBenchmarkComparisonHelp

  override fun setMessageSoundsEnabled(enabled: Boolean) {
    messageSoundsEnabled = enabled
  }

  override fun areMessageSoundsEnabled(): Boolean = messageSoundsEnabled

  override fun setLiveTokenSpeedEnabled(enabled: Boolean) {
    liveTokenSpeedEnabled = enabled
  }

  override fun isLiveTokenSpeedEnabled(): Boolean = liveTokenSpeedEnabled

  override fun setStreamingOutputEnabled(enabled: Boolean) {
    streamingOutputEnabled = enabled
  }

  override fun isStreamingOutputEnabled(): Boolean = streamingOutputEnabled

  override fun setRoleplayToolDebugOutputEnabled(enabled: Boolean) {
    roleplayToolDebugOutputEnabled = enabled
  }

  override fun isRoleplayToolDebugOutputEnabled(): Boolean = roleplayToolDebugOutputEnabled

  override fun setRoleplayLocationToolsEnabled(enabled: Boolean) {
    roleplayLocationToolsEnabled = enabled
  }

  override fun isRoleplayLocationToolsEnabled(): Boolean = roleplayLocationToolsEnabled

  override fun setRoleplayCalendarToolsEnabled(enabled: Boolean) {
    roleplayCalendarToolsEnabled = enabled
  }

  override fun isRoleplayCalendarToolsEnabled(): Boolean = roleplayCalendarToolsEnabled

  override fun setRoleplayToolEnabled(toolId: String, enabled: Boolean) {
    if (toolId.isBlank()) {
      return
    }
    if (enabled) {
      disabledRoleplayToolIds.remove(toolId)
    } else {
      disabledRoleplayToolIds.add(toolId)
    }
  }

  override fun isRoleplayToolEnabled(toolId: String): Boolean = toolId.isNotBlank() && toolId !in disabledRoleplayToolIds

  override fun setAllRoleplayToolsEnabled(toolIds: Collection<String>, enabled: Boolean) {
    toolIds.filter { it.isNotBlank() }.forEach { toolId -> setRoleplayToolEnabled(toolId, enabled) }
  }

  override fun getDisabledRoleplayToolIds(): Set<String> = disabledRoleplayToolIds.toSet()

  override fun setRoleEditorAssistantModelId(modelId: String?) {
    roleEditorAssistantModelId = modelId
  }

  override fun getRoleEditorAssistantModelId(): String? = roleEditorAssistantModelId

  override fun setLastUsedLlmModelId(modelId: String?) {
    lastUsedLlmModelId = modelId
  }

  override fun getLastUsedLlmModelId(): String? = lastUsedLlmModelId

  override fun setStUserProfile(profile: StUserProfile) {
    stUserProfile = profile
  }

  override fun getStUserProfile(): StUserProfile = stUserProfile

  override fun addBenchmarkResult(result: BenchmarkResult) {
    benchmarkResults.add(0, result)
  }

  override fun getAllBenchmarkResults(): List<BenchmarkResult> = benchmarkResults.toList()

  override fun deleteBenchmarkResult(index: Int) {
    benchmarkResults.removeAt(index)
  }

  override fun addSkill(skill: Skill) {
    skills.add(0, skill)
  }

  override fun setSkills(skills: List<Skill>) {
    this.skills.clear()
    this.skills.addAll(skills)
  }

  override fun setSkillSelected(skill: Skill, selected: Boolean) {
    val index = skills.indexOfFirst { it.name == skill.name }
    if (index >= 0) {
      skills[index] = skills[index].toBuilder().setSelected(selected).build()
    }
  }

  override fun setAllSkillsSelected(selected: Boolean) {
    skills.replaceAll { it.toBuilder().setSelected(selected).build() }
  }

  override fun getAllSkills(): List<Skill> = skills.toList()

  override fun deleteSkill(name: String) {
    skills.removeAll { it.name == name }
  }

  override suspend fun deleteSkills(names: Set<String>) {
    skills.removeAll { it.name in names }
  }

  override fun addViewedPromoId(promoId: String) {
    viewedPromoIds += promoId
  }

  override fun removeViewedPromoId(promoId: String) {
    viewedPromoIds -= promoId
  }

  override fun hasViewedPromo(promoId: String): Boolean = promoId in viewedPromoIds
}
