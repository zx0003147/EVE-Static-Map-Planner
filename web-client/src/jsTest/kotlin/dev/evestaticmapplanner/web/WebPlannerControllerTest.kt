package dev.evestaticmapplanner.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPlannerControllerTest {
    @Test
    fun `controller exposes route errors and maintains independent map overlays`() {
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val published = mutableListOf<WebPlannerState>()
        val controller = WebPlannerController(universe, published::add)

        controller.calculateNormalRoute()
        assertNotNull(controller.state.error)

        controller.setNormalStart(30_000_001)
        controller.addNormalWaypoint(30_000_003)
        controller.setNormalDestination(30_000_004)
        controller.calculateNormalRoute()
        assertEquals(listOf(30_000_001, 30_000_002, 30_000_003, 30_000_004), controller.state.normalRoute?.systems)
        assertNull(controller.state.error)

        controller.setCapitalRange(0.6)
        controller.addJumpRange(30_000_001)
        controller.addJumpRange(30_000_003)
        assertEquals(2, controller.state.jumpOverlays.size)
        assertEquals(2, controller.state.coverageCounts.getValue(30_000_002))
        assertTrue(published.isNotEmpty())
    }
}
