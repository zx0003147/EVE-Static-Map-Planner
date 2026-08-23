package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.transport.LocalControlDiscoveryProtocol
import dev.evestaticmapplanner.control.transport.LocalControlServer
import dev.evestaticmapplanner.control.transport.LocalControlServerState
import dev.evestaticmapplanner.control.transport.LocalControlDiscoveryAcquisition
import dev.evestaticmapplanner.control.transport.SecureLocalControlDiscovery
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AiMapControlLifecycleControllerTest {
    @Test
    fun `enable disable and re-enable rotate all session identity and clear only Mission state`() = withLifecycleRoot { root ->
        runBlocking {
            val missionStore = RecordingMissionStore()
            val harness = SessionHarness(missionStore)
            val controller = controller(root, harness)
            val userNormalRoute = RouteResult(99, 99, listOf(99), emptyList())
            val userCapitalRoute = "user-capital"
            val userJumpRanges = mutableListOf<JumpRangeOverlay>()
            val userMarkers = mutableMapOf(99 to Marker.temporary(99))
            val savedMarkers = mutableMapOf(100 to "saved")

            controller.setEnabled(true)
            assertEquals(AiControlStatus.Listening, controller.status.value)
            val first = harness.sessions.single()
            val firstInstanceId = first.server.sessionMetadata!!.instanceId
            val firstKey = Files.readAllBytes(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME))
            first.service.beginMission(BeginMissionCommand("begin-1", "key-1", "Mission One"))
            assertEquals(1, missionStore.missions.size)

            controller.setEnabled(false)
            assertEquals(AiControlStatus.Disabled, controller.status.value)
            assertEquals(LocalControlServerState.STOPPED, first.server.state)
            assertTrue(first.closed)
            assertTrue(missionStore.missions.isEmpty())
            assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            assertEquals(userNormalRoute, RouteResult(99, 99, listOf(99), emptyList()))
            assertEquals("user-capital", userCapitalRoute)
            assertTrue(userJumpRanges.isEmpty())
            assertEquals(setOf(99), userMarkers.keys)
            assertEquals(mapOf(100 to "saved"), savedMarkers)

            controller.setEnabled(true)
            val second = harness.sessions.last()
            val secondInstanceId = second.server.sessionMetadata!!.instanceId
            val secondKey = Files.readAllBytes(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME))
            assertNotEquals(firstInstanceId, secondInstanceId)
            assertFalse(firstKey.contentEquals(secondKey))
            assertEquals(401, handshakeStatus(second.server, firstKey.toString(Charsets.UTF_8)))
            assertTrue(missionStore.missions.isEmpty())
            val active = second.service.getActiveMissions(GetActiveMissionsRequest("active-2"))
            assertTrue(assertIs<ControlResult.Success<List<MissionSummaryDto>>>(active).value.isEmpty())
            val repeatedKey = second.service.beginMission(BeginMissionCommand("begin-2", "key-1", "Mission Two"))
            assertIs<ControlResult.Success<MissionSummaryDto>>(repeatedKey)

            controller.shutdown()
            assertEquals(AiControlStatus.Disabled, controller.status.value)
            assertEquals(LocalControlServerState.STOPPED, second.server.state)
            assertTrue(second.closed)

            val replacementHarness = SessionHarness(RecordingMissionStore())
            val replacement = controller(root, replacementHarness)
            replacement.setEnabled(true)
            assertEquals(AiControlStatus.Listening, replacement.status.value)
            replacement.shutdown()
            assertEquals(LocalControlServerState.STOPPED, replacementHarness.sessions.single().server.state)
            assertNoPublishedSession(root)
        }
    }

    @Test
    fun `App Exit while disabled prevents late enable and creates no lifecycle resources`() = withLifecycleRoot { root ->
        runBlocking {
            val harness = SessionHarness(RecordingMissionStore())
            val controller = controller(root, harness)

            controller.shutdown()
            controller.shutdown()
            controller.setEnabled(true)

            assertEquals(AiControlStatus.Disabled, controller.status.value)
            assertTrue(harness.sessions.isEmpty())
            assertNoPublishedSession(root)
        }
    }

    @Test
    fun `second lifecycle reports Already Active without starting or touching first session`() = withLifecycleRoot { root ->
        runBlocking {
            val firstHarness = SessionHarness(RecordingMissionStore())
            val secondHarness = SessionHarness(RecordingMissionStore())
            val first = controller(root, firstHarness)
            val second = controller(root, secondHarness)
            first.setEnabled(true)
            val descriptor = root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME)
            val key = root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME)
            val descriptorBefore = Files.readAllBytes(descriptor)
            val keyBefore = Files.readAllBytes(key)

            second.setEnabled(true)
            assertEquals(AiControlStatus.AlreadyActive, second.status.value)
            assertTrue(secondHarness.sessions.isEmpty())
            assertContentEquals(descriptorBefore, Files.readAllBytes(descriptor))
            assertContentEquals(keyBefore, Files.readAllBytes(key))

            first.setEnabled(false)
            second.setEnabled(true)
            assertEquals(AiControlStatus.Listening, second.status.value)
            assertEquals(1, secondHarness.sessions.size)
            second.shutdown()
            first.shutdown()
        }
    }

    @Test
    fun `Starting to Disable is serialized and cannot leave a late server`() = withLifecycleRoot { root ->
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = SessionHarness(RecordingMissionStore())
            val controller = controller(root, harness, startupGate = {
                entered.complete(Unit)
                release.await()
            })

            val enable = async(Dispatchers.Default) { controller.setEnabled(true) }
            entered.await()
            assertEquals(AiControlStatus.Starting, controller.status.value)
            val disable = async(Dispatchers.Default) { controller.setEnabled(false) }
            release.complete(Unit)
            enable.await()
            disable.await()

            assertEquals(AiControlStatus.Disabled, controller.status.value)
            assertEquals(LocalControlServerState.STOPPED, harness.sessions.single().server.state)
            assertTrue(harness.sessions.single().closed)
            assertNoPublishedSession(root)
            controller.shutdown()
        }
    }

    @Test
    fun `Starting to App Exit completes startup only to perform full cleanup`() = withLifecycleRoot { root ->
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = SessionHarness(RecordingMissionStore())
            val controller = controller(root, harness, startupGate = {
                entered.complete(Unit)
                release.await()
            })

            val enable = async(Dispatchers.Default) { controller.setEnabled(true) }
            entered.await()
            val exit = async(Dispatchers.Default) { controller.shutdown() }
            release.complete(Unit)
            enable.await()
            exit.await()

            assertEquals(AiControlStatus.Disabled, controller.status.value)
            assertEquals(LocalControlServerState.STOPPED, harness.sessions.single().server.state)
            assertTrue(harness.sessions.single().closed)
            assertNoPublishedSession(root)
        }
    }

    @Test
    fun `unsafe discovery failure is fail closed with safe UI error and no Control Session`() = withLifecycleRoot { root ->
        runBlocking {
            val bootstrap = assertIs<LocalControlDiscoveryAcquisition.Acquired>(
                SecureLocalControlDiscovery(root).acquire(),
            ).lease
            bootstrap.release()
            Files.createDirectory(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME))
            val harness = SessionHarness(RecordingMissionStore())
            val controller = controller(root, harness)

            controller.setEnabled(true)

            val error = assertIs<AiControlStatus.Error>(controller.status.value)
            assertEquals("ENABLE_FAILED", error.code)
            assertEquals("AI Map Control could not be enabled securely", error.message)
            assertFalse(error.message.contains(root.toString()))
            assertTrue(harness.sessions.isEmpty())
            assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
            controller.shutdown()
        }
    }
}

