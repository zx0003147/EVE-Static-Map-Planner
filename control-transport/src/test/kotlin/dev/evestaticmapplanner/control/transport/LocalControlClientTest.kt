package dev.evestaticmapplanner.control.transport

import com.sun.net.httpserver.HttpServer
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalControlClientTest {
    @Test
    fun `reader accepts only strict owner-only bounded discovery files`() = withTemporaryRoot { root ->
        val service = searchService("A")
        withPublished(root, service) { _, _, _ ->
            val snapshot = SecureLocalControlDiscoveryReader(root).read()
            assertEquals(LocalControlProtocol.PROTOCOL_VERSION, snapshot.protocolVersion)
            assertEquals(LocalControlProtocol.CONTROL_API_VERSION, snapshot.controlApiVersion)
            assertTrue(snapshot.port in 1..65535)
            assertFalse(snapshot.toString().contains("Bearer"))
            assertFalse(snapshot.toString().contains("session.key"))
            snapshot.credentials.invalidate()
        }

        val missing = root.resolve("missing")
        assertFailsWith<LocalControlDiscoveryUnavailableException> { SecureLocalControlDiscoveryReader(missing).read() }
    }

    @Test
    fun `malformed oversized invalid port key and symlink discovery fail closed`() = withTemporaryRoot { root ->
        withPublished(root, searchService("A")) { _, _, _ ->
            val descriptor = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
            val key = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
            val validDescriptor = Files.readAllBytes(descriptor)
            val validKey = Files.readAllBytes(key)

            Files.writeString(descriptor, "{not-json", StandardOpenOption.TRUNCATE_EXISTING)
            assertUnavailable(root)
            Files.write(descriptor, ByteArray(LocalControlDiscoveryProtocol.MAX_DESCRIPTOR_BYTES.toInt() + 1))
            assertUnavailable(root)
            Files.write(descriptor, validDescriptor, StandardOpenOption.TRUNCATE_EXISTING)

            rewriteDescriptor(descriptor) { it + ("port" to JsonPrimitive(0)) }
            assertUnavailable(root)
            Files.write(descriptor, validDescriptor, StandardOpenOption.TRUNCATE_EXISTING)

            Files.writeString(key, "malformed", StandardOpenOption.TRUNCATE_EXISTING)
            assertUnavailable(root)
            Files.write(key, ByteArray(LocalControlDiscoveryProtocol.MAX_SESSION_KEY_BYTES.toInt() + 1))
            assertUnavailable(root)
            Files.write(key, validKey, StandardOpenOption.TRUNCATE_EXISTING)

            Files.delete(descriptor)
            val target = root.resolve("descriptor-target")
            Files.write(target, validDescriptor)
            Files.createSymbolicLink(descriptor, target.fileName)
            assertUnavailable(root)
        }
    }

    @Test
    fun `unsafe ACL verification is rejected without leaking details`() = withTemporaryRoot { root ->
        withPublished(root, searchService("A")) { _, _, _ ->
            val rejectingAcl = object : DiscoveryAclSecurity {
                override fun secureDirectory(path: Path) = Unit
                override fun secureFile(path: Path) = Unit
                override fun verifyDirectory(path: Path) = throw DiscoverySecurityException("unsafe test path $path")
                override fun verifyFile(path: Path) = Unit
            }
            val failure = assertFailsWith<LocalControlDiscoveryUnavailableException> {
                SecureLocalControlDiscoveryReader(root, rejectingAcl).read()
            }
            assertEquals("Local control discovery is unavailable", failure.message)
            assertFalse(failure.stackTraceToString().contains("session.key"))
        }
    }

    @Test
    fun `handshake validates authorization instance protocol control API and availability`() = withTemporaryRoot { root ->
        withPublished(root, searchService("A")) { server, _, _ ->
            val descriptor = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
            val key = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
            val validDescriptor = Files.readAllBytes(descriptor)
            val validKey = Files.readAllBytes(key)

            Files.writeString(key, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", StandardOpenOption.TRUNCATE_EXISTING)
            assertDisconnected(client(root).searchSystemBlocking())
            Files.write(key, validKey, StandardOpenOption.TRUNCATE_EXISTING)

            rewriteDescriptor(descriptor) { it + ("instanceId" to JsonPrimitive("different-instance")) }
            assertDisconnected(client(root).searchSystemBlocking())
            Files.write(descriptor, validDescriptor, StandardOpenOption.TRUNCATE_EXISTING)

            rewriteDescriptor(descriptor) { it + ("protocolVersion" to JsonPrimitive(99)) }
            assertDisconnected(client(root).searchSystemBlocking())
            Files.write(descriptor, validDescriptor, StandardOpenOption.TRUNCATE_EXISTING)

            rewriteDescriptor(descriptor) { it + ("controlApiVersion" to JsonPrimitive(99)) }
            assertDisconnected(client(root).searchSystemBlocking())
            Files.write(descriptor, validDescriptor, StandardOpenOption.TRUNCATE_EXISTING)

            server.stop()
            assertDisconnected(client(root).searchSystemBlocking())
        }
    }

    @Test
    fun `client follows no redirects and uses an explicit direct proxy selector`() = withTemporaryRoot { root ->
        val direct = newDirectLocalControlHttpClient()
        assertEquals(java.net.http.HttpClient.Redirect.NEVER, direct.followRedirects())
        assertEquals(DirectProxySelector, direct.proxy().orElse(null))
        assertEquals(listOf(Proxy.NO_PROXY), DirectProxySelector.select(URI("http://127.0.0.1:1/v1/handshake")))

        withPublished(root, searchService("A")) { _, _, _ ->
            val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 1)
            redirect.executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
            redirect.createContext("/") { exchange ->
                exchange.responseHeaders.set("Location", "http://example.invalid/")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            redirect.start()
            try {
                rewriteDescriptor(root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)) {
                    it + ("port" to JsonPrimitive(redirect.address.port))
                }
                assertDisconnected(client(root).searchSystemBlocking())
            } finally {
                redirect.stop(0)
            }
        }
    }

    @Test
    fun `session rotation discovers and handshakes the new app instance`() = withTemporaryRoot { rootA ->
        withTemporaryRoot { rootB ->
            withPublished(rootA, searchService("A")) { _, _, _ ->
                withPublished(rootB, searchService("B")) { _, _, _ ->
                    client(rootA).use { client ->
                        assertSearchName(client.searchSystemBlocking(), "A")
                        replacePublishedSnapshot(rootB, rootA)
                        assertSearchName(client.searchSystemBlocking(), "B")
                    }
                }
            }
        }
    }

    @Test
    fun `uncertain mutation delivery retries with one idempotent creation`() = withTemporaryRoot { root ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = defaultMissionService(scope)
        withPublished(root, service) { server, _, _ ->
            val failFirst = AtomicBoolean(true)
            server.responseWriter = LocalControlResponseWriter { exchange, status, body ->
                if (exchange.requestURI.path == LocalControlOperation.BEGIN_MISSION.path && failFirst.compareAndSet(true, false)) {
                    throw IOException("simulated response loss")
                }
                DefaultLocalControlResponseWriter.write(exchange, status, body)
            }
            val ids = AtomicInteger()
            val client = client(root) { "bridge-${ids.incrementAndGet()}" }
            val result = runBlocking { client.beginMission("Only Once") }
            assertIs<LocalControlClientResult.Success>(result)
            val active = runBlocking { service.getActiveMissions(GetActiveMissionsRequest("verify-active")) }
            assertEquals(1, (assertIs<ControlResult.Success<*>>(active).value as List<*>).size)
            client.close()
        }
        service.close()
        scope.cancel()
    }

    @Test
    fun `uncertain mutation is never replayed into a changed session`() = withTemporaryRoot { rootA ->
        withTemporaryRoot { rootB ->
            val scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val serviceA = defaultMissionService(scopeA)
            val serviceB = defaultMissionService(scopeB)
            withPublished(rootA, serviceA) { serverA, _, _ ->
                withPublished(rootB, serviceB) { _, _, _ ->
                    val switched = AtomicBoolean()
                    serverA.responseWriter = LocalControlResponseWriter { exchange, status, body ->
                        if (exchange.requestURI.path == LocalControlOperation.BEGIN_MISSION.path && switched.compareAndSet(false, true)) {
                            replacePublishedSnapshot(rootB, rootA)
                            throw IOException("simulated session switch")
                        }
                        DefaultLocalControlResponseWriter.write(exchange, status, body)
                    }
                    val result = runBlocking { client(rootA).use { it.beginMission("Old Session") } }
                    val failure = assertIs<LocalControlClientResult.Failure>(result)
                    assertEquals(LocalControlClientErrorCode.SESSION_CHANGED, failure.error.code)
                    val activeB = runBlocking { serviceB.getActiveMissions(GetActiveMissionsRequest("verify-new")) }
                    assertTrue((assertIs<ControlResult.Success<*>>(activeB).value as List<*>).isEmpty())
                }
            }
            serviceA.close()
            serviceB.close()
            scopeA.cancel()
            scopeB.cancel()
        }
    }
}

