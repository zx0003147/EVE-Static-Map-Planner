package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.AnyRouteDto
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.CapitalRouteLegDto
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.ShowCapitalRouteCommand
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRouteId
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaypointTransportTest {
    @Test
    fun `codec accepts ordered Waypoints and omitted destination for query and Mission command`() {
        val service = RecordingWaypointTransportService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val normal = rawRequest(
                server,
                LocalControlOperation.NORMAL_ROUTE.path,
                waypointBody(
                    """{"requestId":"normal","startSystemId":1,"waypointSystemIds":[2,3],"useAnsiblex":false}""",
                ),
            )
            val capital = rawRequest(
                server,
                LocalControlOperation.SHOW_CAPITAL_ROUTE.path,
                waypointBody(
                    """{"requestId":"capital","idempotencyKey":"capital","missionId":"mission-1","startSystemId":4,"waypointSystemIds":[5,6],"effectiveRangeLy":5.0}""",
                ),
            )

            assertEquals(200, normal.status)
            assertEquals(listOf(2, 3), service.normalRequest?.waypointSystemIds)
            assertEquals(null, service.normalRequest?.destinationSystemId)
            assertEquals(listOf(5, 6), service.capitalCommand?.waypointSystemIds)
            assertEquals(null, service.capitalCommand?.destinationSystemId)

            val normalValue = waypointValue(normal).jsonObject
            assertEquals(listOf("2", "3"), normalValue.getValue("waypointSystemIds").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(normalValue.getValue("explicitDestinationSystemId").toString() == "null")
            val capitalRoute = waypointValue(capital).jsonObject.getValue("route").jsonObject
            assertEquals(listOf("5", "6"), capitalRoute.getValue("waypointSystemIds").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(capitalRoute.getValue("explicitDestinationSystemId").toString() == "null")
        }
    }
}

private class RecordingWaypointTransportService : StubMapControlService() {
    var normalRequest: CalculateNormalRouteRequest? = null
    var capitalCommand: ShowCapitalRouteCommand? = null

    override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto> {
        normalRequest = request
        return ControlResult.Success(
            request.requestId,
            NormalRouteDto(1, 3, listOf(1, 2, 3), 2, 2, 0, 0, request.waypointSystemIds, null),
        )
    }

    override suspend fun showCapitalRoute(command: ShowCapitalRouteCommand): ControlResult<MissionRouteReceipt> {
        capitalCommand = command
        val route = CapitalRouteDto(
            startSystemId = 4,
            destinationSystemId = 6,
            effectiveRangeLy = command.effectiveRangeLy,
            systemIds = listOf(4, 5, 6),
            legs = listOf(CapitalRouteLegDto(4, 5, 1.0), CapitalRouteLegDto(5, 6, 1.0)),
            totalJumps = 2,
            totalDistanceLy = 2.0,
            waypointSystemIds = command.waypointSystemIds,
            explicitDestinationSystemId = null,
        )
        return ControlResult.Success(
            command.requestId,
            MissionRouteReceipt(MissionId("mission-1"), MissionRouteId("route-1"), AnyRouteDto.Capital(route)),
        )
    }
}

private fun waypointBody(value: String) = HttpRequest.BodyPublishers.ofString(value)

private fun waypointValue(response: TestHttpResponse) = Json.parseToJsonElement(response.body)
    .jsonObject.getValue("value")
