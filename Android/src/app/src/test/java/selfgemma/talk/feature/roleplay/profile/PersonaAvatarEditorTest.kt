package selfgemma.talk.feature.roleplay.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaAvatarEditorTest {
  @Test
  fun calculateCropSourceRect_keepsCenteredWindowWhenOffsetsAreZero() {
    val cropRect =
      calculateCropSourceRect(
        bitmapWidth = 400,
        bitmapHeight = 200,
        zoom = 1f,
        offsetX = 0f,
        offsetY = 0f,
        outputSize = 128,
      )

    assertEquals(100f, cropRect.left, 0.001f)
    assertEquals(0f, cropRect.top, 0.001f)
    assertEquals(300f, cropRect.right, 0.001f)
    assertEquals(200f, cropRect.bottom, 0.001f)
  }

  @Test
  fun calculateCropSourceRect_clampsExtremePanIntoValidSourceBounds() {
    val cropRect =
      calculateCropSourceRect(
        bitmapWidth = 320,
        bitmapHeight = 320,
        zoom = 1f,
        offsetX = 9_999f,
        offsetY = 0f,
        outputSize = 128,
      )

    assertEquals(0f, cropRect.left, 0.001f)
    assertEquals(0f, cropRect.top, 0.001f)
    assertEquals(320f, cropRect.right, 0.001f)
    assertEquals(320f, cropRect.bottom, 0.001f)
  }

  @Test
  fun calculateCropSourceRect_reducesWindowSizeWhenZoomIncreases() {
    val cropRect =
      calculateCropSourceRect(
        bitmapWidth = 320,
        bitmapHeight = 320,
        zoom = 2f,
        offsetX = 0f,
        offsetY = 0f,
        outputSize = 128,
      )

    assertEquals(80f, cropRect.left, 0.001f)
    assertEquals(80f, cropRect.top, 0.001f)
    assertEquals(240f, cropRect.right, 0.001f)
    assertEquals(240f, cropRect.bottom, 0.001f)
    assertTrue(cropRect.right - cropRect.left < 320f)
  }
}
