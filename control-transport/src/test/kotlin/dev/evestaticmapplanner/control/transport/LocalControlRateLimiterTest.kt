package dev.evestaticmapplanner.control.transport

import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.mission.MissionId
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalControlRateLimiterTest {
    @Test
    fun `global limiter allows burst rejects overflow and recovers deterministically`() {
        val clock = AtomicLong(0)
        val server = LocalControlServer(StubMapControlService(), "0.1.2")
        server.nanoTime = clock::get
        server.start()
        try {
            val client = LocalControlTestClient(server)
            repeat(40) { assertEquals(200, client.handshake("global-$it").status, "$it") }
            val limited = client.handshake("global-limited")
            assertEquals(429, limited.status)
            assertTrue(limited.body.contains("RATE_LIMITED"))

            clock.addAndGet(50_000_000)
            assertEquals(200, client.handshake("global-recovered").status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `mutation limiter has independent lower burst and recovery`() {
        val service = object : StubMapControlService() {
            override suspend fun beginMission(command: BeginMissionCommand) = ControlResult.Success(
                command.requestId,
                MissionSummaryDto(MissionId("mission-${command.requestId}"), command.title, 1, 1, 0, 0, 0, 0),
                1,
            )
        }
        val clock = AtomicLong(0)
        val server = LocalControlServer(service, "0.1.2")
        server.nanoTime = clock::get
        server.start()
        try {
            val client = LocalControlTestClient(server)
            repeat(20) { index ->
                assertEquals(200, client.beginMission("mutation-$index", "key-$index", "Mission $index").status)
            }
            assertEquals(429, client.beginMission("mutation-limited", "key-limited", "Limited").status)

            clock.addAndGet(100_000_000)
            assertEquals(200, client.beginMission("mutation-recovered", "key-recovered", "Recovered").status)
        } finally {
            server.stop()
        }
    }
}
