package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemMarkerControlServiceTest {
    @Test
    fun `query aggregates saved marker and every matching Mission marker`() = runTest {
        val saved = savedMarker(createdBy = SavedMarkerCreatedBy.USER)
        val port = FakeSavedMarkerControlPort(saved)
        service(port).use { service ->
            val first = service.beginMission(BeginMissionCommand("begin-1", "begin-1", "First")).success().value
            val second = service.beginMission(BeginMissionCommand("begin-2", "begin-2", "Second")).success().value
            service.addMissionMarker(
                AddMissionMarkerCommand("add-1", "add-1", first.missionId, 1, MissionMarkerRole.RALLY, "Rally", "Line 1\nLine 2"),
            ).success()
            service.addMissionMarker(
                AddMissionMarkerCommand("add-2", "add-2", second.missionId, 1, MissionMarkerRole.DANGER, colorOverride = MarkerColor.PURPLE),
            ).success()
            service.addMissionMarker(
                AddMissionMarkerCommand("other", "other", first.missionId, 2, MissionMarkerRole.INFO),
            ).success()

            val result = service.getSystemMarkers(GetSystemMarkersRequest("query", 1)).success().value

            assertEquals(saved, result.savedMarker)
            assertEquals(2, result.missionMarkers.size)
            assertEquals(listOf(MissionMarkerRole.RALLY, MissionMarkerRole.DANGER), result.missionMarkers.map { it.role })
            assertEquals(listOf(MarkerColor.GREEN, MarkerColor.PURPLE), result.missionMarkers.map { it.color })
            assertEquals("Line 1\nLine 2", result.missionMarkers.first().notes)
        }
    }

    @Test
    fun `query covers saved only Mission only and neither without conflating empty with denied`() = runTest {
        val port = FakeSavedMarkerControlPort(savedMarker())
        service(port).use { service ->
            val savedOnly = service.getSystemMarkers(GetSystemMarkersRequest("saved", 1)).success().value
            assertTrue(savedOnly.savedMarker != null && savedOnly.missionMarkers.isEmpty())

            port.marker = null
            val mission = service.beginMission(BeginMissionCommand("begin", "begin", "Mission")).success().value
            service.addMissionMarker(
                AddMissionMarkerCommand("add", "add", mission.missionId, 1, MissionMarkerRole.INFO),
            ).success()
            val missionOnly = service.getSystemMarkers(GetSystemMarkersRequest("mission", 1)).success().value
            assertNull(missionOnly.savedMarker)
            assertEquals(1, missionOnly.missionMarkers.size)

            service.clearMissionMarkers(ClearMissionMarkersCommand("clear", "clear", mission.missionId)).success()
            val neither = service.getSystemMarkers(GetSystemMarkersRequest("neither", 1)).success().value
            assertNull(neither.savedMarker)
            assertTrue(neither.missionMarkers.isEmpty())

            port.failure = ControlErrorCode.CAPABILITY_DENIED
            assertEquals(
                ControlErrorCode.CAPABILITY_DENIED,
                service.getSystemMarkers(GetSystemMarkersRequest("denied", 1)).failure().error.code,
            )
        }
    }

    @Test
    fun `Mission cleanup removes transient Mission marker while saved marker remains`() = runTest {
        val saved = savedMarker()
        service(FakeSavedMarkerControlPort(saved)).use { service ->
            val mission = service.beginMission(BeginMissionCommand("begin", "begin", "Mission")).success().value
            service.addMissionMarker(
                AddMissionMarkerCommand("add", "add", mission.missionId, 1, MissionMarkerRole.WAYPOINT),
            ).success()
            val before = service.getSystemMarkers(GetSystemMarkersRequest("before", 1)).success().value
            assertTrue(before.savedMarker != null && before.missionMarkers.size == 1)

            service.clearMission(ClearMissionCommand("end", "end", mission.missionId)).success()
            val after = service.getSystemMarkers(GetSystemMarkersRequest("after", 1)).success().value
            assertEquals(saved, after.savedMarker)
            assertTrue(after.missionMarkers.isEmpty())
        }
    }

    @Test
    fun `create uses marker port idempotency and stable marker errors`() = runTest {
        val port = FakeSavedMarkerControlPort()
        service(port).use { service ->
            val command = CreateSavedMarkerCommand(
                "create",
                "same",
                1,
                "  Home  ",
                "Notes\nremain",
                MarkerColor.BLUE,
                listOf(SavedMarkerChildType.STAGING, SavedMarkerChildType.STRATEGIC),
            )
            val created = service.createSavedMarker(command).success()
            assertEquals(SavedMarkerCreatedBy.AI, created.value.marker.createdBy)
            assertEquals(listOf("staging", "strategic"), created.value.marker.children.map { it.type })
            assertEquals(command.tags, port.lastRequest?.tags)
            assertEquals(1, port.createCalls)

            assertEquals(created, service.createSavedMarker(command.copy(requestId = "retry")).success())
            assertEquals(1, port.createCalls)

            val conflict = service.createSavedMarker(command.copy(requestId = "changed", name = "Other"))
                .failure()
            assertEquals(ControlErrorCode.IDEMPOTENCY_CONFLICT, conflict.error.code)

            val duplicate = service.createSavedMarker(
                command.copy(requestId = "duplicate", idempotencyKey = "duplicate"),
            ).failure()
            assertEquals(ControlErrorCode.MARKER_ALREADY_EXISTS, duplicate.error.code)

            port.failure = ControlErrorCode.CAPABILITY_DENIED
            val denied = service.createSavedMarker(
                command.copy(requestId = "denied", idempotencyKey = "denied", systemId = 2),
            ).failure()
            assertEquals(ControlErrorCode.CAPABILITY_DENIED, denied.error.code)
        }
    }

    @Test
    fun `invalid system is distinct from valid system with no markers`() = runTest {
        val port = FakeSavedMarkerControlPort()
        service(port).use { service ->
            assertEquals(
                ControlErrorCode.INVALID_ARGUMENT,
                service.getSystemMarkers(GetSystemMarkersRequest("malformed", 0)).failure().error.code,
            )
            port.failure = ControlErrorCode.SYSTEM_NOT_FOUND
            assertEquals(
                ControlErrorCode.SYSTEM_NOT_FOUND,
                service.getSystemMarkers(GetSystemMarkersRequest("missing", 99)).failure().error.code,
            )
        }
    }
}

