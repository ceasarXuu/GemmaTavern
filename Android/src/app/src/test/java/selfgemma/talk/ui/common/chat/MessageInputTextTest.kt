package selfgemma.talk.ui.common.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageInputTextTest {
  @Test
  fun `roleplay override keeps text entry enabled while reply is in progress`() {
    val state =
      resolveMessageInputInteractionState(
        inProgress = true,
        isResettingSession = false,
        modelInitializing = false,
        isPressRecording = false,
        showStopButtonWhenInProgress = true,
        allowTextInputWhenInProgress = true,
        allowAuxiliaryActionsWhenInProgress = false,
        forceDisableComposer = false,
      )

    assertTrue(state.allowTextEntry)
    assertFalse(state.allowAuxiliaryActions)
    assertFalse(state.showStopButton)
  }

  @Test
  fun `default chat behavior locks composer and shows stop button while running`() {
    val state =
      resolveMessageInputInteractionState(
        inProgress = true,
        isResettingSession = false,
        modelInitializing = false,
        isPressRecording = false,
        showStopButtonWhenInProgress = true,
        allowTextInputWhenInProgress = false,
        allowAuxiliaryActionsWhenInProgress = false,
        forceDisableComposer = false,
      )

    assertFalse(state.allowTextEntry)
    assertFalse(state.allowAuxiliaryActions)
    assertTrue(state.showStopButton)
  }

  @Test
  fun `force disable composer wins even when roleplay override is enabled`() {
    val state =
      resolveMessageInputInteractionState(
        inProgress = true,
        isResettingSession = false,
        modelInitializing = false,
        isPressRecording = false,
        showStopButtonWhenInProgress = true,
        allowTextInputWhenInProgress = true,
        allowAuxiliaryActionsWhenInProgress = false,
        forceDisableComposer = true,
      )

    assertFalse(state.allowTextEntry)
    assertFalse(state.allowAuxiliaryActions)
    assertFalse(state.showStopButton)
  }

  @Test
  fun `recorded audio dispatch requires explicit send with enough duration and bytes`() {
    assertTrue(
      shouldDispatchRecordedAudio(
        sendAudio = true,
        cancelled = false,
        elapsedMs = 600L,
        audioSize = 2048,
      )
    )
  }

  @Test
  fun `recorded audio dispatch rejects quick empty or cancelled clips`() {
    assertFalse(
      shouldDispatchRecordedAudio(
        sendAudio = true,
        cancelled = false,
        elapsedMs = 120L,
        audioSize = 2048,
      )
    )
    assertFalse(
      shouldDispatchRecordedAudio(
        sendAudio = true,
        cancelled = true,
        elapsedMs = 600L,
        audioSize = 2048,
      )
    )
    assertFalse(
      shouldDispatchRecordedAudio(
        sendAudio = true,
        cancelled = false,
        elapsedMs = 600L,
        audioSize = 0,
      )
    )
  }

  @Test
  fun `audio button stays enabled while recording so tap to send can complete`() {
    assertTrue(
      isAudioButtonEnabled(
        isResettingSession = false,
        modelInitializing = false,
        forceDisableComposer = false,
        inProgress = false,
        isPressRecording = true,
        canRecordAudio = false,
        allowAuxiliaryActionsWhenInProgress = false,
      )
    )
  }

  @Test
  fun `roleplay override keeps auxiliary actions enabled while reply is in progress`() {
    val state =
      resolveMessageInputInteractionState(
        inProgress = true,
        isResettingSession = false,
        modelInitializing = false,
        isPressRecording = false,
        showStopButtonWhenInProgress = true,
        allowTextInputWhenInProgress = true,
        allowAuxiliaryActionsWhenInProgress = true,
        forceDisableComposer = false,
      )

    assertTrue(state.allowTextEntry)
    assertTrue(state.allowAuxiliaryActions)
    assertFalse(state.showStopButton)
  }

  @Test
  fun `audio button stays enabled during in-progress merge mode when override is enabled`() {
    assertTrue(
      isAudioButtonEnabled(
        isResettingSession = false,
        modelInitializing = false,
        forceDisableComposer = false,
        inProgress = true,
        isPressRecording = false,
        canRecordAudio = true,
        allowAuxiliaryActionsWhenInProgress = true,
      )
    )
  }

  @Test
  fun `audio button stays disabled when in-progress override is not enabled`() {
    assertFalse(
      isAudioButtonEnabled(
        isResettingSession = false,
        modelInitializing = false,
        forceDisableComposer = false,
        inProgress = true,
        isPressRecording = true,
        canRecordAudio = false,
        allowAuxiliaryActionsWhenInProgress = false,
      )
    )
  }

  @Test
  fun `recording uses inline composer state instead of assist chip`() {
    val state =
      resolveComposerSupportState(
        showSkillsPicker = true,
        isPressRecording = true,
      )

    assertTrue(state == ComposerSupportState.RecordingInline)
  }

  @Test
  fun `skills chip only appears when not recording`() {
    val state =
      resolveComposerSupportState(
        showSkillsPicker = true,
        isPressRecording = false,
      )

    assertTrue(state == ComposerSupportState.SkillsChip)
  }

  @Test
  fun `audio button press starts hold recording when permission is granted`() {
    val action =
      resolveAudioButtonPressAction(
        audioButtonEnabled = true,
        canRecordAudio = true,
        hasRecordAudioPermission = true,
      )

    assertTrue(action == AudioButtonPressAction.StartHoldRecording)
  }

  @Test
  fun `audio button press requests permission before hold recording`() {
    val action =
      resolveAudioButtonPressAction(
        audioButtonEnabled = true,
        canRecordAudio = true,
        hasRecordAudioPermission = false,
      )

    assertTrue(action == AudioButtonPressAction.RequestPermission)
  }
}
