package dev.evestaticmapplanner.sde.update

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class SdeHttpRequest(
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration,
)

class SdeHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
) : AutoCloseable {
    fun firstHeader(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()

    override fun close() = body.close()
}

fun interface SdeHttpTransport {
    fun execute(request: SdeHttpRequest): SdeHttpResponse
}

class JdkSdeHttpTransport(
    private val userAgent: String,
    connectTimeout: Duration = Duration.ofSeconds(15),
) : SdeHttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun execute(request: SdeHttpRequest): SdeHttpResponse {
        val builder = HttpRequest.newBuilder(request.uri)
            .timeout(request.timeout)
            .header("User-Agent", userAgent)
            .GET()
        request.headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        return SdeHttpResponse(response.statusCode(), response.headers().map(), response.body())
    }
}
