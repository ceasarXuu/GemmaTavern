/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package selfgemma.talk.ui.common.textandvoiceinput

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "AGHTD"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f
private const val STOP_LISTENING_DELAY_MS = 500L

/** The UI state of the HoldToDictateViewModel. */
data class HoldToDictateUiState(val recognizing: Boolean = false, val recognizedText: String = "")

@HiltViewModel
class HoldToDictateViewModel @Inject constructor(@ApplicationContext private val context: Context) :
  ViewModel(), RecognitionListener {
  protected val _uiState = MutableStateFlow(HoldToDictateUiState())
  val uiState = _uiState.asStateFlow()

  private val speechRecognizer: SpeechRecognizer?
  private val recognizerIntent: Intent
  private var onRecognitionDone: ((String) -> Unit)? = null
  private var onAmplitudeChanged: ((Int) -> Unit)? = null
  private var stopListeningJob: Job? = null

  init {
    speechRecognizer = createSpeechRecognizer(context = context, listener = this)
    recognizerIntent = buildSpeechRecognizerIntent(locale = Locale.getDefault())
    Log.d(
      TAG,
      "Hold-to-dictate initialized recognizerAvailable=${speechRecognizer != null} language=${resolveRecognizerLanguageTag(Locale.getDefault())}",
    )
  }

  fun startSpeechRecognition(onDone: (String) -> Unit, onAmplitudeChanged: (Int) -> Unit): Boolean {
    stopListeningJob?.cancel()
    if (uiState.value.recognizing) {
      Log.w(TAG, "Speech recognition already active; cancelling stale session before restart")
      cancelSpeechRecognition()
    }

    onRecognitionDone = onDone
    this.onAmplitudeChanged = onAmplitudeChanged
    onAmplitudeChanged(0)
    setRecognizedText(text = "")

    val recognizer = speechRecognizer
    if (recognizer == null) {
      Log.w(TAG, "Cannot start speech recognition because no recognizer service is available")
      resetRecognitionState(clearRecognizedText = true)
      return false
    }

    val started =
      runCatching { recognizer.startListening(recognizerIntent) }
        .onFailure { error ->
          Log.e(TAG, "Failed to start speech recognition", error)
          resetRecognitionState(clearRecognizedText = true)
        }
        .isSuccess
    if (!started) {
      return false
    }

    Log.d(
      TAG,
      "Speech recognition started language=${recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}",
    )
    setRecognizing(recognizing = true)
    return true
  }

  fun stopSpeechRecognition() {
    val recognizer = speechRecognizer
    if (recognizer == null) {
      resetRecognitionState(clearRecognizedText = false)
      return
    }

    stopListeningJob?.cancel()
    stopListeningJob =
      viewModelScope.launch {
        delay(STOP_LISTENING_DELAY_MS)
        runCatching { recognizer.stopListening() }
          .onSuccess { Log.d(TAG, "Speech recognition stop requested") }
          .onFailure { error ->
            Log.w(TAG, "Failed to stop speech recognition cleanly; cancelling recognizer", error)
            runCatching { recognizer.cancel() }
              .onFailure { cancelError ->
                Log.w(TAG, "Failed to cancel recognizer after stop failure", cancelError)
              }
            resetRecognitionState(clearRecognizedText = false)
          }
      }
  }

  fun cancelSpeechRecognition() {
    stopListeningJob?.cancel()
    stopListeningJob = null
    runCatching { speechRecognizer?.cancel() }
      .onFailure { error -> Log.w(TAG, "Failed to cancel speech recognition cleanly", error) }
    Log.d(TAG, "Speech recognition cancelled")
    resetRecognitionState(clearRecognizedText = true)
  }

  fun setRecognizing(recognizing: Boolean) {
    _uiState.update { uiState.value.copy(recognizing = recognizing) }
  }

  fun setRecognizedText(text: String) {
    _uiState.update { uiState.value.copy(recognizedText = text) }
  }

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {
    Log.d(TAG, "Speech recognizer detected speech input")
  }

  override fun onRmsChanged(rmsdB: Float) {
    onAmplitudeChanged?.invoke(convertRmsDbToAmplitude(rmsdB = rmsdB))
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {
    Log.d(TAG, "Speech recognizer reported end of speech")
  }

  override fun onError(error: Int) {
    Log.w(TAG, "Speech recognition failed error=$error reason=${speechRecognizerErrorLabel(error)}")
    resetRecognitionState(clearRecognizedText = true)
  }

  override fun onResults(results: Bundle?) {
    stopListeningJob?.cancel()
    stopListeningJob = null
    val recognizedText = extractRecognizedText(results)
    setRecognizedText(recognizedText)
    Log.d(TAG, "Speech recognition completed chars=${recognizedText.length}")

    val curOnRecognitionDone = onRecognitionDone
    if (curOnRecognitionDone != null) {
      curOnRecognitionDone(recognizedText)
    }

    resetRecognitionState(clearRecognizedText = false)
  }

  override fun onPartialResults(partialResults: Bundle?) {
    setRecognizedText(extractRecognizedText(partialResults))
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}

  override fun onCleared() {
    stopListeningJob?.cancel()
    stopListeningJob = null
    runCatching { speechRecognizer?.destroy() }
      .onFailure { error -> Log.w(TAG, "Failed to destroy speech recognizer cleanly", error) }
    super.onCleared()
  }

  private fun resetRecognitionState(clearRecognizedText: Boolean) {
    if (clearRecognizedText) {
      setRecognizedText("")
    }
    onAmplitudeChanged?.invoke(0)
    setRecognizing(recognizing = false)
  }
}

private fun createSpeechRecognizer(
  context: Context,
  listener: RecognitionListener,
): SpeechRecognizer? {
  if (!SpeechRecognizer.isRecognitionAvailable(context)) {
    Log.w(TAG, "Speech recognition service is not available on this device")
    return null
  }
  return SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
}

private fun buildSpeechRecognizerIntent(locale: Locale): Intent =
  Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    val languageTag = resolveRecognizerLanguageTag(locale)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
  }

private fun extractRecognizedText(results: Bundle?): String {
  val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
  return matches?.firstOrNull().orEmpty()
}

internal fun resolveRecognizerLanguageTag(locale: Locale): String {
  val languageTag = locale.toLanguageTag()
  return if (languageTag.isBlank() || languageTag == "und") Locale.US.toLanguageTag() else languageTag
}

internal fun speechRecognizerErrorLabel(error: Int): String =
  when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "audio"
    SpeechRecognizer.ERROR_CLIENT -> "client"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient_permissions"
    SpeechRecognizer.ERROR_NETWORK -> "network"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
    SpeechRecognizer.ERROR_SERVER -> "server"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
    else -> "unknown"
  }

private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
  // Clamp the input value to the defined range
  var clampedRmsdB = Math.max(rmsdB, AUDIO_METER_MIN_DB)
  clampedRmsdB = Math.min(clampedRmsdB, AUDIO_METER_MAX_DB)

  // Linear scaling to a 0-65535 range
  return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
    .toInt()
}
