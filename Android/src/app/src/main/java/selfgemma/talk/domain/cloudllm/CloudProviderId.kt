package selfgemma.talk.domain.cloudllm

enum class CloudProviderId(val storageId: String) {
  OPENROUTER("openrouter"),
  DEEPSEEK("deepseek"),
  CLAUDE("claude"),
  ;

  companion object {
    fun fromStorageId(value: String?): CloudProviderId? {
      return entries.firstOrNull { provider -> provider.storageId == value?.trim()?.lowercase() }
    }
  }
}
