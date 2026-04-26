package selfgemma.talk.data.cloudllm

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface CloudCredentialStore {
  fun saveSecret(secretName: String, value: String)

  fun readSecret(secretName: String): String?

  fun deleteSecret(secretName: String)
}

@Singleton
class AndroidCloudCredentialStore
@Inject
constructor(@ApplicationContext private val context: Context) : CloudCredentialStore {
  private val preferences by lazy {
    val masterKey =
      MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    EncryptedSharedPreferences.create(
      context,
      FILE_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  override fun saveSecret(secretName: String, value: String) {
    if (secretName.isBlank()) {
      return
    }
    preferences.edit().putString(secretName, value).apply()
  }

  override fun readSecret(secretName: String): String? {
    if (secretName.isBlank()) {
      return null
    }
    return preferences.getString(secretName, null)
  }

  override fun deleteSecret(secretName: String) {
    if (secretName.isBlank()) {
      return
    }
    preferences.edit().remove(secretName).apply()
  }

  private companion object {
    const val FILE_NAME = "selfgemma_talk_cloud_credentials"
  }
}
