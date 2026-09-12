package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.MultiPointRouteCoverageDto
import dev.evestaticmapplanner.control.MultiPointRouteOptimizationDetailsDto
import dev.evestaticmapplanner.control.MultiPointRouteOptimizationDto
import dev.evestaticmapplanner.control.MultiPointRouteSegmentDto
import dev.evestaticmapplanner.control.MultiPointRouteStatsDto
import dev.evestaticmapplanner.control.MultiPointRouteTargetDto
import dev.evestaticmapplanner.control.OptimizeMultiPointRouteRequest
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiPointRouteTransportTest {
    @Test
    fun `request and success DTO preserve native optimizer semantics`() {
        val service = RecordingMultiPointRouteService()
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val response = rawRequest(
                server,
                LocalControlOperation.OPTIMIZE_MULTI_POINT_ROUTE.path,
                HttpRequest.BodyPublishers.ofString(
                    """{"requestId":"multi-1","startSystemId":1,"targetSystemIds":[3,2,3],"useAnsiblex":true}""",
                ),
            )

            assertEquals(200, response.status)
            assertEquals(OptimizeMultiPointRouteRequest("multi-1", 1, listOf(3, 2, 3), true), service.request)
            val value = Json.parseToJsonElement(response.body).jsonObject.getValue("value").jsonObject
            assertEquals(1, value.getValue("schemaVersion").jsonPrimitive.int)
            assertEquals(true, value.getValue("success").jsonPrimitive.boolean)
            assertEquals("EXACT_HELD_KARP", value.getValue("optimization").jsonObject.getValue("method").jsonPrimitive.content)
            assertEquals(listOf(2, 3), value.getValue("orderedTargets").jsonArray.map {
                it.jsonObject.getValue("systemId").jsonPrimitive.int
            })
            assertEquals(2, value.getValue("totalJumps").jsonPrimitive.int)
            assertEquals(3, value.getValue("stats").jsonObject.getValue("bfsRuns").jsonPrimitive.int)
        }
    }

    @Test
    fun `unreachable failure serializes named systems without claiming success`() {
        val service = RecordingMultiPointRouteService(fail = true)
        LocalControlServer(service, "1.0.0").use { server ->
            server.start()
            val response = rawRequest(
                server,
                LocalControlOperation.OPTIMIZE_MULTI_POINT_ROUTE.path,
                HttpRequest.BodyPublishers.ofString(
                    """{"requestId":"multi-2","startSystemId":1,"targetSystemIds":[2,3],"useAnsiblex":false}""",
                ),
            )

            val value = Json.parseToJsonElement(response.body).jsonObject.getValue("value").jsonObject
            assertEquals(false, value.getValue("success").jsonPrimitive.boolean)
            assertEquals("UNREACHABLE_TARGETS", value.getValue("error").jsonPrimitive.content)
            assertEquals(listOf(3), value.getValue("unreachableSystemIds").jsonArray.map { it.jsonPrimitive.int })
        }
    }
}

private class RecordingMultiPointRouteService(
    private val fail: Boolean = false,
) : StubMapControlService() {
    var request: OptimizeMultiPointRouteRequest? = null

    override suspend fun optimizeMultiPointRoute(
        request: OptimizeMultiPointRouteRequest,
    ): ControlResult<MultiPointRouteOptimizationDto> {
        this.request = request
        val value = if (fail) {
            MultiPointRouteOptimizationDto.Failed(
                error = "UNREACHABLE_TARGETS",
                message = "Target 3 is unreachable.",
                unreachableSystemIds = listOf(3),
            )
        } else {
            MultiPointRouteOptimizationDto.Succeeded(
                startSystemId = 1,
                inputTargetCount = 3,
                uniqueTargetCount = 2,
                startWasTarget = false,
                useAnsiblex = true,
                optimization = MultiPointRouteOptimizationDetailsDto("EXACT_HELD_KARP", true),
                orderedTargets = listOf(
                    MultiPointRouteTargetDto(2, "Two"),
                    MultiPointRouteTargetDto(3, "Three"),
                ),
                segments = listOf(
                    MultiPointRouteSegmentDto(1, 2, 1),
                    MultiPointRouteSegmentDto(2, 3, 1),
                ),
                totalJumps = 2,
                coverage = MultiPointRouteCoverageDto(2, 2, emptyList()),
                stats = MultiPointRouteStatsDto(3, 2, 3),
            )
        }
        return ControlResult.Success(request.requestId, value)
    }
}
