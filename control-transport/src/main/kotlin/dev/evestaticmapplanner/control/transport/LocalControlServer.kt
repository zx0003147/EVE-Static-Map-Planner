package dev.evestaticmapplanner.control.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.evestaticmapplanner.control.MapControlService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LocalControlServer(
    private val service: MapControlService,
    private val appVersion: String,
    private val controlApiVersion: Int = LocalControlProtocol.CONTROL_API_VERSION,
    private val timeouts: LocalControlTimeouts = LocalControlTimeouts(),
    private val auditSink: LocalControlAuditSink = NoOpLocalControlAuditSink,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val codec = LocalControlJsonCodec()
    private val secureRandom = SecureRandom()

    @Volatile private var lifecycleState = LocalControlServerState.STOPPED
    @Volatile private var httpServer: HttpServer? = null
    @Volatile private var workerExecutor: BoundedHttpExecutor? = null
    @Volatile private var credentials: LocalControlSessionCredentials? = null
    @Volatile private var session: LocalControlSessionMetadata? = null
    @Volatile private var rateLimiters: LocalControlRateLimiters? = null

    internal var bindAddressProvider: () -> InetAddress = { LocalControlProtocol.loopbackAddress }
    internal var nanoTime: () -> Long = System::nanoTime
    internal var executorFactory: (String) -> BoundedHttpExecutor = ::BoundedHttpExecutor
    internal var responseWriter: LocalControlResponseWriter = DefaultLocalControlResponseWriter
    internal var lastExecutorTerminated: Boolean = true
        private set

    val state: LocalControlServerState get() = lifecycleState
    val boundAddress: InetAddress? get() = session?.boundAddress
    val port: Int? get() = session?.port
    val sessionMetadata: LocalControlSessionMetadata? get() = session

    fun start(): LocalControlSessionMetadata = synchronized(lifecycleLock) {
        check(lifecycleState == LocalControlServerState.STOPPED) { "Local control server is already running" }

        val configuredAddress = bindAddressProvider()
        requireStrictLoopback(configuredAddress)
        val newCredentials = LocalControlSessionCredentials.generate(secureRandom)
        val instanceId = UUID.randomUUID().toString()
        val executor = newWorkerExecutor(instanceId)
        var candidate: HttpServer? = null
        try {
            candidate = HttpServer.create(InetSocketAddress(configuredAddress, 0), HTTP_BACKLOG)
            candidate.executor = executor
            val handler = { exchange: HttpExchange -> handle(exchange) }
            LocalControlOperation.entries.forEach { operation -> candidate.createContext(operation.path, handler) }
            candidate.createContext("/", handler)
            candidate.start()

            val actual = candidate.address
            requireStrictLoopback(actual.address)
            check(actual.port > 0) { "Local control server did not receive an ephemeral port" }

            val metadata = LocalControlSessionMetadata(
                protocolVersion = LocalControlProtocol.PROTOCOL_VERSION,
                instanceId = instanceId,
                appVersion = appVersion,
                controlApiVersion = controlApiVersion,
                boundAddress = actual.address,
                port = actual.port,
            )
            credentials = newCredentials
            session = metadata
            rateLimiters = LocalControlRateLimiters(nanoTime)
            workerExecutor = executor
            httpServer = candidate
            lastExecutorTerminated = false
            lifecycleState = LocalControlServerState.RUNNING
            metadata
        } catch (_: Throwable) {
            candidate?.stop(0)
            executor.shutdownNow()
            newCredentials.invalidate()
            throw IllegalStateException("Local control server failed to start")
        }
    }

    fun stop() {
        val server: HttpServer?
        val executor: BoundedHttpExecutor?
        synchronized(lifecycleLock) {
            if (lifecycleState == LocalControlServerState.STOPPED) return
            lifecycleState = LocalControlServerState.STOPPED
            server = httpServer
            executor = workerExecutor
            credentials?.invalidate()
            credentials = null
            session = null
            rateLimiters = null
            httpServer = null
            workerExecutor = null
        }

        server?.stop(0)
        executor?.shutdown()
        val terminated = try {
            executor?.awaitTermination(Duration.ofSeconds(MAX_OPERATION_SECONDS)) ?: true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!terminated) executor?.shutdownNow()
        lastExecutorTerminated = executor?.isTerminated ?: true
    }

    override fun close() = stop()

    internal fun sessionCredentials(): LocalControlSessionCredentials =
        credentials ?: error("Local control server is not running")

    internal fun executorSnapshot(): BoundedHttpExecutorSnapshot? = workerExecutor?.snapshot()

    private fun handle(exchange: HttpExchange) {
        val startedNanos = System.nanoTime()
        var operationName = "UNKNOWN"
        var response = try {
            val operation = LocalControlOperation.byPath[exchange.requestURI.path]
            operationName = operation?.name ?: "UNKNOWN"
            process(exchange, operation, BoundedHttpExecutor.isBusyResponseTask())
        } catch (_: TimeoutCancellationException) {
            wireError(504, "TIMEOUT", "The operation timed out")
        } catch (failure: WireRequestFailure) {
            wireError(failure.httpStatus, failure.code, failure.safeMessage)
        } catch (_: Throwable) {
            wireError(500, "INTERNAL_ERROR", "The control operation failed")
        }

        var bytes = codec.encode(response.json)
        if (bytes.size > LocalControlProtocol.RESPONSE_BODY_LIMIT_BYTES) {
            response = wireError(500, "INTERNAL_ERROR", "The control operation failed", response.requestId)
            bytes = codec.encode(response.json)
        }

        var delivered = false
        try {
            responseWriter.write(exchange, response.httpStatus, bytes)
            delivered = true
        } catch (_: IOException) {
            // A disconnected client cannot change or roll back an already completed service operation.
        } catch (_: RuntimeException) {
            // Response delivery is intentionally isolated from control service commit semantics.
        } finally {
            exchange.close()
            val elapsed = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000
            runCatching {
                auditSink.record(
                    LocalControlAuditEvent(
                        timestamp = Instant.now(),
                        requestId = response.requestId,
                        operation = operationName,
                        missionId = response.missionId,
                        httpStatus = response.httpStatus,
                        resultCode = response.resultCode,
                        durationMillis = elapsed,
                        responseDelivered = delivered,
                    ),
                )
            }
        }
    }

    private fun process(
        exchange: HttpExchange,
        operation: LocalControlOperation?,
        busyResponseTask: Boolean,
    ): OperationResponse {
        if (exchange.requestHeaders.containsKey("Origin")) {
            throw WireRequestFailure(403, "CAPABILITY_DENIED", "Browser-origin requests are not allowed")
        }
        if (exchange.requestMethod != "POST") {
            throw WireRequestFailure(405, "METHOD_NOT_ALLOWED", "The HTTP method is not allowed")
        }

        val limiters = rateLimiters ?: throw WireRequestFailure(503, "APP_NOT_READY", "The application is not ready")
        if (!limiters.tryAcquire(operation?.mutation == true)) {
            throw WireRequestFailure(429, "RATE_LIMITED", "The request rate limit was exceeded")
        }
        operation ?: throw WireRequestFailure(404, "NOT_FOUND", "The endpoint was not found")

        val activeCredentials = credentials
            ?: throw WireRequestFailure(503, "APP_NOT_READY", "The application is not ready")
        if (!activeCredentials.authenticate(exchange.requestHeaders["Authorization"])) {
            throw WireRequestFailure(401, "UNAUTHORIZED", "UNAUTHORIZED")
        }
        if (!hasJsonContentType(exchange)) {
            throw WireRequestFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json")
        }
        if (exchange.requestURI.rawQuery != null) {
            throw WireRequestFailure(400, "INVALID_ARGUMENT", "Query-string arguments are not supported")
        }
        validateDeclaredBodyLength(exchange)
        if (busyResponseTask) {
            return wireError(503, "APP_BUSY", "The local control transport is busy")
        }

        val body = readLimitedBody(exchange)
        val request = codec.parseRequest(body)
        val activeSession = session ?: throw WireRequestFailure(503, "APP_NOT_READY", "The application is not ready")
        val requestId = (request["requestId"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.let(LocalControlJsonCodec::safeIdentifierOrNull)
        return try {
            runBlocking {
                withTimeout(operation.timeout(timeouts).toMillis()) {
                    codec.execute(operation, request, service, activeSession)
                }
            }
        } catch (failure: WireRequestFailure) {
            throw failure
        } catch (_: TimeoutCancellationException) {
            wireError(504, "TIMEOUT", "The operation timed out", requestId)
        } catch (_: Throwable) {
            wireError(500, "INTERNAL_ERROR", "The control operation failed", requestId)
        }
    }

    private fun readLimitedBody(exchange: HttpExchange): ByteArray {
        validateDeclaredBodyLength(exchange)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES) {
                    throw WireRequestFailure(413, "PAYLOAD_TOO_LARGE", "The request body is too large")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun validateDeclaredBodyLength(exchange: HttpExchange) {
        val lengths = exchange.requestHeaders["Content-Length"]
        if (lengths != null) {
            if (lengths.size != 1) throw WireRequestFailure(400, "INVALID_ARGUMENT", "The request is invalid")
            val declared = lengths.single().trim().toLongOrNull()
                ?: throw WireRequestFailure(400, "INVALID_ARGUMENT", "The request is invalid")
            if (declared < 0) throw WireRequestFailure(400, "INVALID_ARGUMENT", "The request is invalid")
            if (declared > LocalControlProtocol.REQUEST_BODY_LIMIT_BYTES) {
                throw WireRequestFailure(413, "PAYLOAD_TOO_LARGE", "The request body is too large")
            }
        }
    }

    private fun hasJsonContentType(exchange: HttpExchange): Boolean {
        val values = exchange.requestHeaders["Content-Type"] ?: return false
        return values.size == 1 && values.single().substringBefore(';').trim().equals("application/json", ignoreCase = true)
    }

    private fun requireStrictLoopback(address: InetAddress) {
        if (address !is Inet4Address || address.isAnyLocalAddress || !address.isLoopbackAddress || address.hostAddress != "127.0.0.1") {
            throw IllegalArgumentException("Local control requires the 127.0.0.1 IPv4 loopback address")
        }
    }

    private fun newWorkerExecutor(instanceId: String) = executorFactory(instanceId)

    private fun wireError(status: Int, code: String, message: String, requestId: String? = null) = OperationResponse(
        LocalControlJsonCodec.errorJson(requestId, code, message),
        status,
        LocalControlJsonCodec.safeIdentifierOrNull(requestId),
        null,
        code,
    )

    private companion object {
        const val HTTP_BACKLOG = 32
        const val MAX_OPERATION_SECONDS = 35L
    }
}

internal fun interface LocalControlResponseWriter {
    fun write(exchange: HttpExchange, status: Int, body: ByteArray)
}

internal object DefaultLocalControlResponseWriter : LocalControlResponseWriter {
    override fun write(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
