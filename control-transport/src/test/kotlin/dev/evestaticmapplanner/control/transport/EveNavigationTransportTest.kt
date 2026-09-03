package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.EveNavigationTargetDto
import dev.evestaticmapplanner.control.ListEveNavigationTargetsRequest
import dev.evestaticmapplanner.control.NavigationActionExecutionStatus
import dev.evestaticmapplanner.control.SendMissionNavigationReceipt
import dev.evestaticmapplanner.control.SendMissionNavigationToEveCommand
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionRouteId
import java.net.http.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EveNavigationTransportTest {
    @Test
    fun `safe target listing and explicit Mission route send round trip exactly`() {
        val service = RecordingEveNavigationTransportService()
        LocalControlServer(service, "1.2.0").use { server ->
            server.start()

            val targets = rawRequest(server, LocalControlOperation.EVE_NAVIGATION_TARGETS.path)
            val sent = rawRequest(
                server,
                LocalControlOperation.SEND_MISSION_NAVIGATION_TO_EVE.path,
                body(
                    """{"requestId":"send-1","idempotencyKey":"send-1","missionId":"mission-7","routeId":"route-9","characterId":"character-42"}""",
                ),
            )

            assertEquals(200, targets.status)
            val target = value(targets).jsonArray.single().jsonObject
            assertEquals("character-42", target.getValue("characterId").jsonPrimitive.content)
            assertEquals("Pilot Forty Two", target.getValue("label").jsonPrimitive.content)
            assertFalse(target.getValue("available").jsonPrimitive.boolean)

            assertEquals(200, sent.status)
            assertEquals(
                SendMissionNavigationToEveCommand(
                    "send-1",
                    "send-1",
                    MissionId("mission-7"),
                    MissionRouteId("route-9"),
                    "character-42",
                ),
                service.sentCommand,
            )
            val receipt = value(sent).jsonObject
            assertEquals(listOf("30000002", "30000003"), receipt.getValue("targetSystemIds").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("SUCCEEDED", receipt.getValue("status").jsonPrimitive.content)
            assertFalse(sent.body.contains("token", ignoreCase = true))
        }
    }

    @Test
    fun `send rejects any missing explicit identity before calling service`() {
        val service = RecordingEveNavigationTransportService()
        LocalControlServer(service, "1.2.0").use { server ->
            server.start()
            val complete = linkedMapOf(
                "missionId" to "\"mission-7\"",
                "routeId" to "\"route-9\"",
                "characterId" to "\"character-42\"",
            )

            complete.keys.forEach { omitted ->
                val fields = complete.filterKeys { it != omitted }.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }
                val response = rawRequest(
                    server,
                    LocalControlOperation.SEND_MISSION_NAVIGATION_TO_EVE.path,
                    body("""{"requestId":"missing-$omitted","idempotencyKey":"missing-$omitted",$fields}"""),
                )
                assertEquals(400, response.status, omitted)
            }

            assertEquals(null, service.sentCommand)
        }
    }
}

private class RecordingEveNavigationTransportService : StubMapControlService() {
    var sentCommand: SendMissionNavigationToEveCommand? = null

    override suspend fun listEveNavigationTargets(
        request: ListEveNavigationTargetsRequest,
    ): ControlResult<List<EveNavigationTargetDto>> = ControlResult.Success(
        request.requestId,
        listOf(EveNavigationTargetDto("character-42", "Pilot Forty Two", "Disconnected", false)),
    )

    override suspend fun sendMissionNavigationToEve(
        command: SendMissionNavigationToEveCommand,
    ): ControlResult<SendMissionNavigationReceipt> {
        sentCommand = command
        return ControlResult.Success(
            command.requestId,
            SendMissionNavigationReceipt(
                command.missionId,
                command.routeId,
                command.characterId,
                listOf(30_000_002, 30_000_003),
                NavigationActionExecutionStatus.SUCCEEDED,
                "Sent 2 navigation targets to EVE.",
            ),
        )
    }
}

private fun body(value: String) = HttpRequest.BodyPublishers.ofString(value)

private fun value(response: TestHttpResponse) = Json.parseToJsonElement(response.body)
    .jsonObject.getValue("value")
