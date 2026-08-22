package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.CalculateCapitalRouteRequest
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.ControlError
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemSummaryDto
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalControlErrorMappingTest {
    @Test
    fun `control failures map to stable statuses and internal diagnostics are sanitized`() {
        val dangerous = "java.sql.SQLException C:\\Users\\secret\\user.db"
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest) = failure<List<SystemSummaryDto>>(
                request.requestId,
                ControlErrorCode.DATABASE_UNAVAILABLE,
                dangerous,
            )

            override suspend fun getSystemInfo(request: GetSystemInfoRequest) =
                failure<dev.evestaticmapplanner.control.SystemInfoDto>(request.requestId, ControlErrorCode.NOT_FOUND, dangerous)

            override suspend fun calculateNormalRoute(request: CalculateNormalRouteRequest) =
                failure<dev.evestaticmapplanner.control.NormalRouteDto>(request.requestId, ControlErrorCode.INVALID_ARGUMENT, dangerous)

            override suspend fun calculateCapitalRoute(request: CalculateCapitalRouteRequest) =
                failure<dev.evestaticmapplanner.control.CapitalRouteDto>(request.requestId, ControlErrorCode.INTERNAL_ERROR, dangerous)

            override suspend fun beginMission(command: BeginMissionCommand) =
                failure<dev.evestaticmapplanner.control.MissionSummaryDto>(
                    command.requestId,
                    ControlErrorCode.IDEMPOTENCY_CONFLICT,
                    dangerous,
                )

            override suspend fun focusSystem(command: FocusSystemCommand) =
                failure<SystemSummaryDto>(command.requestId, ControlErrorCode.APP_NOT_READY, dangerous)

            private fun <T> failure(requestId: String, code: ControlErrorCode, message: String): ControlResult<T> =
                ControlResult.Failure(requestId, ControlError(code, message))
        }
        val server = LocalControlServer(service, "0.1.2")
        server.start()
        try {
            val cases = listOf(
                LocalControlOperation.SEARCH_SYSTEM.path to
                    ("{\"requestId\":\"db-1\",\"query\":\"Jita\"}" to (503 to "DATABASE_UNAVAILABLE")),
                LocalControlOperation.SYSTEM_INFO.path to
                    ("{\"requestId\":\"not-found-1\",\"systemId\":1}" to (404 to "NOT_FOUND")),
                LocalControlOperation.NORMAL_ROUTE.path to
                    ("{\"requestId\":\"invalid-1\",\"startSystemId\":1,\"destinationSystemId\":2,\"useAnsiblex\":false}" to (400 to "INVALID_ARGUMENT")),
                LocalControlOperation.CAPITAL_ROUTE.path to
                    ("{\"requestId\":\"internal-1\",\"startSystemId\":1,\"destinationSystemId\":2,\"effectiveRangeLy\":5.0}" to (500 to "INTERNAL_ERROR")),
                LocalControlOperation.BEGIN_MISSION.path to
                    ("{\"requestId\":\"conflict-1\",\"idempotencyKey\":\"same-key\",\"title\":\"A\"}" to (409 to "IDEMPOTENCY_CONFLICT")),
                LocalControlOperation.FOCUS_SYSTEM.path to
                    ("{\"requestId\":\"ready-1\",\"idempotencyKey\":\"focus-key\",\"systemId\":1}" to (503 to "APP_NOT_READY")),
            )
            cases.forEach { (path, bodyAndExpected) ->
                val (body, expected) = bodyAndExpected
                val response = rawRequest(server, path, HttpRequest.BodyPublishers.ofString(body))
                assertEquals(expected.first, response.status, path)
                assertTrue(response.body.contains(expected.second), path)
                assertFalse(response.body.contains(dangerous), path)
                assertFalse(response.body.contains("SQLException"), path)
                assertFalse(response.body.contains("user.db"), path)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `oversized serialized response becomes bounded internal error`() {
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest) = ControlResult.Success(
                request.requestId,
                listOf(SystemSummaryDto(1, "x".repeat(LocalControlProtocol.RESPONSE_BODY_LIMIT_BYTES + 1), 1, 1, 0.0)),
            )
        }
        val server = LocalControlServer(service, "0.1.2")
        server.start()
        try {
            val response = LocalControlTestClient(server).search()
            assertEquals(500, response.status)
            assertTrue(response.body.contains("INTERNAL_ERROR"))
            assertTrue(response.body.toByteArray().size < 1024)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unexpected service exception is caught at transport boundary without class message or stack trace`() {
        val service = object : StubMapControlService() {
            override suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>> {
                error("C:\\private\\static.db SQL exploded")
            }
        }
        val server = LocalControlServer(service, "0.1.2")
        server.start()
        try {
            val response = LocalControlTestClient(server).search("unexpected-1")
            assertEquals(500, response.status)
            assertTrue(response.body.contains("INTERNAL_ERROR"))
            listOf("IllegalStateException", "private", "static.db", "SQL exploded", "at dev.").forEach {
                assertFalse(response.body.contains(it), it)
            }
        } finally {
            server.stop()
        }
    }
}
