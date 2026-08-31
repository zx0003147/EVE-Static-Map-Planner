package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.core.jump.EligibilityVerdict
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeResult
import dev.evestaticmapplanner.core.jump.PositionQueryStrategy
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WormholeControlServiceTest {
    @Test
    fun `list and create are global deterministic and duplicate-safe without UI side effects`() = runTest {
        val fixture = WormholeFixture(this)
        try {
            assertTrue(fixture.service.listWormholes(ListWormholesRequest("empty")).success().value.isEmpty())

            val created = fixture.service.createWormhole(
                CreateWormholeCommand("create", "create", 2, 1),
            ).success().value
            val duplicate = fixture.service.createWormhole(
                CreateWormholeCommand("duplicate", "duplicate", 1, 2),
            ).success().value
            fixture.service.createWormhole(CreateWormholeCommand("second", "second", 1, 3)).success()

            assertTrue(created.created)
            assertEquals("created", created.status)
            assertEquals(1, created.connection.firstSystemId)
            assertEquals("System 1", created.connection.firstSystemName)
            assertFalse(duplicate.created)
            assertEquals("already_exists", duplicate.status)
            assertEquals(created.connection, duplicate.connection)
            assertEquals(
                listOf("wormhole:1:2", "wormhole:1:3"),
                fixture.service.listWormholes(ListWormholesRequest("list")).success().value.map { it.connectionId },
            )
            assertEquals(0, fixture.routeCalls)
            assertTrue(fixture.viewport.focused.isEmpty())
            assertTrue(fixture.viewport.fitted.isEmpty())
            assertTrue(fixture.rendered.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `create rejects self loop and unknown endpoints without mutating Store`() = runTest {
        val fixture = WormholeFixture(this)
        try {
            val selfLoop = fixture.service.createWormhole(
                CreateWormholeCommand("self", "self", 1, 1),
            ).failure()
            val missingFirst = fixture.service.createWormhole(
                CreateWormholeCommand("first", "first", 99, 1),
            ).failure()
            val missingSecond = fixture.service.createWormhole(
                CreateWormholeCommand("second", "second", 1, 99),
            ).failure()

            assertEquals(ControlErrorCode.INVALID_ARGUMENT, selfLoop.error.code)
            assertEquals(ControlErrorCode.SYSTEM_NOT_FOUND, missingFirst.error.code)
            assertEquals(ControlErrorCode.SYSTEM_NOT_FOUND, missingSecond.error.code)
            assertTrue(fixture.wormholes.values.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Wormhole Control port exposes read and create only`() {
        val methods = WormholeControlPort::class.java.declaredMethods
            .filterNot { it.isSynthetic || '$' in it.name }
            .map { it.name }
            .toSet()
        assertEquals(setOf("listWormholes", "createWormhole"), methods)
    }
}

private class WormholeFixture(scope: kotlinx.coroutines.CoroutineScope) {
    val wormholes = FakeWormholePort()
    val viewport = RecordingViewportPort()
    var rendered: List<Mission> = emptyList()
    var routeCalls = 0
    val service = DefaultMapControlService(
        systemReadPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = emptyList<SystemSummaryDto>()
            override suspend fun getSystemInfo(systemId: Int) = systemId.takeIf { it in 1..3 }?.let { id ->
                SystemInfoDto(SystemSummaryDto(id, "System $id", 10, 20, 0.0), "Region", "Constellation", 0.0, 0.0, 0.0, 0)
            }
        },
        routePlanningPort = object : RoutePlanningPort {
            override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
                RouteCalculationOutcome.Unreachable(startSystemId, destinationSystemId).also { routeCalls++ }
            override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) =
                CapitalRouteOutcome.Unreachable(startSystemId, destinationSystemId)
        },
        jumpPlanningPort = JumpPlanningPort { origin, range ->
            JumpRangeResult(origin, JumpProfile.manual(range), emptySet(), EligibilityVerdict.Eligible, PositionQueryStrategy.LINEAR_SCAN)
        },
        viewportControlPort = viewport,
        missionRenderStatePort = MissionRenderStatePort { rendered = it },
        wormholeControlPort = wormholes,
        scope = scope,
    )

    fun close() = service.close()
}

private class FakeWormholePort : WormholeControlPort {
    val values = linkedMapOf<String, WormholeConnectionDto>()

    override suspend fun listWormholes(): List<WormholeConnectionDto> = values.values.reversed()

    override suspend fun createWormhole(fromSystemId: Int, toSystemId: Int): WormholeCreatePortResult {
        val first = minOf(fromSystemId, toSystemId)
        val second = maxOf(fromSystemId, toSystemId)
        val connection = WormholeConnectionDto("wormhole:$first:$second", first, second)
        val status = if (values.putIfAbsent(connection.connectionId, connection) == null) {
            WormholeCreateStatus.CREATED
        } else {
            WormholeCreateStatus.ALREADY_EXISTS
        }
        return WormholeCreatePortResult(connection, status)
    }
}

private class RecordingViewportPort : ViewportControlPort {
    val focused = mutableListOf<Int>()
    val fitted = mutableListOf<Set<Int>>()
    override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED.also { focused += systemId }
    override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED.also { fitted += systemIds }
}

private fun <T> ControlResult<T>.success(): ControlResult.Success<T> = assertIs(this)
private fun ControlResult<*>.failure(): ControlResult.Failure = assertIs(this)
