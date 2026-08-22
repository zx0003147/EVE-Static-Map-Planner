package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.RouteConnectionId
import dev.evestaticmapplanner.core.route.RouteEdge
import dev.evestaticmapplanner.core.route.RouteEdgeId
import dev.evestaticmapplanner.core.route.RouteEdgeType
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalControlTimeoutAndDisconnectTest {
    @Test
    fun `query and expensive route operations use distinct bounded timeouts`() {
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>> {
                delay(100)
                return ControlResult.Success(request.requestId, emptyList())
            }

            override suspend fun calculateNormalRoute(request: dev.evestaticmapplanner.control.CalculateNormalRouteRequest): ControlResult<NormalRouteDto> {
                delay(100)
                return ControlResult.Success(request.requestId, NormalRouteDto(1, 2, listOf(1, 2), 1, 1, 0))
            }

            override suspend fun calculateCapitalRoute(request: dev.evestaticmapplanner.control.CalculateCapitalRouteRequest): ControlResult<CapitalRouteDto> {
                delay(100)
                return denied(request.requestId)
            }
        }
        val server = LocalControlServer(
            service,
            "0.1.2",
            timeouts = LocalControlTimeouts(Duration.ofMillis(20), Duration.ofMillis(30), Duration.ofMillis(40)),
        )
        server.start()
        try {
            val query = LocalControlTestClient(server).search()
            assertEquals(504, query.status)
            assertTrue(query.body.contains("TIMEOUT"))

            val route = rawRequest(
                server,
                LocalControlOperation.NORMAL_ROUTE.path,
                java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"requestId\":\"route-query-1\",\"startSystemId\":1,\"destinationSystemId\":2,\"useAnsiblex\":false}",
                ),
            )
            assertEquals(504, route.status)
            assertTrue(route.body.contains("TIMEOUT"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `timed out mutation finishes once and same idempotency key replays original result`() {
        val routeCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = defaultService(scope, routeCalls, routeDelayMillis = 150)
        val server = LocalControlServer(
            service,
            "0.1.2",
            timeouts = LocalControlTimeouts(Duration.ofMillis(500), Duration.ofMillis(40), Duration.ofMillis(40)),
        )
        server.start()
        try {
            val client = LocalControlTestClient(server)
            val begin = client.beginMission("begin-1", "begin-key", "Timeout Mission")
            assertEquals(200, begin.status)
            val missionId = requireNotNull(Regex("\\\"missionId\\\":\\\"([^\\\"]+)\\\"").find(begin.body)?.groupValues?.get(1))

            val timedOut = client.showNormalRoute("route-timeout-1", "route-key", missionId)
            assertEquals(504, timedOut.status)
            assertTrue(timedOut.body.contains("route-timeout-1"))

            val completedMission = runBlocking {
                repeat(100) {
                    val result = service.getMission(GetMissionRequest("poll-$it", MissionId(missionId)))
                    val mission = (result as? ControlResult.Success)?.value
                    if (mission != null && mission.routes.isNotEmpty()) return@runBlocking mission
                    delay(10)
                }
                error("Timed-out mutation did not finish")
            }
            assertEquals(1, completedMission.routes.size)
            val committedRouteId = completedMission.routes.single().routeId.value

            val replay = client.showNormalRoute("route-retry-1", "route-key", missionId)
            assertEquals(200, replay.status)
            assertTrue(replay.body.contains(committedRouteId))
            assertTrue(replay.body.contains("route-timeout-1"))
            assertEquals(1, routeCalls.get())

            val finalMission = runBlocking {
                assertIs<ControlResult.Success<*>>(service.getMission(GetMissionRequest("final-1", MissionId(missionId)))).value
                    as dev.evestaticmapplanner.control.mission.Mission
            }
            assertEquals(1, finalMission.routes.size)
        } finally {
            server.stop()
            service.close()
            scope.cancel()
        }
    }

    @Test
    fun `response delivery failure does not undo mutation and retry remains idempotent`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = defaultService(scope, AtomicInteger(), routeDelayMillis = 0)
        val audits = Collections.synchronizedList(mutableListOf<LocalControlAuditEvent>())
        val server = LocalControlServer(service, "0.1.2", auditSink = LocalControlAuditSink(audits::add))
        val failFirstWrite = AtomicBoolean(true)
        server.responseWriter = LocalControlResponseWriter { exchange, status, body ->
            if (failFirstWrite.compareAndSet(true, false)) throw IOException("simulated disconnect")
            DefaultLocalControlResponseWriter.write(exchange, status, body)
        }
        server.start()
        try {
            val client = LocalControlTestClient(server)
            assertFailsWith<IOException> {
                client.beginMission("disconnect-1", "disconnect-key", "Committed Mission")
            }

            val active = runBlocking { service.getActiveMissions(GetActiveMissionsRequest("after-disconnect")) }
            assertEquals(1, assertIs<ControlResult.Success<*>>(active).value.let { it as List<*> }.size)

            val replay = client.beginMission("disconnect-retry", "disconnect-key", "Committed Mission")
            assertEquals(200, replay.status)
            assertTrue(replay.body.contains("disconnect-1"))
            val stillActive = runBlocking { service.getActiveMissions(GetActiveMissionsRequest("after-retry")) }
            assertEquals(1, assertIs<ControlResult.Success<*>>(stillActive).value.let { it as List<*> }.size)
            assertTrue(audits.any { it.operation == "BEGIN_MISSION" && !it.responseDelivered })
        } finally {
            server.stop()
            service.close()
            scope.cancel()
        }
    }

    private fun defaultService(
        scope: CoroutineScope,
        routeCalls: AtomicInteger,
        routeDelayMillis: Long,
    ): DefaultMapControlService {
        val system = SystemSummaryDto(1, "A", 1, 1, 0.0)
        val system2 = SystemSummaryDto(2, "B", 1, 1, 0.0)
        val systemPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = listOf(system, system2)
            override suspend fun getSystemInfo(systemId: Int): SystemInfoDto? = when (systemId) {
                1 -> SystemInfoDto(system, "R", "C", 0.0, 0.0, 0.0, 1)
                2 -> SystemInfoDto(system2, "R", "C", 1.0, 0.0, 0.0, 1)
                else -> null
            }
        }
        val routePort = object : RoutePlanningPort {
            override suspend fun calculateNormalRoute(
                startSystemId: Int,
                destinationSystemId: Int,
                useAnsiblex: Boolean,
            ): RouteCalculationOutcome {
                routeCalls.incrementAndGet()
                if (routeDelayMillis > 0) delay(routeDelayMillis)
                val edge = RouteEdge(
                    RouteEdgeId("gate:1:2"),
                    RouteConnectionId("gate:1:2"),
                    1,
                    2,
                    RouteEdgeType.STARGATE,
                )
                return RouteCalculationOutcome.Found(RouteResult(1, 2, listOf(1, 2), listOf(edge)))
            }

            override suspend fun calculateCapitalRoute(
                startSystemId: Int,
                destinationSystemId: Int,
                effectiveRangeLy: Double,
            ): CapitalRouteOutcome = CapitalRouteOutcome.InvalidEndpoint(emptySet())
        }
        return DefaultMapControlService(
            systemReadPort = systemPort,
            routePlanningPort = routePort,
            jumpPlanningPort = JumpPlanningPort { _, _ -> error("unused") },
            viewportControlPort = object : ViewportControlPort {
                override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED
                override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED
            },
            missionRenderStatePort = MissionRenderStatePort { },
            scope = scope,
        )
    }
}
