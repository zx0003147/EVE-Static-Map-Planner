package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.AnyRouteDto
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateWormholeCommand
import dev.evestaticmapplanner.control.CreateWormholeReceipt
import dev.evestaticmapplanner.control.ListWormholesRequest
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.WormholeConnectionDto
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRouteId
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WormholeTransportTest {
    @Test
    fun `list create and Wormhole route fields round trip without protocol upgrade`() {
        val service = RecordingWormholeTransportService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()

            val listed = rawRequest(server, LocalControlOperation.LIST_WORMHOLES.path)
            val created = rawRequest(
                server,
                LocalControlOperation.CREATE_WORMHOLE.path,
                body("""{"requestId":"create","idempotencyKey":"create","fromSystemId":2,"toSystemId":1}"""),
            )

            assertEquals(200, listed.status)
            assertEquals(1, value(listed).jsonArray.size)
            assertEquals("System 1", value(listed).jsonArray.single().jsonObject.getValue("firstSystemName").jsonPrimitive.content)
            assertEquals(200, created.status)
            assertTrue(value(created).jsonObject.getValue("created").jsonPrimitive.boolean)
            assertEquals("created", value(created).jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(2 to 1, service.createdEndpoints)
        }

        assertEquals(1, LocalControlProtocol.PROTOCOL_VERSION)
        assertEquals(3, LocalControlProtocol.CONTROL_API_VERSION)
    }

    @Test
    fun `missing useWormholes defaults false and true is carried for query and Mission command`() {
        val service = RecordingWormholeTransportService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val normalBase = """{"requestId":"normal","startSystemId":1,"destinationSystemId":2,"useAnsiblex":false"""
            val omitted = rawRequest(server, LocalControlOperation.NORMAL_ROUTE.path, body("$normalBase}"))
            val enabled = rawRequest(
                server,
                LocalControlOperation.NORMAL_ROUTE.path,
                body("${normalBase.dropLast(0)},\"useWormholes\":true}"),
            )
            val showBase = """{"requestId":"show","idempotencyKey":"show","missionId":"mission-1","startSystemId":1,"destinationSystemId":2,"useAnsiblex":false"""
            val showOmitted = rawRequest(server, LocalControlOperation.SHOW_NORMAL_ROUTE.path, body("$showBase}"))
            val showEnabled = rawRequest(
                server,
                LocalControlOperation.SHOW_NORMAL_ROUTE.path,
                body("$showBase,\"useWormholes\":true}".replace("\"requestId\":\"show\"", "\"requestId\":\"show-2\"").replace("\"idempotencyKey\":\"show\"", "\"idempotencyKey\":\"show-2\"")),
            )

            assertEquals(listOf(false, true), service.queryFlags)
            assertEquals(listOf(false, true), service.missionFlags)
            assertEquals(0, value(omitted).jsonObject.getValue("wormholeJumps").jsonPrimitive.int)
            assertEquals(1, value(enabled).jsonObject.getValue("wormholeJumps").jsonPrimitive.int)
            assertFalse(value(showOmitted).jsonObject.getValue("route").jsonObject.getValue("wormholeJumps").jsonPrimitive.int > 0)
            assertEquals(1, value(showEnabled).jsonObject.getValue("route").jsonObject.getValue("wormholeJumps").jsonPrimitive.int)
        }
    }
}

private class RecordingWormholeTransportService : StubMapControlService() {
    val queryFlags = mutableListOf<Boolean>()
    val missionFlags = mutableListOf<Boolean>()
    var createdEndpoints: Pair<Int, Int>? = null
    private val connection = WormholeConnectionDto("wormhole:1:2", 1, 2, "System 1", "System 2")

    override suspend fun listWormholes(request: ListWormholesRequest) =
        ControlResult.Success(request.requestId, listOf(connection))

    override suspend fun createWormhole(command: CreateWormholeCommand): ControlResult<CreateWormholeReceipt> {
        createdEndpoints = command.fromSystemId to command.toSystemId
        return ControlResult.Success(command.requestId, CreateWormholeReceipt(connection, true, "created"))
    }

    override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest): ControlResult<NormalRouteDto> {
        queryFlags += request.useWormholes
        return ControlResult.Success(request.requestId, route(request.useWormholes))
    }

    override suspend fun showNormalRoute(command: ShowNormalRouteCommand): ControlResult<MissionRouteReceipt> {
        missionFlags += command.useWormholes
        return ControlResult.Success(
            command.requestId,
            MissionRouteReceipt(command.missionId, MissionRouteId("route-${missionFlags.size}"), AnyRouteDto.Normal(route(command.useWormholes))),
        )
    }

    private fun route(useWormholes: Boolean) = NormalRouteDto(
        startSystemId = 1,
        destinationSystemId = 2,
        systemIds = listOf(1, 2),
        totalJumps = 1,
        stargateJumps = if (useWormholes) 0 else 1,
        ansiblexJumps = 0,
        wormholeJumps = if (useWormholes) 1 else 0,
    )
}

private fun body(value: String) = HttpRequest.BodyPublishers.ofString(value)

private fun value(response: TestHttpResponse) = Json.parseToJsonElement(response.body)
    .jsonObject.getValue("value")
