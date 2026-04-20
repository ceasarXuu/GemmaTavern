package selfgemma.talk.ui.llmchat

import selfgemma.talk.data.Accelerator
import selfgemma.talk.data.Model
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlmChatModelHelperTest {
  @Test
  fun resolveImportedCpuWeightCacheFile_returnsSidecarForImportedCpuModel() {
    val tempDir = createTempDirectory("llm-cache-test").toFile()
    val modelFile = File(tempDir, "gemma.litertlm").apply { writeText("model") }
    val cacheFile = File("${modelFile.absolutePath}.xnnpack_cache").apply { writeText("cache") }

    try {
      val result =
        resolveImportedCpuWeightCacheFile(
          model = Model(name = "gemma.litertlm", imported = true),
          accelerator = Accelerator.CPU.label,
          modelPath = modelFile.absolutePath,
        )

      assertEquals(cacheFile.absolutePath, result?.absolutePath)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun resolveImportedCpuWeightCacheFile_ignoresNonImportedOrNonCpuModels() {
    val tempDir = createTempDirectory("llm-cache-test").toFile()
    val modelFile = File(tempDir, "gemma.litertlm").apply { writeText("model") }
    File("${modelFile.absolutePath}.xnnpack_cache").apply { writeText("cache") }

    try {
      assertNull(
        resolveImportedCpuWeightCacheFile(
          model = Model(name = "gemma.litertlm", imported = false),
          accelerator = Accelerator.CPU.label,
          modelPath = modelFile.absolutePath,
        )
      )
      assertNull(
        resolveImportedCpuWeightCacheFile(
          model = Model(name = "gemma.litertlm", imported = true),
          accelerator = Accelerator.GPU.label,
          modelPath = modelFile.absolutePath,
        )
      )
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun buildSessionConfig_capturesPromptTextAndConstrainedDecoding() {
    val config =
      buildSessionConfig(
        systemInstruction = Contents.of("System prompt"),
        tools = listOf(),
        enableConversationConstrainedDecoding = true,
      )

    assertTrue(config.systemInstructionText.contains("System prompt"))
    assertTrue(config.enableConversationConstrainedDecoding)
  }

  @OptIn(ExperimentalApi::class)
  @Test
  fun withConversationConstrainedDecoding_restoresPreviousFlagAfterFailure() {
    ExperimentalFlags.enableConversationConstrainedDecoding = false

    runCatching {
      withConversationConstrainedDecoding(enableConversationConstrainedDecoding = true) {
        throw IllegalStateException("boom")
      }
    }

    assertFalse(ExperimentalFlags.enableConversationConstrainedDecoding)
  }

  @Test
  fun toSystemInstructionContents_returnsNullForBlankPrompt() {
    val sessionConfig = LlmConversationSessionConfig(systemInstructionText = "   ")

    val contents = sessionConfig.toSystemInstructionContents()

    assertNull(contents)
  }

  @Test
  fun toSystemInstructionContents_restoresStoredPromptText() {
    val sessionConfig = LlmConversationSessionConfig(systemInstructionText = "Roleplay system prompt")

    val contents = sessionConfig.toSystemInstructionContents()

    assertNotNull(contents)
    assertTrue(contents.toString().contains("Roleplay system prompt"))
  }

  @Test
  fun shouldIgnoreStopResponseFailure_matchesDeadConversationCrash() {
    assertTrue(
      shouldIgnoreStopResponseFailure(
        IllegalStateException("Conversation is not alive.")
      )
    )
  }

  @Test
  fun shouldIgnoreStopResponseFailure_preservesUnexpectedStopFailures() {
    assertFalse(
      shouldIgnoreStopResponseFailure(
        IllegalStateException("Different failure")
      )
    )
    assertFalse(
      shouldIgnoreStopResponseFailure(
        RuntimeException("Conversation is not alive.")
      )
    )
  }
}
