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

        normal.value = NormalRoutePlanningSnapshot(
            1,
            2,
            useAnsiblex = true,
            useWormholes = true,
            calculated = true,
        )
        capital.value = CapitalRoutePlanningSnapshot(3, 4, "7.5", calculated = true)
        val second = coordinator.createView()
        assertEquals(NormalRoutePlanningSnapshot(), normal.value)
        assertEquals(CapitalRoutePlanningSnapshot(), capital.value)

        normal.value = NormalRoutePlanningSnapshot(5, 6, calculated = true)
        assertTrue(coordinator.switchView(PlanningViewId("view-1")))
        assertEquals(1, normal.value.fromSystemId)
        assertTrue(normal.value.useWormholes)
        assertEquals("7.5", capital.value.manualRangeText)
        assertTrue(coordinator.switchView(second))
        assertEquals(5, normal.value.fromSystemId)
        assertFalse(normal.value.useWormholes)
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
        assertFalse(coordinator.renameView(second, "   "))
        assertFalse(coordinator.renameView(PlanningViewId("view-1"), "scout"))
        assertTrue(coordinator.deleteView(second))
        assertFalse(coordinator.deleteView(PlanningViewId("view-1")))
        assertEquals("View 1", coordinator.state.value.currentView.label)
    }

    @Test
    fun `rename preserves stable id routes and selected ESI target`() {
        val id = PlanningViewId("stable-id")
        val normalSnapshot = NormalRoutePlanningSnapshot(10, 20, useAnsiblex = true, calculated = true)
        val capitalSnapshot = CapitalRoutePlanningSnapshot(30, 40, "7.5", calculated = true)
        val coordinator = PlanningViewCoordinator(
            NormalPort(),
            CapitalPort(),
            initialState = PlanningViewsState(
                views = listOf(PlanningView(
                    id = id,
                    label = "Original",
                    normalRoute = normalSnapshot,
                    capitalRoute = capitalSnapshot,
                    selectedRouteActionTargets = mapOf("esi-character" to "90000001"),
                )),
                currentViewId = id,
            ),
        )

        assertTrue(coordinator.renameView(id, " Renamed "))
        assertEquals(
            PlanningView(
                id = id,
                label = "Renamed",
                normalRoute = normalSnapshot,
                capitalRoute = capitalSnapshot,
                selectedRouteActionTargets = mapOf("esi-character" to "90000001"),
            ),
            coordinator.state.value.currentView,
        )
    }

    @Test
    fun `delete keeps non-current selection and current deletion prefers right then left`() {
        val ids = (1..3).map { PlanningViewId("view-$it") }
        fun coordinator(current: PlanningViewId) = PlanningViewCoordinator(
            NormalPort(),
            CapitalPort(),
            initialState = PlanningViewsState(
                ids.mapIndexed { index, id -> PlanningView(id, "View ${index + 1}") },
                current,
            ),
        )

        coordinator(ids[1]).also {
            assertTrue(it.deleteView(ids[0]))
            assertEquals(ids[1], it.state.value.currentViewId)
        }
        coordinator(ids[1]).also {
            assertTrue(it.deleteView(ids[1]))
            assertEquals(ids[2], it.state.value.currentViewId)
        }
        coordinator(ids[2]).also {
            assertTrue(it.deleteView(ids[2]))
            assertEquals(ids[1], it.state.value.currentViewId)
        }
    }

    @Test
    fun `fresh coordinator is exact blank View 1 and recreation discards the planning session`() {
        val firstNormal = NormalPort()
        val firstCapital = CapitalPort()
        val first = PlanningViewCoordinator(
            firstNormal,
            firstCapital,
            newId = { PlanningViewId("view-2") },
        )
        assertEquals(
            PlanningViewsState(
                listOf(PlanningView(PlanningViewId("view-1"), "View 1")),
                PlanningViewId("view-1"),
            ),
            first.state.value,
        )

        firstNormal.value = NormalRoutePlanningSnapshot(1, 2, calculated = true)
        firstCapital.value = CapitalRoutePlanningSnapshot(3, 4, "8", calculated = true)
        first.captureCurrent()
        first.selectRouteActionTarget("esi-character", "character-42")
        val second = first.createView("Second")
        assertTrue(first.state.value.currentView.selectedRouteActionTargets.isEmpty())
        first.selectRouteActionTarget("esi-character", "character-84")
        first.switchView(PlanningViewId("view-1"))
        assertEquals("character-42", first.state.value.currentView.selectedRouteActionTargets["esi-character"])
        first.switchView(second)
        assertEquals("character-84", first.state.value.currentView.selectedRouteActionTargets["esi-character"])

        val restartedNormal = NormalPort()
        val restartedCapital = CapitalPort()
        val restarted = PlanningViewCoordinator(restartedNormal, restartedCapital)

        assertEquals(listOf("View 1"), restarted.state.value.views.map(PlanningView::label))
        assertEquals(PlanningViewId("view-1"), restarted.state.value.currentViewId)
        assertEquals(NormalRoutePlanningSnapshot(), restartedNormal.value)
        assertEquals(CapitalRoutePlanningSnapshot(), restartedCapital.value)
        assertTrue(restarted.state.value.currentView.selectedRouteActionTargets.isEmpty())
    }

    @Test
    fun `normal and capital snapshots are both restored before switch returns`() {
        val normal = NormalPort()
        val capital = CapitalPort()
        val coordinator = PlanningViewCoordinator(
            normal,
            capital,
            newId = { PlanningViewId("view-2") },
        )
        normal.value = NormalRoutePlanningSnapshot(1, 2, calculated = true)
        capital.value = CapitalRoutePlanningSnapshot(3, 4, "7", calculated = true)
        coordinator.captureCurrent()
        val second = coordinator.createView()
        normal.value = NormalRoutePlanningSnapshot(5, 6, calculated = true)
        capital.value = CapitalRoutePlanningSnapshot(7, 8, "9", calculated = true)
        coordinator.captureCurrent()

        assertTrue(coordinator.switchView(PlanningViewId("view-1")))
        assertEquals(1, normal.value.fromSystemId)
        assertEquals(3, capital.value.fromSystemId)
        assertEquals("7", capital.value.manualRangeText)

        assertTrue(coordinator.switchView(second))
        assertEquals(5, normal.value.fromSystemId)
        assertEquals(7, capital.value.fromSystemId)
        assertEquals("9", capital.value.manualRangeText)
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
