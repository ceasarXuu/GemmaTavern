package selfgemma.talk.feature.roleplay.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import selfgemma.talk.R

private const val TAG = "RoleplaySoundEffects"

object RoleplaySoundEffectPlayer {
  private val playbackAttributes: AudioAttributes? by lazy(LazyThreadSafetyMode.NONE) {
    runCatching {
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    }
      .onFailure { error ->
        warnLog("audio attributes unavailable; playback will use platform defaults", error)
      }
      .getOrNull()
  }

  fun prepare(context: Context) {
    // MediaPlayer has no preload contract comparable to SoundPool. Keep this as a no-op
    // so callers can retain the same lifecycle without special branching.
    debugLog("player prepared mode=mediaplayer usage=media")
  }

  fun playSend(context: Context) {
    play(context = context, resId = R.raw.iphone_send, label = "send")
  }

  fun playReceive(context: Context) {
    play(context = context, resId = R.raw.iphone_back, label = "receive")
  }

  private fun play(context: Context, resId: Int, label: String) {
    runCatching {
      val player = checkNotNull(MediaPlayer.create(context.applicationContext, resId)) {
        "MediaPlayer.create returned null for $label sound."
      }
      playbackAttributes?.let(player::setAudioAttributes)
      player.setVolume(1f, 1f)
      player.setOnCompletionListener { completedPlayer ->
        completedPlayer.release()
        debugLog("playback completed label=$label")
      }
      player.setOnErrorListener { erroredPlayer, what, extra ->
        errorLog("playback error label=$label what=$what extra=$extra")
        erroredPlayer.release()
        true
      }
      player.start()
      debugLog("playback started label=$label")
    }
      .onFailure { error ->
        errorLog("playback failed label=$label", error)
      }
  }

  private fun debugLog(message: String) {
    runCatching {
      Log.d(TAG, message)
    }
  }

  private fun warnLog(message: String, error: Throwable) {
    runCatching {
      Log.w(TAG, message, error)
    }
  }

  private fun errorLog(message: String, error: Throwable? = null) {
    runCatching {
      if (error == null) {
        Log.e(TAG, message)
      } else {
        Log.e(TAG, message, error)
      }
    }
  }
}
