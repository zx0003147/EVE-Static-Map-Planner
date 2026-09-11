package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.GetNormalRouteGraphRequest
import dev.evestaticmapplanner.control.NormalRouteGraphEdgeDto
import dev.evestaticmapplanner.control.NormalRouteGraphEdgeType
import dev.evestaticmapplanner.control.NormalRouteGraphNodeDto
import dev.evestaticmapplanner.control.NormalRouteGraphProjection
import dev.evestaticmapplanner.control.NormalRouteGraphSnapshotDto
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NormalRouteGraphTransportTest {
    @Test
    fun `graph snapshot request and nullable coordinates serialize without changing their semantics`() {
        val service = RecordingNormalRouteGraphService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val response = rawRequest(
                server,
                LocalControlOperation.NORMAL_ROUTE_GRAPH.path,
                HttpRequest.BodyPublishers.ofString(
                    """{"requestId":"graph-1","useAnsiblex":true}""",
                ),
            )

            assertEquals(200, response.status)
            assertEquals(true, service.request?.useAnsiblex)
            val value = Json.parseToJsonElement(response.body).jsonObject.getValue("value").jsonObject
            assertEquals(1, value.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals("OFFICIAL_2D", value.getValue("projection").jsonPrimitive.content)
            assertEquals(true, value.getValue("useAnsiblex").jsonPrimitive.boolean)
            val node = value.getValue("nodes").jsonArray.single().jsonObject
            assertEquals(30_000_001, node.getValue("systemId").jsonPrimitive.int)
            assertEquals(JsonNull, node.getValue("official2dX"))
            assertEquals(JsonNull, node.getValue("official2dY"))
            val edge = value.getValue("edges").jsonArray.single().jsonObject
            assertEquals(30_000_001, edge.getValue("fromSystemId").jsonPrimitive.int)
            assertEquals(30_000_002, edge.getValue("toSystemId").jsonPrimitive.int)
            assertEquals("ANSIBLEX", edge.getValue("type").jsonPrimitive.content)
        }
    }
}

private class RecordingNormalRouteGraphService : StubMapControlService() {
    var request: GetNormalRouteGraphRequest? = null

    override suspend fun getNormalRouteGraph(
        request: GetNormalRouteGraphRequest,
    ): ControlResult<NormalRouteGraphSnapshotDto> {
        this.request = request
        return ControlResult.Success(
            request.requestId,
            NormalRouteGraphSnapshotDto(
                schemaVersion = 1,
                projection = NormalRouteGraphProjection.OFFICIAL_2D,
                useAnsiblex = request.useAnsiblex,
                nodes = listOf(NormalRouteGraphNodeDto(30_000_001, "No Layout", null, null)),
                edges = listOf(
                    NormalRouteGraphEdgeDto(30_000_001, 30_000_002, NormalRouteGraphEdgeType.ANSIBLEX),
                ),
            ),
        )
    }
}
