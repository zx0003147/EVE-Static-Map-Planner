package dev.evestaticmapplanner.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

enum class NetworkProxyMode { DIRECT, SYSTEM, MANUAL }

enum class ManualProxyType { HTTP }

data class NetworkProxyConfiguration(
    val mode: NetworkProxyMode = NetworkProxyMode.SYSTEM,
    val manualType: ManualProxyType = ManualProxyType.HTTP,
    val host: String = "",
    val port: Int? = null,
) {
    fun validated(): NetworkProxyConfiguration = apply {
        if (mode == NetworkProxyMode.MANUAL) {
            require(isValidProxyHost(host)) { "Proxy host is invalid" }
            require(port in 1..65535) { "Proxy port must be between 1 and 65535" }
        }
    }

    companion object {
        val Defaults = NetworkProxyConfiguration()
        val LegacyDefaults = NetworkProxyConfiguration(mode = NetworkProxyMode.DIRECT)
    }
}

fun isValidProxyHost(value: String): Boolean {
    val host = value.trim()
    return host.isNotEmpty() && host.length <= 253 &&
        host.none { it.isWhitespace() || it == '/' || it == '\\' || it == ':' }
}

fun isLoopbackHost(host: String?): Boolean = when (host?.trim()?.lowercase()) {
    "localhost", "127.0.0.1", "::1", "[::1]" -> true
    else -> false
}

class ApplicationProxySelector(
    private val systemSelector: ProxySelector,
    initialConfiguration: NetworkProxyConfiguration,
) : ProxySelector() {
    private val configuration = AtomicReference(initialConfiguration.validated())

    fun configuration(): NetworkProxyConfiguration = configuration.get()

    fun update(value: NetworkProxyConfiguration) {
        configuration.set(value.validated())
    }

    fun forConfiguration(value: NetworkProxyConfiguration): ApplicationProxySelector =
        ApplicationProxySelector(systemSelector, value)

    override fun select(uri: URI): List<Proxy> {
        if (isLoopbackHost(uri.host)) return listOf(Proxy.NO_PROXY)
        val current = configuration.get()
        return when (current.mode) {
            NetworkProxyMode.DIRECT -> listOf(Proxy.NO_PROXY)
            NetworkProxyMode.SYSTEM -> systemSelector.select(uri).ifEmpty { listOf(Proxy.NO_PROXY) }
            NetworkProxyMode.MANUAL -> listOf(
                Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(current.host.trim(), checkNotNull(current.port)),
                ),
            )
        }
    }

    override fun connectFailed(uri: URI?, address: java.net.SocketAddress?, failure: IOException?) {
        if (configuration.get().mode == NetworkProxyMode.SYSTEM) {
            systemSelector.connectFailed(uri, address, failure)
        }
    }
}

object NetworkProxyRuntime {
    private val installed = AtomicReference<ApplicationProxySelector?>()

    fun install(
        configuration: NetworkProxyConfiguration,
        systemSelector: ProxySelector = ProxySelector.getDefault(),
    ): ApplicationProxySelector {
        installed.get()?.let {
            it.update(configuration)
            return it
        }
        val selector = ApplicationProxySelector(systemSelector, configuration)
        check(installed.compareAndSet(null, selector)) { "Network proxy runtime was installed concurrently" }
        ProxySelector.setDefault(selector)
        return selector
    }

    fun update(configuration: NetworkProxyConfiguration) {
        checkNotNull(installed.get()) { "Network proxy runtime is not installed" }.update(configuration)
    }

    fun selector(): ApplicationProxySelector =
        checkNotNull(installed.get()) { "Network proxy runtime is not installed" }

    fun current(): NetworkProxyConfiguration = selector().configuration()

    fun selectorFor(configuration: NetworkProxyConfiguration): ApplicationProxySelector =
        selector().forConfiguration(configuration)
}

data class NetworkProxyTestResult(
    val successful: Boolean,
    val selectedProxy: String,
    val target: URI? = null,
    val statusCode: Int? = null,
    val redirectLocation: String? = null,
    val failure: Throwable? = null,
)

class NetworkProxyTester(
    private val targets: List<URI> = listOf(
        URI.create("https://www.cloudflare.com/cdn-cgi/trace"),
        URI.create("https://api.github.com/zen"),
    ),
    private val timeout: Duration = Duration.ofSeconds(10),
) {
    fun test(selector: ProxySelector): NetworkProxyTestResult {
        require(targets.isNotEmpty())
        val client = HttpClient.newBuilder().proxy(selector).connectTimeout(timeout).build()
        var lastResult: NetworkProxyTestResult? = null
        for (target in targets) {
            val selected = runCatching { selector.select(target).toString() }.getOrElse { "<unavailable>" }
            val result = runCatching {
                val response = client.send(
                    HttpRequest.newBuilder(target)
                        .timeout(timeout)
                        .header("Accept", "*/*")
                        .header("User-Agent", "EVE-Static-Map-Planner-Proxy-Test")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                NetworkProxyTestResult(
                    successful = true,
                    selectedProxy = selected,
                    target = target,
                    statusCode = response.statusCode(),
                    redirectLocation = response.headers().firstValue("location").orElse(null),
                )
            }.getOrElse {
                NetworkProxyTestResult(false, selected, target = target, failure = it)
            }
            if (result.successful) return result
            lastResult = result
        }
        return checkNotNull(lastResult)
    }
}
