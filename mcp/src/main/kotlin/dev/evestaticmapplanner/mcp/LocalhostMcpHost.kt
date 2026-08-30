package dev.evestaticmapplanner.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import java.net.BindException
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Hosts the canonical EVE Map MCP server inside the GUI JVM.
 *
 * HTTP compatibility is intentionally limited to the 2025-series Streamable HTTP
 * protocol implemented by Kotlin MCP SDK 0.14.0. A later 2026-07-28 migration is a
 * separate compatibility phase.
 */
class LocalhostMcpHost private constructor(
    private val configuration: LocalhostMcpHostConfiguration,
    private val clientFactory: () -> McpMapClient,
    private val diagnostics: LocalhostMcpHostDiagnostics,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutation = Mutex()
    private val mutableStatus = MutableStateFlow<LocalhostMcpHostStatus>(LocalhostMcpHostStatus.Stopped)
    private var running: RunningHost? = null
    private var startAttempted = false
    private var closed = false

    val status: StateFlow<LocalhostMcpHostStatus> = mutableStatus.asStateFlow()

    suspend fun start() = withContext(ioDispatcher) {
        mutation.withLock {
            if (closed || running != null || startAttempted) return@withLock
            startAttempted = true
            mutableStatus.value = LocalhostMcpHostStatus.Starting
            val mapClient = clientFactory()
            try {
                val permits = Semaphore(configuration.maxConcurrentRequests, true)
                val engine = embeddedServer(
                    factory = CIO,
                    environment = applicationEnvironment(),
                    configure = {
                        connector {
                            host = configuration.bindAddress
                            port = configuration.port
                        }
                        connectionGroupSize = 1
                        workerGroupSize = 2
                        callGroupSize = configuration.maxConcurrentRequests
                        connectionIdleTimeoutSeconds = configuration.idleTimeoutSeconds.toInt()
                    },
                    module = {
                        installLocalhostMcpEndpoint(configuration, mapClient, permits, diagnostics)
                    },
                )
                engine.start(wait = false)
                running = RunningHost(mapClient) {
                    engine.stop(
                        gracePeriodMillis = configuration.shutdownGraceMillis,
                        timeoutMillis = configuration.shutdownTimeoutMillis,
                    )
                }
                mutableStatus.value = LocalhostMcpHostStatus.Available(configuration.endpoint)
                diagnostics.info("Localhost MCP available on ${configuration.authority}")
            } catch (failure: Throwable) {
                runCatching(mapClient::close)
                if (failure.isPortBindingFailure()) {
                    mutableStatus.value = LocalhostMcpHostStatus.UnavailablePortInUse
                    diagnostics.warning(
                        "Localhost MCP unavailable: ${configuration.authority} is already in use.",
                        failure,
                    )
                } else {
                    mutableStatus.value = LocalhostMcpHostStatus.Failed
                    diagnostics.warning("Localhost MCP failed to start", failure)
                }
            }
        }
    }

    suspend fun shutdown() = withContext(ioDispatcher + NonCancellable) {
        mutation.withLock {
            if (closed) return@withLock
            closed = true
            val active = running
            running = null
            if (active != null) {
                runCatching(active.stop).onFailure {
                    diagnostics.warning("Localhost MCP stop failed", it)
                }
                runCatching(active.mapClient::close).onFailure {
                    diagnostics.warning("Localhost MCP Control client close failed", it)
                }
                diagnostics.info("Localhost MCP stopped")
            }
            mutableStatus.value = LocalhostMcpHostStatus.Stopped
        }
    }

    companion object {
        const val ENDPOINT = "http://127.0.0.1:27892/mcp"

        fun create(diagnostics: LocalhostMcpHostDiagnostics = LocalhostMcpHostDiagnostics.None): LocalhostMcpHost =
            LocalhostMcpHost(
                configuration = LocalhostMcpHostConfiguration(),
                clientFactory = ::LocalMcpMapClient,
                diagnostics = diagnostics,
                ioDispatcher = Dispatchers.IO,
            )

        internal fun createForTest(
            configuration: LocalhostMcpHostConfiguration,
            clientFactory: () -> McpMapClient,
            diagnostics: LocalhostMcpHostDiagnostics = LocalhostMcpHostDiagnostics.None,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): LocalhostMcpHost = LocalhostMcpHost(configuration, clientFactory, diagnostics, ioDispatcher)
    }

    private data class RunningHost(
        val mapClient: McpMapClient,
        val stop: () -> Unit,
    )
}

sealed interface LocalhostMcpHostStatus {
    data object Starting : LocalhostMcpHostStatus
    data class Available(val endpoint: String) : LocalhostMcpHostStatus
    data object UnavailablePortInUse : LocalhostMcpHostStatus
    data object Failed : LocalhostMcpHostStatus
    data object Stopped : LocalhostMcpHostStatus
}

