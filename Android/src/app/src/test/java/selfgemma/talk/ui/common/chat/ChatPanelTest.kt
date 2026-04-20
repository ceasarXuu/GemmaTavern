package selfgemma.talk.ui.common.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPanelTest {
  @Test
  fun `initial load scrolls to bottom even before list metrics are available`() {
    assertTrue(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 0,
        currentMessageCount = 5,
        lastVisibleItemIndex = null,
        lastVisibleItemBottomOffsetPx = null,
        viewportEndOffsetPx = 0,
        isAudioPlaybackActive = false,
      )
    )
  }

  @Test
  fun `new message does not auto scroll while audio playback is active`() {
    assertFalse(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 4,
        currentMessageCount = 5,
        lastVisibleItemIndex = 3,
        lastVisibleItemBottomOffsetPx = 1040,
        viewportEndOffsetPx = 1000,
        isAudioPlaybackActive = true,
      )
    )
  }

  @Test
  fun `new message auto scrolls when user was already near previous bottom`() {
    assertTrue(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 4,
        currentMessageCount = 5,
        lastVisibleItemIndex = 3,
        lastVisibleItemBottomOffsetPx = 1040,
        viewportEndOffsetPx = 1000,
        isAudioPlaybackActive = false,
      )
    )
  }

  @Test
  fun `new message does not auto scroll when user is reading older messages`() {
    assertFalse(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 6,
        currentMessageCount = 7,
        lastVisibleItemIndex = 4,
        lastVisibleItemBottomOffsetPx = 1040,
        viewportEndOffsetPx = 1000,
        isAudioPlaybackActive = false,
      )
    )
  }

  @Test
  fun `tail type change keeps auto scroll only when last item is still visible`() {
    assertTrue(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 5,
        currentMessageCount = 5,
        lastVisibleItemIndex = 4,
        lastVisibleItemBottomOffsetPx = 1050,
        viewportEndOffsetPx = 1000,
        isAudioPlaybackActive = false,
      )
    )
    assertFalse(
      shouldAutoScrollOnTailUpdate(
        previousMessageCount = 5,
        currentMessageCount = 5,
        lastVisibleItemIndex = 3,
        lastVisibleItemBottomOffsetPx = 1050,
        viewportEndOffsetPx = 1000,
        isAudioPlaybackActive = false,
      )
    )
  }
}
