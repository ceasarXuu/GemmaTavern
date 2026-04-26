package selfgemma.talk.data.cloudllm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import selfgemma.talk.domain.cloudllm.CloudNetworkStatusProvider

@Singleton
class AndroidCloudNetworkStatusProvider
@Inject
constructor(
  @ApplicationContext private val context: Context,
) : CloudNetworkStatusProvider {
  override fun isNetworkAvailable(): Boolean {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }
}
