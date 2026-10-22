package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CurrentNavigationConnectionDto
import dev.evestaticmapplanner.control.CurrentNavigationConnectionType
import dev.evestaticmapplanner.control.CurrentNavigationRouteDto
import dev.evestaticmapplanner.control.CurrentNavigationRouteStatus
import dev.evestaticmapplanner.control.CurrentNavigationRouteType
import dev.evestaticmapplanner.control.CurrentNavigationRoutesDto
import dev.evestaticmapplanner.control.CurrentNavigationSecurityBand
import dev.evestaticmapplanner.control.CurrentNavigationSystemDto
import dev.evestaticmapplanner.control.GetCurrentNavigationRoutesRequest
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentNavigationRoutesTransportTest {
    @Test
    fun `current manual route query remains read only and preserves ordered route data`() {
        val service = RecordingCurrentNavigationRoutesService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val response = rawRequest(
                server,
                LocalControlOperation.CURRENT_NAVIGATION_ROUTES.path,
                HttpRequest.BodyPublishers.ofString("""{"requestId":"current-routes-1"}"""),
            )

            assertEquals(200, response.status)
            assertEquals("current-routes-1", service.request?.requestId)
            val value = Json.parseToJsonElement(response.body).jsonObject.getValue("value").jsonObject
            val normal = value.getValue("normal").jsonObject
            assertEquals("NORMAL", normal.getValue("routeType").jsonPrimitive.content)
            assertEquals(true, normal.getValue("valid").jsonPrimitive.boolean)
            assertEquals(1, normal.getValue("totalJumps").jsonPrimitive.int)
            assertEquals(
                listOf(30_000_001, 30_000_002),
                normal.getValue("systems").jsonArray.map { it.jsonObject.getValue("systemId").jsonPrimitive.int },
            )
            assertEquals(
                "ANSIBLEX",
                normal.getValue("connections").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }
}

private class RecordingCurrentNavigationRoutesService : StubMapControlService() {
    var request: GetCurrentNavigationRoutesRequest? = null

    override suspend fun getCurrentNavigationRoutes(
        request: GetCurrentNavigationRoutesRequest,
    ): ControlResult<CurrentNavigationRoutesDto> {
        this.request = request
        return ControlResult.Success(request.requestId, CURRENT_ROUTES)
    }
}

private val CURRENT_ROUTES = CurrentNavigationRoutesDto(
    normal = CurrentNavigationRouteDto(
        routeType = CurrentNavigationRouteType.NORMAL,
        status = CurrentNavigationRouteStatus.CALCULATED,
        valid = true,
        stale = false,
        startSystemId = 30_000_001,
        destinationSystemId = 30_000_002,
        systems = listOf(
            currentSystem(0, 30_000_001, "A"),
            currentSystem(1, 30_000_002, "B"),
        ),
        connections = listOf(
            CurrentNavigationConnectionDto(
                routeIndex = 0,
                fromSystemId = 30_000_001,
                toSystemId = 30_000_002,
                type = CurrentNavigationConnectionType.ANSIBLEX,
            ),
        ),
    ),
    capital = CurrentNavigationRouteDto(
        routeType = CurrentNavigationRouteType.CAPITAL,
        status = CurrentNavigationRouteStatus.NOT_PLANNED,
        valid = false,
        stale = false,
        unavailableReason = "NO_CALCULATED_ROUTE",
    ),
)

private fun currentSystem(index: Int, id: Int, name: String) = CurrentNavigationSystemDto(
    routeIndex = index,
    systemId = id,
    name = name,
    securityStatus = 0.2,
    spaceClassification = "NEW_EDEN",
    securityBand = CurrentNavigationSecurityBand.LOW_SECURITY,
)
