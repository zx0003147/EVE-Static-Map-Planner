package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.AddMissionMarkerCommand
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.CalculateCapitalRouteRequest
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.CapitalRouteDto
import dev.evestaticmapplanner.control.ClearMissionCommand
import dev.evestaticmapplanner.control.ClearMissionJumpRangesCommand
import dev.evestaticmapplanner.control.ClearMissionMarkersCommand
import dev.evestaticmapplanner.control.ClearMissionRoutesCommand
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.CreateSavedMarkerReceipt
import dev.evestaticmapplanner.control.FitMissionCommand
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.GetActiveMissionsRequest
import dev.evestaticmapplanner.control.GetMissionRequest
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionJumpRangeReceipt
import dev.evestaticmapplanner.control.MissionMarkerReceipt
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.NormalRouteDto
import dev.evestaticmapplanner.control.RemoveJumpRangeCommand
import dev.evestaticmapplanner.control.RemoveMissionMarkerCommand
import dev.evestaticmapplanner.control.RemoveMissionRouteCommand
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.ShowCapitalRouteCommand
import dev.evestaticmapplanner.control.ShowJumpRangeCommand
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemMarkersDto
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.mission.Mission
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal open class StubMapControlService : MapControlService {
    protected fun <T> denied(requestId: String): ControlResult<T> =
        ControlResult.Failure(requestId, ControlError(ControlErrorCode.CAPABILITY_DENIED, "denied"))

    override suspend fun searchSystems(request: SearchSystemsRequest) = denied<List<SystemSummaryDto>>(request.requestId)
    override suspend fun getSystemInfo(request: GetSystemInfoRequest) = denied<SystemInfoDto>(request.requestId)
    override suspend fun getSystemMarkers(request: GetSystemMarkersRequest) = denied<SystemMarkersDto>(request.requestId)
    override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest) = denied<NormalRouteDto>(request.requestId)
    override suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest) = denied<CapitalRouteDto>(request.requestId)
    override suspend fun getActiveMissions(request: GetActiveMissionsRequest) = denied<List<MissionSummaryDto>>(request.requestId)
    override suspend fun getMission(request: GetMissionRequest) = denied<Mission>(request.requestId)
    override suspend fun beginMission(command: BeginMissionCommand) = denied<MissionSummaryDto>(command.requestId)
    override suspend fun createSavedMarker(command: CreateSavedMarkerCommand) =
        denied<CreateSavedMarkerReceipt>(command.requestId)
    override suspend fun focusSystem(command: FocusSystemCommand) = denied<SystemSummaryDto>(command.requestId)
    override suspend fun showNormalRoute(command: ShowNormalRouteCommand) = denied<MissionRouteReceipt>(command.requestId)
    override suspend fun showCapitalRoute(command: ShowCapitalRouteCommand) = denied<MissionRouteReceipt>(command.requestId)
    override suspend fun removeMissionRoute(command: RemoveMissionRouteCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun clearMissionRoutes(command: ClearMissionRoutesCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun showJumpRange(command: ShowJumpRangeCommand) = denied<MissionJumpRangeReceipt>(command.requestId)
    override suspend fun removeJumpRange(command: RemoveJumpRangeCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun clearMissionJumpRanges(command: ClearMissionJumpRangesCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun addMissionMarker(command: AddMissionMarkerCommand) = denied<MissionMarkerReceipt>(command.requestId)
    override suspend fun removeMissionMarker(command: RemoveMissionMarkerCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun clearMissionMarkers(command: ClearMissionMarkersCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun fitMission(command: FitMissionCommand) = denied<MissionMutationReceipt>(command.requestId)
    override suspend fun clearMission(command: ClearMissionCommand) = denied<MissionMutationReceipt>(command.requestId)
}

internal data class TestHttpResponse(
    val status: Int,
    val body: String,
    val headers: java.net.http.HttpHeaders,
)

internal class LocalControlTestClient(
    private val server: LocalControlServer,
    private val authorization: String = server.sessionCredentials().authorizationHeaderValue(),
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val baseUri = "http://127.0.0.1:${server.port}"

    fun handshake(requestId: String = "handshake-1"): TestHttpResponse =
        post(LocalControlOperation.HANDSHAKE.path, "{\"requestId\":\"$requestId\"}")

    fun search(requestId: String = "search-1", query: String = "Jita"): TestHttpResponse =
        post(LocalControlOperation.SEARCH_SYSTEM.path, "{\"requestId\":\"$requestId\",\"query\":\"$query\"}")

    fun beginMission(requestId: String, idempotencyKey: String, title: String): TestHttpResponse = post(
        LocalControlOperation.BEGIN_MISSION.path,
        "{\"requestId\":\"$requestId\",\"idempotencyKey\":\"$idempotencyKey\",\"title\":\"$title\"}",
    )

    fun showNormalRoute(
        requestId: String,
        idempotencyKey: String,
        missionId: String,
        startSystemId: Int = 1,
        destinationSystemId: Int = 2,
    ): TestHttpResponse = post(
        LocalControlOperation.SHOW_NORMAL_ROUTE.path,
        """{"requestId":"$requestId","idempotencyKey":"$idempotencyKey","missionId":"$missionId","startSystemId":$startSystemId,"destinationSystemId":$destinationSystemId,"useAnsiblex":false}""",
    )

    private fun post(path: String, body: String): TestHttpResponse {
        val request = HttpRequest.newBuilder(URI.create(baseUri + path))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return TestHttpResponse(response.statusCode(), response.body(), response.headers())
    }
}

internal fun rawRequest(
    server: LocalControlServer,
    path: String,
    body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofString("{\"requestId\":\"raw-1\"}"),
    method: String = "POST",
    authorization: String? = server.sessionCredentials().authorizationHeaderValue(),
    contentType: String? = "application/json",
    origin: String? = null,
): TestHttpResponse {
    val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path"))
        .timeout(Duration.ofSeconds(3))
    authorization?.let { builder.header("Authorization", it) }
    contentType?.let { builder.header("Content-Type", it) }
    origin?.let { builder.header("Origin", it) }
    val request = builder.method(method, body).build()
    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    return TestHttpResponse(response.statusCode(), response.body(), response.headers())
}
