/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package selfgemma.talk.ui.common.chat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selfgemma.talk.common.calculatePeakAmplitude
import selfgemma.talk.data.MAX_AUDIO_CLIP_DURATION_SEC
import selfgemma.talk.data.SAMPLE_RATE

private const val TAG = "AGHoldToTalkAudio"
private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

internal class HoldToTalkRecordingSession(
  val audioRecord: AudioRecord,
  val audioStream: ByteArrayOutputStream,
  val startedAtMs: Long,
) {
  lateinit var readJob: Job
}

internal data class FinalizedHoldToTalkRecording(
  val audioData: ByteArray,
  val elapsedMs: Long,
)

/** Allocates an [AudioRecord], starts a background read loop, and returns the active session. */
internal fun beginHoldToTalkAudioRecording(
  scope: CoroutineScope,
  isStillActive: (HoldToTalkRecordingSession) -> Boolean,
  onAmplitudeUpdate: suspend (HoldToTalkRecordingSession, amplitude: Int, elapsedMs: Long) -> Unit,
  onMaxDurationReached: suspend () -> Unit,
  onStartFailure: suspend (HoldToTalkRecordingSession) -> Unit,
): HoldToTalkRecordingSession? {
  val minBufferSize =
    AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING)
  if (minBufferSize <= 0) {
    Log.w(TAG, "Cannot start hold-to-talk recording invalidMinBufferSize=$minBufferSize")
    return null
  }
  val recorder =
    AudioRecord(
      MediaRecorder.AudioSource.MIC,
      SAMPLE_RATE,
      AUDIO_CHANNEL_CONFIG,
      AUDIO_ENCODING,
      minBufferSize,
    )
  if (recorder.state != AudioRecord.STATE_INITIALIZED) {
    Log.w(TAG, "Cannot start hold-to-talk recording recorder not initialized")
    recorder.release()
    return null
  }
  val session =
    HoldToTalkRecordingSession(
      audioRecord = recorder,
      audioStream = ByteArrayOutputStream(),
      startedAtMs = System.currentTimeMillis(),
    )
  session.readJob =
    scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(minBufferSize)
      runCatching { recorder.startRecording() }
        .onFailure { error ->
          Log.e(TAG, "Failed to start hold-to-talk recording", error)
          withContext(Dispatchers.Main) { onStartFailure(session) }
          return@launch
        }
      while (isActive && isStillActive(session)) {
        val bytesRead = recorder.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
          val amplitude = calculatePeakAmplitude(buffer = buffer, bytesRead = bytesRead)
          session.audioStream.write(buffer, 0, bytesRead)
          val elapsedMs = System.currentTimeMillis() - session.startedAtMs
          withContext(Dispatchers.Main) {
            if (isStillActive(session)) onAmplitudeUpdate(session, amplitude, elapsedMs)
          }
        }
        if (System.currentTimeMillis() - session.startedAtMs >=
          MAX_AUDIO_CLIP_DURATION_SEC * 1000L
        ) {
          withContext(Dispatchers.Main) { onMaxDurationReached() }
          break
        }
      }
    }
  Log.d(TAG, "Started chat audio recording")
  return session
}

/** Stops the recorder, releases resources, and returns the captured audio buffer. */
internal suspend fun finalizeHoldToTalkAudioRecording(
  session: HoldToTalkRecordingSession,
): FinalizedHoldToTalkRecording {
  val elapsedMs = System.currentTimeMillis() - session.startedAtMs
  runCatching {
    if (session.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      session.audioRecord.stop()
    }
  }.onFailure { error -> Log.w(TAG, "Failed to stop hold-to-talk recorder cleanly", error) }
  session.readJob.cancelAndJoin()
  session.audioRecord.release()
  val audioData = session.audioStream.toByteArray()
  session.audioStream.reset()
  return FinalizedHoldToTalkRecording(audioData = audioData, elapsedMs = elapsedMs)
}

/** Best-effort synchronous teardown used by lifecycle dispose hooks. */
internal fun disposeHoldToTalkAudioRecording(session: HoldToTalkRecordingSession) {
  runCatching {
    if (session.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      session.audioRecord.stop()
    }
  }
  session.readJob.cancel()
  runCatching { session.audioRecord.release() }
}
