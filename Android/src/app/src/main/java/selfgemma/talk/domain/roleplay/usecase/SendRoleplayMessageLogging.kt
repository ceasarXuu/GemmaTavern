package selfgemma.talk.domain.roleplay.usecase

import android.os.SystemClock
import android.util.Log

private const val TAG_SRM = "SendRoleplayMessage"

internal fun srmDebugLog(message: String) {
  runCatching {
    Log.d(TAG_SRM, message)
  }
}

internal fun srmWarnLog(message: String, throwable: Throwable? = null) {
  runCatching {
    if (throwable == null) {
      Log.w(TAG_SRM, message)
    } else {
      Log.w(TAG_SRM, message, throwable)
    }
  }
}

internal fun srmSafeElapsedRealtime(): Long {
  return runCatching {
    SystemClock.elapsedRealtime()
  }.getOrDefault(0L)
}
