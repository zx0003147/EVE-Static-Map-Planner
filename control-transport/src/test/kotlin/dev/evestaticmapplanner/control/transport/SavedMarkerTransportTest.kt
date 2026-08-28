package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.CreateSavedMarkerReceipt
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.MissionMarkerSummaryDto
import dev.evestaticmapplanner.control.SavedMarkerChildSummaryDto
import dev.evestaticmapplanner.control.SavedMarkerSummaryDto
import dev.evestaticmapplanner.control.SystemMarkersDto
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedMarkerTransportTest {
    @Test
    fun `JSON query preserves provenance notes children and multiple Mission markers`() {
        val server = LocalControlServer(MarkerTransportService(), "0.5.0")
        server.start()
        try {
            val response = rawRequest(
                server,
                LocalControlOperation.SYSTEM_MARKERS.path,
                HttpRequest.BodyPublishers.ofString("{\"requestId\":\"markers-1\",\"systemId\":1}"),
            )
            assertEquals(200, response.status)
            val value = response.jsonValue()
            assertEquals(1, value.getValue("systemId").jsonPrimitive.content.toInt())
            val saved = value.getValue("savedMarker").jsonObject
            assertEquals("USER", saved.getValue("createdBy").jsonPrimitive.content)
            assertEquals("Line 1\nLine 2 \"quoted\"", saved.getValue("notes").jsonPrimitive.content)
            assertEquals("staging", assertIs<JsonArray>(saved.getValue("children")).single().jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(2, assertIs<JsonArray>(value.getValue("missionMarkers")).size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `JSON query encodes absent saved marker and empty Mission list`() {
        val service = object : MarkerTransportService() {
            override suspend fun getSystemMarkers(request: GetSystemMarkersRequest) =
                ControlResult.Success(request.requestId, SystemMarkersDto(request.systemId, null, emptyList()))
        }
        val server = LocalControlServer(service, "0.5.0")
        server.start()
        try {
            val response = rawRequest(
                server,
                LocalControlOperation.SYSTEM_MARKERS.path,
                HttpRequest.BodyPublishers.ofString("{\"requestId\":\"empty\",\"systemId\":1}"),
            )
            val value = response.jsonValue()
            assertEquals(JsonNull, value["savedMarker"])
            assertTrue(assertIs<JsonArray>(value.getValue("missionMarkers")).isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `create JSON is strict and maps success denied duplicate and invalid data`() {
        val service = MarkerTransportService()
        val server = LocalControlServer(service, "0.5.0")
        server.start()
        try {
            val valid = rawRequest(
                server,
                LocalControlOperation.CREATE_SAVED_MARKER.path,
                HttpRequest.BodyPublishers.ofString(
                    "{\"requestId\":\"create\",\"idempotencyKey\":\"key\",\"systemId\":1," +
                        "\"name\":\"Home\",\"notes\":\"Line 1\\nLine 2\",\"color\":\"GREEN\"," +
                        "\"tags\":[\"STAGING\",\"STRATEGIC\",\"STAGING\"]}",
                ),
            )
            assertEquals(200, valid.status)
            assertEquals("AI", valid.jsonValue().getValue("marker").jsonObject.getValue("createdBy").jsonPrimitive.content)
            assertEquals(MarkerColor.GREEN, service.lastCreate?.color)
            assertEquals(listOf("staging", "strategic", "staging"), service.lastCreate?.tags?.map { it.key })

            listOf(
                "{\"requestId\":\"absent\",\"idempotencyKey\":\"absent\",\"systemId\":2,\"color\":\"RED\"}",
                "{\"requestId\":\"empty\",\"idempotencyKey\":\"empty\",\"systemId\":2,\"color\":\"RED\",\"tags\":[]}",
            ).forEach { body ->
                assertEquals(
                    200,
                    rawRequest(server, LocalControlOperation.CREATE_SAVED_MARKER.path, HttpRequest.BodyPublishers.ofString(body)).status,
                )
                assertEquals(emptyList(), service.lastCreate?.tags)
            }

            listOf(
                "{\"requestId\":\"missing\",\"idempotencyKey\":\"key\",\"systemId\":1}",
                "{\"requestId\":\"color\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"BLACK\"}",
                "{\"requestId\":\"unknown\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"children\":[]}",
                "{\"requestId\":\"null-tags\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"tags\":null}",
                "{\"requestId\":\"wrong-tags\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"tags\":\"DANGER\"}",
                "{\"requestId\":\"unknown-tag\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"tags\":[\"UNKNOWN\"]}",
                "{\"requestId\":\"lower-tag\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"tags\":[\"danger\"]}",
                "{\"requestId\":\"null-tag\",\"idempotencyKey\":\"key\",\"systemId\":1,\"color\":\"RED\",\"tags\":[null]}",
                "{malformed",
            ).forEach { body ->
                assertEquals(
                    400,
                    rawRequest(
                        server,
                        LocalControlOperation.CREATE_SAVED_MARKER.path,
                        HttpRequest.BodyPublishers.ofString(body),
                    ).status,
                )
            }

            service.failure = ControlErrorCode.CAPABILITY_DENIED
            assertError(server, "denied", 403, "CAPABILITY_DENIED")
            service.failure = ControlErrorCode.MARKER_ALREADY_EXISTS
            assertError(server, "duplicate", 409, "MARKER_ALREADY_EXISTS")
            service.failure = ControlErrorCode.INVALID_MARKER_DATA
            assertError(server, "invalid", 400, "INVALID_MARKER_DATA")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `Local Control client invokes both Control API v2 marker operations`() {
        val temporary = createTempDirectory("saved-marker-client-")
        val root = temporary.resolve("control")
        val acquisition = assertIs<LocalControlDiscoveryAcquisition.Acquired>(SecureLocalControlDiscovery(root).acquire())
        val service = MarkerTransportService()
        val server = LocalControlServer(service, "0.5.0")
        server.start()
        acquisition.lease.publish(server)
        try {
            LocalControlClient(
                SecureLocalControlDiscoveryReader(root),
                newDirectLocalControlHttpClient(),
                { java.util.UUID.randomUUID().toString() },
            ).use { client ->
                val query = assertIs<LocalControlClientResult.Success>(runBlocking { client.getSystemMarkers(1) })
                assertEquals(1, query.value.jsonObject.getValue("systemId").jsonPrimitive.content.toInt())
                val create = assertIs<LocalControlClientResult.Success>(
                    runBlocking { client.createSavedMarker(1, "Home", "Notes", "BLUE", listOf("LOGISTICS")) },
                )
                assertEquals("AI", create.value.jsonObject.getValue("marker").jsonObject.getValue("createdBy").jsonPrimitive.content)
                assertEquals(listOf("logistics"), service.lastCreate?.tags?.map { it.key })
                service.failure = ControlErrorCode.CAPABILITY_DENIED
                assertEquals(
                    LocalControlClientErrorCode.CAPABILITY_DENIED,
                    assertIs<LocalControlClientResult.Failure>(
                        runBlocking { client.getSystemMarkers(1) },
                    ).error.code,
                )
                service.failure = ControlErrorCode.MARKER_ALREADY_EXISTS
                assertEquals(
                    LocalControlClientErrorCode.MARKER_ALREADY_EXISTS,
                    assertIs<LocalControlClientResult.Failure>(
                        runBlocking { client.createSavedMarker(1, null, null, "YELLOW") },
                    ).error.code,
                )
            }
        } finally {
            runCatching { acquisition.lease.unpublishDescriptor() }
            runCatching { server.stop() }
            runCatching { acquisition.lease.removeSessionKey() }
            runCatching { acquisition.lease.release() }
            temporary.toFile().deleteRecursively()
        }
    }
}

private open class MarkerTransportService : StubMapControlService() {
    var failure: ControlErrorCode? = null
    var lastCreate: CreateSavedMarkerCommand? = null

    override suspend fun getSystemMarkers(request: GetSystemMarkersRequest): ControlResult<SystemMarkersDto> =
        failure(request.requestId) ?: ControlResult.Success(
            request.requestId,
            SystemMarkersDto(
                request.systemId,
                saved(SavedMarkerCreatedBy.USER),
                listOf(
                    mission("mission-1", "marker-1", MissionMarkerRole.RALLY, MarkerColor.GREEN),
                    mission("mission-2", "marker-2", MissionMarkerRole.DANGER, MarkerColor.ORANGE),
                ),
            ),
        )

    override suspend fun createSavedMarker(command: CreateSavedMarkerCommand): ControlResult<CreateSavedMarkerReceipt> {
        lastCreate = command
        return failure(command.requestId) ?: ControlResult.Success(
            command.requestId,
            CreateSavedMarkerReceipt(
                saved(SavedMarkerCreatedBy.AI).copy(
                    systemId = command.systemId,
                    name = command.name,
                    notes = command.notes,
                    color = command.color,
                    children = command.tags.distinct().mapIndexed { index, type ->
                        SavedMarkerChildSummaryDto("child-$index", type.key, index)
                    },
                ),
            ),
        )
    }

    private fun <T> failure(requestId: String): ControlResult<T>? = failure?.let {
        ControlResult.Failure(requestId, ControlError(it, "internal details must be sanitized"))
    }
}

private fun saved(createdBy: SavedMarkerCreatedBy) = SavedMarkerSummaryDto(
    1,
    "Home",
    MarkerColor.YELLOW,
    "Line 1\nLine 2 \"quoted\"",
    listOf(SavedMarkerChildSummaryDto("child-1", "staging", 0)),
    createdBy,
)

private fun mission(id: String, markerId: String, role: MissionMarkerRole, color: MarkerColor) =
    MissionMarkerSummaryDto(MissionId(id), MissionMarkerId(markerId), 1, role, role.name, null, color)

private fun TestHttpResponse.jsonValue(): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject.getValue("value").jsonObject

private fun assertError(server: LocalControlServer, requestId: String, status: Int, code: String) {
    val response = rawRequest(
        server,
        LocalControlOperation.CREATE_SAVED_MARKER.path,
        HttpRequest.BodyPublishers.ofString(
            "{\"requestId\":\"$requestId\",\"idempotencyKey\":\"$requestId\",\"systemId\":1,\"color\":\"RED\"}",
        ),
    )
    assertEquals(status, response.status)
    assertTrue(response.body.contains(code))
}
