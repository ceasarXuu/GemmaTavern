package selfgemma.talk.data.cloudllm

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudHttpRequest(
  val url: String,
  val method: String = "POST",
  val headers: Map<String, String> = emptyMap(),
  val body: String = "",
)

data class CloudHttpResponse(
  val statusCode: Int,
  val body: String,
)

interface CloudHttpClient {
  suspend fun execute(request: CloudHttpRequest): CloudHttpResponse

  suspend fun stream(
    request: CloudHttpRequest,
    onLine: suspend (String) -> Unit,
  ): CloudHttpResponse {
    val response = execute(request)
    if (response.statusCode in 200..299) {
      for (line in response.body.lineSequence()) {
        onLine(line)
      }
    }
    return response
  }
}

@Singleton
class UrlConnectionCloudHttpClient @Inject constructor() : CloudHttpClient {
  override suspend fun execute(request: CloudHttpRequest): CloudHttpResponse {
    return withContext(Dispatchers.IO) {
      val connection = URL(request.url).openConnection() as HttpURLConnection
      try {
        prepareConnection(connection, request)
        val statusCode = connection.responseCode
        val input = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body =
          input?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
          } ?: ""
        CloudHttpResponse(statusCode = statusCode, body = body)
      } finally {
        connection.disconnect()
      }
    }
  }

  override suspend fun stream(
    request: CloudHttpRequest,
    onLine: suspend (String) -> Unit,
  ): CloudHttpResponse {
    return withContext(Dispatchers.IO) {
      val connection = URL(request.url).openConnection() as HttpURLConnection
      try {
        prepareConnection(connection, request)
        val statusCode = connection.responseCode
        val input = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        if (input == null) {
          return@withContext CloudHttpResponse(statusCode = statusCode, body = "")
        }
        val body = StringBuilder()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            body.append(line).append('\n')
            if (statusCode in 200..299) {
              onLine(line)
            }
          }
        }
        CloudHttpResponse(statusCode = statusCode, body = body.toString())
      } finally {
        connection.disconnect()
      }
    }
  }

  private fun prepareConnection(
    connection: HttpURLConnection,
    request: CloudHttpRequest,
  ) {
    connection.requestMethod = request.method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    if (request.body.isNotBlank()) {
      connection.doOutput = true
      connection.outputStream.use { output ->
        output.write(request.body.toByteArray(Charsets.UTF_8))
      }
    }
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 120_000
  }
}
