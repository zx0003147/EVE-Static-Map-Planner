package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.control.mission.Mission
import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.control.mission.MissionMarker
import dev.evestaticmapplanner.control.mission.MissionMarkerId
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.route.RouteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MissionMapStateStoreTest {
    @Test
    fun `switching View filters the in-session Mission pool`() = runTest {
        val store = MissionMapStateStore(UnconfinedTestDispatcher(testScheduler))
        fun mission(id: String, viewId: String) = Mission(
            MissionId(id), id, Instant.EPOCH, 1, emptyList(), emptyList(), emptyList(), emptySet(), viewId,
        )
        store.publish(listOf(mission("one", "view-1"), mission("scout", "view-scout")))
        assertEquals(listOf("one"), store.state.value.missions.map { it.missionId.value })

        store.selectView("view-scout")

        assertEquals(listOf("scout"), store.state.value.missions.map { it.missionId.value })
    }

    @Test
    fun `Mission publish and clear are physically isolated from user collections`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = MissionMapStateStore(dispatcher)
        val userNormalRoute = RouteResult(99, 99, listOf(99), emptyList())
        val userCapitalRoute = "user-capital-route"
        val userJumpOverlays = mutableListOf<JumpRangeOverlay>()
        val userMarkers = mutableMapOf(99 to Marker.temporary(99))
        val missionId = MissionId("mission")
        val mission = Mission(
            missionId = missionId,
            title = "Mission",
            createdAt = Instant.EPOCH,
            revision = 2,
            routes = emptyList(),
            jumpRanges = emptyList(),
            markers = listOf(
                MissionMarker(missionId, MissionMarkerId("marker"), 1, MissionMarkerRole.RALLY, null, null, null),
            ),
            referencedSystemIds = setOf(1),
        )

        store.publish(listOf(mission))
        assertEquals(1, store.state.value.markers.size)
        store.publish(emptyList())

        assertTrue(store.state.value.missions.isEmpty())
        assertEquals(RouteResult(99, 99, listOf(99), emptyList()), userNormalRoute)
        assertEquals("user-capital-route", userCapitalRoute)
        assertTrue(userJumpOverlays.isEmpty())
        assertEquals(setOf(99), userMarkers.keys)
    }
}
