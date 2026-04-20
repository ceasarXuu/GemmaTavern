package selfgemma.talk.feature.roleplay.chat

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleplayChatStringsTest {
  @Test
  fun `message action strings exist for every shipped chat locale`() {
    val resDir = resolveMainResDir()
    SHIPPED_LOCALE_FILES.forEach { relativePath ->
      val stringNames = parseStringNames(resDir.resolve(relativePath))
      val missingKeys = REQUIRED_CHAT_ACTION_KEYS - stringNames
      assertTrue(
        "$relativePath is missing chat action keys: ${missingKeys.sorted()}",
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
    private val SHIPPED_LOCALE_FILES =
      listOf(
        "values-ja/strings.xml",
        "values-ko/strings.xml",
        "values-zh-rCN/strings.xml",
      )

    private val REQUIRED_CHAT_ACTION_KEYS =
      setOf(
        "chat_message_actions_title",
        "chat_message_actions_content",
        "chat_pin_message_action",
        "chat_rewind_here_action",
        "chat_regenerate_reply_action",
        "chat_edit_from_here_action",
      )

    private val STRING_NAME_REGEX = Regex("""<string\s+name="([^"]+)"""")
  }
}
