package selfgemma.talk.ui.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

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
}