private class FakeSavedMarkerControlPort(
    var marker: SavedMarkerSummaryDto? = null,
) : SavedMarkerControlPort {
    var failure: ControlErrorCode? = null
    var createCalls = 0
    var lastRequest: SavedMarkerCreatePortRequest? = null

    override suspend fun getSystemMarker(systemId: Int): SavedMarkerSummaryDto? {
        failure?.let { throw ControlPortFailure(it, it.name) }
        return marker?.takeIf { it.systemId == systemId }
    }

    override suspend fun createSavedMarker(request: SavedMarkerCreatePortRequest): SavedMarkerSummaryDto {
        createCalls++
        lastRequest = request
        failure?.let { throw ControlPortFailure(it, it.name) }
        if (marker?.systemId == request.systemId) {
            throw ControlPortFailure(ControlErrorCode.MARKER_ALREADY_EXISTS, "duplicate")
        }
        return savedMarker(
            systemId = request.systemId,
            name = request.name?.trim(),
            notes = request.notes,
            color = request.color,
            createdBy = SavedMarkerCreatedBy.AI,
            children = request.tags.mapIndexed { index, type ->
                SavedMarkerChildSummaryDto("child-$index", type.key, index)
            },
        ).also { marker = it }
    }
}

private fun service(savedMarkerPort: SavedMarkerControlPort): DefaultMapControlService {
    val systems = (1..2).associateWith { id -> SystemSummaryDto(id, "System $id", 1, 1, 0.0) }
    return DefaultMapControlService(
        systemReadPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = systems.values.toList()
            override suspend fun getSystemInfo(systemId: Int) = systems[systemId]?.let {
                SystemInfoDto(it, "Region", "Constellation", 0.0, 0.0, 0.0, 0)
            }
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
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        savedMarkerControlPort = savedMarkerPort,
    )
}

private fun savedMarker(
    systemId: Int = 1,
    name: String? = "Saved",
    notes: String? = "Persistent notes",
    color: MarkerColor = MarkerColor.YELLOW,
    createdBy: SavedMarkerCreatedBy = SavedMarkerCreatedBy.AI,
    children: List<SavedMarkerChildSummaryDto> = listOf(SavedMarkerChildSummaryDto("child", "staging", 0)),
) = SavedMarkerSummaryDto(
    systemId,
    name,
    color,
    notes,
    children,
    createdBy,
)

private fun <T> ControlResult<T>.success(): ControlResult.Success<T> = assertIs(this)
private fun ControlResult<*>.failure(): ControlResult.Failure = assertIs(this)
