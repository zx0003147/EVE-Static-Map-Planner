package dev.evestaticmapplanner.view

import dev.evestaticmapplanner.capital.CapitalRoutePlanningPort
import dev.evestaticmapplanner.capital.CapitalRoutePlanningSnapshot
import dev.evestaticmapplanner.route.NormalRoutePlanningPort
import dev.evestaticmapplanner.route.NormalRoutePlanningSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningViewCoordinatorTest {
    @Test
    fun `views keep independent normal and capital planning state`() {
        val normal = NormalPort()
        val capital = CapitalPort()
        var id = 1
        val coordinator = PlanningViewCoordinator(
            normal,
            capital,
            newId = { PlanningViewId("generated-${id++}") },
        )

        normal.value = NormalRoutePlanningSnapshot(1, 2, useAnsiblex = true, calculated = true)
        capital.value = CapitalRoutePlanningSnapshot(3, 4, "7.5", calculated = true)
        val second = coordinator.createView()
        assertEquals(NormalRoutePlanningSnapshot(), normal.value)
        assertEquals(CapitalRoutePlanningSnapshot(), capital.value)

        normal.value = NormalRoutePlanningSnapshot(5, 6, calculated = true)
        assertTrue(coordinator.switchView(PlanningViewId("view-1")))
        assertEquals(1, normal.value.fromSystemId)
        assertEquals("7.5", capital.value.manualRangeText)
        assertTrue(coordinator.switchView(second))
        assertEquals(5, normal.value.fromSystemId)
    }

    @Test
    fun `view labels are case insensitive unique and at least one view remains`() {
        val coordinator = PlanningViewCoordinator(
            NormalPort(),
            CapitalPort(),
            newId = { PlanningViewId("view-2") },
        )
        val second = coordinator.createView()

        assertTrue(coordinator.renameView(second, " Scout "))
        assertFalse(coordinator.renameView(PlanningViewId("view-1"), "scout"))
        assertTrue(coordinator.deleteView(second))
        assertFalse(coordinator.deleteView(PlanningViewId("view-1")))
        assertEquals("View 1", coordinator.state.value.currentView.label)
    }

    private class NormalPort : NormalRoutePlanningPort {
        var value = NormalRoutePlanningSnapshot()
        override fun planningSnapshot() = value
        override fun restorePlanningSnapshot(snapshot: NormalRoutePlanningSnapshot) { value = snapshot }
    }

    private class CapitalPort : CapitalRoutePlanningPort {
        var value = CapitalRoutePlanningSnapshot()
        override fun planningSnapshot() = value
        override fun restorePlanningSnapshot(snapshot: CapitalRoutePlanningSnapshot) { value = snapshot }
    }
}