private fun client(root: Path, ids: () -> String = { java.util.UUID.randomUUID().toString() }) = LocalControlClient(
    SecureLocalControlDiscoveryReader(root),
    newDirectLocalControlHttpClient(),
    ids,
)

private fun LocalControlClient.searchSystemBlocking() = runBlocking { searchSystem("Jita") }

private fun assertDisconnected(result: LocalControlClientResult) {
    assertEquals(LocalControlClientErrorCode.APP_DISCONNECTED, assertIs<LocalControlClientResult.Failure>(result).error.code)
}

private fun assertSearchName(result: LocalControlClientResult, expected: String) {
    val success = assertIs<LocalControlClientResult.Success>(result)
    val first = assertIs<kotlinx.serialization.json.JsonArray>(success.value).single().jsonObject
    assertEquals(expected, first.getValue("name").jsonPrimitive.content)
}

private fun searchService(name: String) = object : StubMapControlService() {
    override suspend fun searchSystems(request: SearchSystemsRequest) = ControlResult.Success(
        request.requestId,
        listOf(SystemSummaryDto(30000142, name, 10000002, 20000020, 0.9)),
    )
}

private fun defaultMissionService(scope: CoroutineScope): DefaultMapControlService {
    val system = SystemSummaryDto(1, "A", 1, 1, 0.0)
    return DefaultMapControlService(
        systemReadPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = listOf(system)
            override suspend fun getSystemInfo(systemId: Int) =
                if (systemId == 1) SystemInfoDto(system, "R", "C", 0.0, 0.0, 0.0, 0) else null
        },
        routePlanningPort = object : RoutePlanningPort {
            override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
                RouteCalculationOutcome.InvalidEndpoint(emptySet(), startSystemId, destinationSystemId)
            override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) =
                CapitalRouteOutcome.InvalidEndpoint(emptySet())
        },
        jumpPlanningPort = JumpPlanningPort { _, _ -> error("unused") },
        viewportControlPort = object : ViewportControlPort {
            override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED
            override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED
        },
        missionRenderStatePort = MissionRenderStatePort { },
        scope = scope,
    )
}

