package selfgemma.talk.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import selfgemma.talk.data.ConfigKeys
import selfgemma.talk.proto.ImportedModel
import selfgemma.talk.proto.LlmConfig

class ModelManagerViewModelTest {
  @Test
  fun `initialization waits for cleanup completion when an instance already exists`() {
    val events = mutableListOf<String>()
    var cleanupCompletion: (() -> Unit)? = null

    runInitializationAfterOptionalCleanup(
      hasExistingInstance = true,
      startCleanup = { onDone ->
        events += "cleanup-started"
        cleanupCompletion = {
          events += "cleanup-finished"
          onDone()
        }
      },
      startInitialization = {
        events += "initialization-started"
      },
    )

    assertEquals(listOf("cleanup-started"), events)
    assertNotNull(cleanupCompletion)

    cleanupCompletion!!.invoke()

    assertEquals(
      listOf("cleanup-started", "cleanup-finished", "initialization-started"),
      events,
    )
  }

  @Test
  fun `initialization starts immediately when no prior instance exists`() {
    var cleanupCalled = false
    val events = mutableListOf<String>()

    runInitializationAfterOptionalCleanup(
      hasExistingInstance = false,
      startCleanup = {
        cleanupCalled = true
      },
      startInitialization = {
        events += "initialization-started"
      },
    )

    assertFalse(cleanupCalled)
    assertEquals(listOf("initialization-started"), events)
  }

  @Test
  fun `imported model config update persists runtime defaults and selected accelerator`() {
    val importedModel =
      ImportedModel.newBuilder()
        .setFileName("local-model.litertlm")
        .setFileSize(123L)
        .setLlmConfig(
          LlmConfig.newBuilder()
            .addCompatibleAccelerators("cpu")
            .addCompatibleAccelerators("gpu")
            .setDefaultMaxTokens(1024)
            .setDefaultTopk(64)
            .setDefaultTopp(0.95f)
            .setDefaultTemperature(1.0f)
            .setSupportThinking(true)
            .build()
        )
        .build()

    val updated =
      updatedImportedModelWithConfigValues(
        importedModel = importedModel,
        values =
          mapOf(
            ConfigKeys.MAX_TOKENS.label to 2048f,
            ConfigKeys.TOPK.label to 40f,
            ConfigKeys.TOPP.label to 0.8f,
            ConfigKeys.TEMPERATURE.label to 0.7f,
            ConfigKeys.ACCELERATOR.label to "gpu",
          ),
      )

    assertEquals("local-model.litertlm", updated.fileName)
    assertEquals(123L, updated.fileSize)
    assertEquals(listOf("gpu", "cpu"), updated.llmConfig.compatibleAcceleratorsList)
    assertEquals(2048, updated.llmConfig.defaultMaxTokens)
    assertEquals(40, updated.llmConfig.defaultTopk)
    assertEquals(0.8f, updated.llmConfig.defaultTopp)
    assertEquals(0.7f, updated.llmConfig.defaultTemperature)
    assertEquals(true, updated.llmConfig.supportThinking)
  }
}