private fun controller(
    root: Path,
    harness: SessionHarness,
    startupGate: suspend () -> Unit = {},
) = AiMapControlLifecycleController(
    discoveryRoot = root,
    sessionFactory = harness::create,
    ioDispatcher = Dispatchers.IO,
    diagnostics = RecordingLifecycleDiagnostics(),
    startupGate = startupGate,
)

private class SessionHarness(private val missionStore: RecordingMissionStore) {
    val sessions = mutableListOf<SessionRecord>()

    fun create(): AppAiControlSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = DefaultMapControlService(
            systemReadPort = EmptySystemReadPort,
            routePlanningPort = UnusedRoutePlanningPort,
            jumpPlanningPort = JumpPlanningPort { _, _ -> error("unused") },
            viewportControlPort = UnusedViewportPort,
            missionRenderStatePort = missionStore,
            scope = scope,
        )
        val record = SessionRecord(LocalControlServer(service, "0.1.2"), service)
        sessions += record
        return AppAiControlSession(
            server = record.server,
            clearMissionState = { missionStore.publish(emptyList()) },
            closeControlSession = {
                service.close()
                scope.cancel()
                record.closed = true
            },
        )
    }
}

private data class SessionRecord(
    val server: LocalControlServer,
    val service: DefaultMapControlService,
    var closed: Boolean = false,
)

private class RecordingMissionStore : MissionRenderStatePort {
    @Volatile var missions: List<Mission> = emptyList()
    override suspend fun publish(missions: List<Mission>) {
        this.missions = missions
    }
}

private object EmptySystemReadPort : SystemReadPort {
    override suspend fun searchSystems(query: String, limit: Int): List<SystemSummaryDto> = emptyList()
    override suspend fun getSystemInfo(systemId: Int): SystemInfoDto? = null
}

private object UnusedRoutePlanningPort : RoutePlanningPort {
    override suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome = error("unused")

    override suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome = error("unused")
}

private object UnusedViewportPort : ViewportControlPort {
    override suspend fun focusSystem(systemId: Int): ViewportOperationOutcome = ViewportOperationOutcome.APP_NOT_READY
    override suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome = ViewportOperationOutcome.APP_NOT_READY
}

private class RecordingLifecycleDiagnostics : AiControlLifecycleDiagnostics {
    val messages = mutableListOf<String>()
    override fun info(message: String) {
        messages += message
    }
    override fun warning(message: String, failure: Throwable) {
        messages += message
    }
}

private fun assertNoPublishedSession(root: Path) {
    assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.DESCRIPTOR_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(root.resolve(LocalControlDiscoveryProtocol.SESSION_KEY_FILE_NAME), LinkOption.NOFOLLOW_LINKS))
}

private fun handshakeStatus(server: LocalControlServer, secret: String): Int {
    val request = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:${server.port}/v1/handshake"),
    )
        .header("Authorization", "Bearer $secret")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\"old-secret-check\"}"))
        .build()
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

private inline fun withLifecycleRoot(block: (Path) -> Unit) {
    val temporary = createTempDirectory("ai-control-lifecycle-test-")
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