private inline fun withPublished(
    root: Path,
    service: dev.evestaticmapplanner.control.MapControlService,
    block: (LocalControlServer, ActiveLocalControlDiscovery, LocalControlSessionMetadata) -> Unit,
) {
    val lease = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire()).lease
    val server = LocalControlServer(service, "0.1.2")
    val metadata = server.start()
    lease.publish(server)
    try {
        block(server, lease, metadata)
    } finally {
        runCatching { lease.unpublishDescriptor() }
        runCatching { server.stop() }
        runCatching { lease.removeSessionKey() }
        runCatching { lease.release() }
    }
}

private fun replacePublishedSnapshot(sourceRoot: Path, targetRoot: Path) {
    val keyName = LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME
    val descriptorName = LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME
    Files.write(targetRoot.resolve(keyName), Files.readAllBytes(sourceRoot.resolve(keyName)), StandardOpenOption.TRUNCATE_EXISTING)
    Files.write(
        targetRoot.resolve(descriptorName),
        Files.readAllBytes(sourceRoot.resolve(descriptorName)),
        StandardOpenOption.TRUNCATE_EXISTING,
    )
}

private fun rewriteDescriptor(path: Path, transform: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>) {
    val current = Json.parseToJsonElement(Files.readString(path)).jsonObject
    Files.writeString(path, JsonObject(transform(current)).toString(), StandardOpenOption.TRUNCATE_EXISTING)
}

private fun assertUnavailable(root: Path) {
    assertFailsWith<LocalControlDiscoveryUnavailableException> { SecureLocalControlDiscoveryReader(root).read() }
}

private inline fun withTemporaryRoot(block: (Path) -> Unit) {
    val temporary = createTempDirectory("local-control-client-test-")
    val root = temporary.resolve("EVE Static Map Planner").resolve("control")
    try {
        block(root)
    } finally {
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(temporary).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
