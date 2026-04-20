package selfgemma.talk.ui.common.textandvoiceinput

import android.speech.SpeechRecognizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HoldToDictateViewModelTest {
  @Test
  fun `resolve recognizer language tag follows device locale`() {
    assertEquals("zh-CN", resolveRecognizerLanguageTag(Locale.SIMPLIFIED_CHINESE))
    assertEquals("en-US", resolveRecognizerLanguageTag(Locale.US))
  }

  @Test
  fun `resolve recognizer language tag falls back for undefined locale`() {
    assertEquals("en-US", resolveRecognizerLanguageTag(Locale.ROOT))
  }

  @Test
  fun `speech recognizer error label maps busy and timeout errors`() {
    assertEquals("recognizer_busy", speechRecognizerErrorLabel(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    assertEquals("speech_timeout", speechRecognizerErrorLabel(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
  }
}