interface LocalhostMcpHostDiagnostics {
    fun info(message: String)
    fun warning(message: String, failure: Throwable? = null)

    data object None : LocalhostMcpHostDiagnostics {
        override fun info(message: String) = Unit
        override fun warning(message: String, failure: Throwable?) = Unit
    }
}

internal data class LocalhostMcpHostConfiguration(
    val bindAddress: String = "127.0.0.1",
    val port: Int = 27892,
    val path: String = "/mcp",
    val maxRequestBodyBytes: Long = 64L * 1024L,
    val maxConcurrentRequests: Int = 8,
    val requestTimeoutMillis: Long = 60_000L,
    val idleTimeoutSeconds: Long = 10L * 60L,
    val shutdownGraceMillis: Long = 1_000L,
    val shutdownTimeoutMillis: Long = 5_000L,
) {
    init {
        require(bindAddress == "127.0.0.1") { "Localhost MCP must bind only to 127.0.0.1" }
        require(port in 1..65_535)
        require(path == "/mcp")
        require(maxRequestBodyBytes > 0)
        require(maxConcurrentRequests > 0)
        require(requestTimeoutMillis > 0)
        require(idleTimeoutSeconds > 0)
    }

    val authority: String get() = "$bindAddress:$port"
    val endpoint: String get() = "http://$authority$path"
}

private fun Application.installLocalhostMcpEndpoint(
    configuration: LocalhostMcpHostConfiguration,
    mapClient: McpMapClient,
    permits: Semaphore,
    diagnostics: LocalhostMcpHostDiagnostics,
) {
    install(ContentNegotiation) { json(McpJson) }
    routing {
        route(configuration.path) {
            post { call.handleLocalhostMcpRequest(configuration, mapClient, permits, diagnostics) }
            get { call.handleLocalhostMcpRequest(configuration, mapClient, permits, diagnostics) }
            delete { call.handleLocalhostMcpRequest(configuration, mapClient, permits, diagnostics) }
            options { call.handleLocalhostMcpRequest(configuration, mapClient, permits, diagnostics) }
        }
    }
}

private suspend fun ApplicationCall.handleLocalhostMcpRequest(
    configuration: LocalhostMcpHostConfiguration,
    mapClient: McpMapClient,
    permits: Semaphore,
    diagnostics: LocalhostMcpHostDiagnostics,
) {
    val host = request.headers[HttpHeaders.Host]
    if (host != configuration.authority) {
        diagnostics.warning("HTTP MCP request rejected: invalid Host")
        respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        return
    }
    if (request.headers[HttpHeaders.Origin] != null) {
        diagnostics.warning("HTTP MCP request rejected: invalid Origin")
        respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
        return
    }
    if (request.httpMethod != HttpMethod.Post) {
        respondText("Method Not Allowed", ContentType.Text.Plain, HttpStatusCode.MethodNotAllowed)
        return
    }
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        respondText("Unsupported Media Type", ContentType.Text.Plain, HttpStatusCode.UnsupportedMediaType)
        return
    }
    val declaredLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > configuration.maxRequestBodyBytes) {
        respondText("Payload Too Large", ContentType.Text.Plain, HttpStatusCode.PayloadTooLarge)
        return
    }
    if (!permits.tryAcquire()) {
        respondText("Too Many Requests", ContentType.Text.Plain, HttpStatusCode.TooManyRequests)
        return
    }

    try {
        withTimeout(configuration.requestTimeoutMillis) {
            handleSdkRequest(configuration, mapClient)
        }
    } catch (_: TimeoutCancellationException) {
        if (!response.isCommitted) {
            respondText("Gateway Timeout", ContentType.Text.Plain, HttpStatusCode.GatewayTimeout)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        diagnostics.warning("HTTP MCP request failed", failure)
        if (!response.isCommitted) {
            respondText("Internal Server Error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }
    } finally {
        permits.release()
    }
}

private suspend fun ApplicationCall.handleSdkRequest(
    configuration: LocalhostMcpHostConfiguration,
    mapClient: McpMapClient,
) {
    val transport = StreamableHttpServerTransport(
        StreamableHttpServerTransport.Configuration(
            enableJsonResponse = true,
            enableDnsRebindingProtection = true,
            allowedHosts = listOf(configuration.bindAddress),
            allowedOrigins = emptyList(),
            eventStore = null,
            retryInterval = null,
            maxRequestBodySize = configuration.maxRequestBodyBytes,
        ),
    ).also { it.setSessionIdGenerator(null) }
    var server: Server? = null
    try {
        server = createMcpServer(mapClient)
        server.createSession(transport)
        transport.handleRequest(session = null, call = this)
    } finally {
        withContext(NonCancellable) {
            runCatching { server?.close() }
            runCatching { transport.close() }
        }
    }
}

private fun Throwable.isPortBindingFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is BindException }
