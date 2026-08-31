package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.MissionRoute
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiWormholeControlIntegrationTest {
    @Test
    fun `AI create feeds Control and Mission routes from global Store without automatic lifecycle changes`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = WormholeSessionStore()
        val systems = listOf(system(1, "One"), system(2, "Two"))
        val planning = ExistingPlanningPorts(
            staticMapRepository = StaticMapRepository { StaticMapData(systems, emptyList()) },
            ansiblexRepository = null,
            wormholeSessionStore = store,
            ioDispatcher = dispatcher,
            calculationDispatcher = dispatcher,
        )
        val viewport = IntegrationViewportPort()
        var renderedMissionCount = 0
        val service = DefaultMapControlService(
            systemReadPort = object : SystemReadPort {
                override suspend fun searchSystems(query: String, limit: Int) = systems.map { it.toSummary() }
                override suspend fun getSystemInfo(systemId: Int) = systems.firstOrNull { it.id == systemId }?.let {
                    SystemInfoDto(it.toSummary(), "Region", "Constellation", it.position.x, it.position.y, it.position.z, 0)
                }
            },
            routePlanningPort = planning,
            jumpPlanningPort = planning,
            viewportControlPort = viewport,
            missionRenderStatePort = MissionRenderStatePort { renderedMissionCount = it.size },
            wormholeControlPort = AppWormholeControlAdapter(store),
            scope = this,
        )
        try {
            val created = service.createWormhole(CreateWormholeCommand("create", "create", 1, 2)).success().value
            assertTrue(created.created)
            assertTrue(viewport.focused.isEmpty() && viewport.fitted.isEmpty())
            assertEquals(0, renderedMissionCount)

            assertEquals(
                ControlErrorCode.ROUTE_NOT_FOUND,
                service.calculateNormalRoute(CalculateNormalRouteRequest("off", 1, 2, false)).failure().error.code,
            )
            val route = service.calculateNormalRoute(
                CalculateNormalRouteRequest("on", 1, 2, false, useWormholes = true),
            ).success().value
            assertEquals(1, route.totalJumps)
            assertEquals(1, route.wormholeJumps)

            val missionId = service.beginMission(BeginMissionCommand("begin", "begin", "Wormhole route"))
                .success().value.missionId
            service.showNormalRoute(
                ShowNormalRouteCommand("show", "show", missionId, 1, 2, false, useWormholes = true),
            ).success()
            val beforeRemoval = service.getMission(GetMissionRequest("before", missionId)).success().value
            val missionRoute = assertIs<MissionRoute.Normal>(beforeRemoval.routes.single())
            assertEquals(1, missionRoute.route.wormholeJumps)

            store.remove("wormhole:1:2")
            val existingSnapshot = service.getMission(GetMissionRequest("after", missionId)).success().value
            assertEquals(beforeRemoval.routes, existingSnapshot.routes)
            assertEquals(
                ControlErrorCode.ROUTE_NOT_FOUND,
                service.calculateNormalRoute(
                    CalculateNormalRouteRequest("new", 1, 2, false, useWormholes = true),
                ).failure().error.code,
            )
        } finally {
            service.close()
        }
    }
}

private class IntegrationViewportPort : ViewportControlPort {
    val focused = mutableListOf<Int>()
    val fitted = mutableListOf<Set<Int>>()
    override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED.also { focused += systemId }
    override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED.also { fitted += systemIds }
}

private fun system(id: Int, name: String) = SolarSystem(
    id = id,
    constellationId = 20,
    regionId = 10,
    name = name,
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = null,
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)

private fun SolarSystem.toSummary() = SystemSummaryDto(id, name, regionId, constellationId, securityStatus)
private fun <T> ControlResult<T>.success(): ControlResult.Success<T> = assertIs(this)
private fun ControlResult<*>.failure(): ControlResult.Failure = assertIs(this)
