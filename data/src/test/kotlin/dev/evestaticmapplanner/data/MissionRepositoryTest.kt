package dev.evestaticmapplanner.data

import dev.evestaticmapplanner.control.mission.*
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.*
import dev.evestaticmapplanner.data.mission.SqliteMissionRepository
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class MissionRepositoryTest {
    @Test
    fun `round trips View owned Mission routes ranges and markers`() {
        val repository = SqliteMissionRepository(createTempDirectory("missions").resolve("user.db"))
        val missionId = MissionId("mission-1")
        val profile = JumpProfile("carrier", "Carrier", 5.0)
        val normal = MissionRoute.Normal(
            missionId, MissionRouteId("normal-1"),
            RouteResult(1, 2, listOf(1, 2), listOf(RouteEdge(
                RouteEdgeId("edge-1"), RouteConnectionId("connection-1"), 1, 2, RouteEdgeType.STARGATE,
            ))),
        )
        val capital = MissionRoute.Capital(
            missionId, MissionRouteId("capital-1"),
            CapitalRouteResult(2, 3, profile, listOf(2, 3), listOf(CapitalRouteLeg(2, 3, 1.0e15))),
        )
        val mission = Mission(
            missionId, "Scout route", Instant.parse("2026-08-30T00:00:00Z"), 4,
            listOf(normal, capital),
            listOf(MissionJumpRange(missionId, MissionJumpRangeId("range-1"), 2, profile, setOf(1, 2, 3), "Range")),
            listOf(MissionMarker(missionId, MissionMarkerId("marker-1"), 3, MissionMarkerRole.DANGER, "Danger", "Camp", MarkerColor.RED)),
            setOf(1, 2, 3),
            "view-scout",
        )

        repository.save(listOf(mission))

        assertEquals(listOf(mission), repository.load())
    }
}
