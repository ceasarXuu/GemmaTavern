package selfgemma.talk.feature.roleplay.settings

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplaySettingsStringsTest {
  @Test
  fun `cloud model strings exist for every shipped settings locale`() {
    val resDir = resolveMainResDir()
    SHIPPED_SETTINGS_FILES.forEach { relativePath ->
      val stringNames = parseStringNames(resDir.resolve(relativePath))
      val missingKeys = REQUIRED_CLOUD_MODEL_KEYS - stringNames
      assertTrue(
        "$relativePath is missing cloud model settings keys: ${missingKeys.sorted()}",
        missingKeys.isEmpty(),
      )
    }
  }

  private fun parseStringNames(path: Path): Set<String> {
    val xml = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    return STRING_NAME_REGEX.findAll(xml).map { it.groupValues[1] }.toSet()
  }

  private fun resolveMainResDir(): Path {
    val candidates =
      listOf(
        Paths.get("src", "main", "res"),
        Paths.get("app", "src", "main", "res"),
        Paths.get("Android", "src", "app", "src", "main", "res"),
      )
    return checkNotNull(candidates.firstOrNull { Files.exists(it.resolve("values").resolve("strings.xml")) }) {
      "Unable to resolve main res dir from user.dir=${System.getProperty("user.dir")}"
    }
  }

  companion object {
    private val SHIPPED_SETTINGS_FILES =
      listOf(
        "values/strings.xml",
        "values-en/strings.xml",
        "values-ja/strings.xml",
        "values-ko/strings.xml",
        "values-zh-rCN/strings.xml",
      )

    private val REQUIRED_CLOUD_MODEL_KEYS =
      setOf(
        "settings_cloud_model_title",
        "settings_cloud_model_summary_disabled",
        "settings_cloud_model_dialog_title",
        "settings_cloud_model_enabled_title",
        "settings_cloud_model_enabled_summary",
        "settings_cloud_model_provider_label",
        "settings_cloud_model_model_name_label",
        "settings_cloud_model_api_key_label",
        "settings_cloud_model_api_key_placeholder",
        "settings_cloud_model_api_key_saved",
        "settings_cloud_model_presets_label",
        "settings_cloud_model_clear_key",
      )

    private val STRING_NAME_REGEX = Regex("""<string\s+name="([^"]+)"""")
  }
}
