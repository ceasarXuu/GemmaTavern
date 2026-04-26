package selfgemma.talk.domain.cloudllm

interface CloudNetworkStatusProvider {
  fun isNetworkAvailable(): Boolean
}
