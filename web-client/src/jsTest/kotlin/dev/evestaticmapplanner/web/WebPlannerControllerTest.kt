package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.shared.protocol.RouteHandoffDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffMapMetadataDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffPublisherDto
import dev.evestaticmapplanner.shared.protocol.RouteHandoffResolvedEdgeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        controller.setCoverageRange(0.6)
        controller.addJumpRange(30_000_001)
        controller.addJumpRange(30_000_003)
        assertEquals(2, controller.state.jumpOverlays.size)
        assertEquals(2, controller.state.coverageCounts.getValue(30_000_002))
        assertTrue(published.isNotEmpty())
    }

    @Test
    fun `Coverage owns its range and existing overlays keep their 4 5 and 6 LY snapshots`() {
        val controller = WebPlannerController(WebUniverseDataAdapter.adapt(fixtureDocument()), {})
        controller.setCapitalRange(0.6)
        listOf(4.0, 5.0, 6.0).forEach { range ->
            controller.setCoverageRange(range)
            controller.addJumpRange(30_000_001)
        }
        assertEquals(listOf(4.0, 5.0, 6.0), controller.state.jumpOverlays.map { it.profile.maxRangeLy })
        assertEquals(0.6, controller.state.capitalRangeLy)

        controller.setCoverageRange(10.0)
        assertEquals(listOf(4.0, 5.0, 6.0), controller.state.jumpOverlays.map { it.profile.maxRangeLy })
        controller.setCoverageRange(Double.NaN)
        assertEquals(10.0, controller.state.coverageRangeLy)
        assertNotNull(controller.state.error)
        controller.setCoverageRange(50.1)
        assertEquals(10.0, controller.state.coverageRangeLy)
        assertNotNull(controller.state.error)
        assertTrue(controller.state.coverageCounts.values.any { it > 1 })
    }

    @Test
    fun `Personal Ansiblex preview apply disable enable remove and clear preserve Pack links`() {
        val storage = MemoryBrowserStore()
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val controller = WebPlannerController(
            universe,
            {},
            personalAnsiblexStore = PersonalAnsiblexStore(storage),
            idFactory = { "personal-test" },
        )
        controller.previewPersonalAnsiblex(
            "personal.csv",
            "from,to,direction,enabled\nBeta,Isolated,BIDIRECTIONAL,true",
        )
        assertTrue(controller.state.personalAnsiblexPreview?.canApply == true)
        controller.applyPersonalAnsiblexPreview()
        assertEquals(1, controller.state.personalAnsiblex.size)
        assertEquals(2, controller.enabledAnsiblexLinks().size)

        controller.setNormalStart(30_000_001)
        controller.setNormalDestination(30_000_005)
        controller.setUseAnsiblex(true)
        controller.calculateNormalRoute()
        assertEquals(listOf(30_000_001, 30_000_002, 30_000_005), controller.state.normalRoute?.systems)

        controller.setPersonalAnsiblexEnabled("personal-test", false)
        assertEquals(1, controller.enabledAnsiblexLinks().size)
        controller.setPersonalAnsiblexEnabled("personal-test", true)
        assertEquals(2, controller.enabledAnsiblexLinks().size)
        controller.removePersonalAnsiblex("personal-test")
        assertTrue(controller.state.personalAnsiblex.isEmpty())

        controller.previewPersonalAnsiblex(
            "personal.json",
            """[{"from":"Beta","to":"Isolated","direction":"BIDIRECTIONAL"}]""",
        )
        controller.applyPersonalAnsiblexPreview()
        controller.clearPersonalAnsiblex()
        assertTrue(controller.state.personalAnsiblex.isEmpty())
        assertEquals(universe.packAnsiblexLinks, controller.enabledAnsiblexLinks())
        assertTrue(PersonalAnsiblexStore(storage).load().isEmpty())
    }

    @Test
    fun `Capital waypoints are ordered and calculated without changing Coverage range`() {
        val controller = WebPlannerController(WebUniverseDataAdapter.adapt(fixtureDocument()), {})
        controller.setCapitalStart(30_000_001)
        controller.addCapitalWaypoint(30_000_003)
        controller.setCapitalDestination(30_000_004)
        controller.setCapitalRange(0.6)
        controller.setCoverageRange(6.0)
        controller.calculateCapitalRoute()

        assertEquals(listOf(30_000_001, 30_000_002, 30_000_003, 30_000_004), controller.state.capitalRoute?.systems)
        assertEquals(listOf(30_000_003), controller.state.capitalWaypointSystemIds)
        assertEquals(6.0, controller.state.coverageRangeLy)
    }

    @Test
    fun `Keepstar replacement preserves selection waypoint and route state then restores`() {
        val storage = MemoryBrowserStore()
        val universe = WebUniverseDataAdapter.adapt(fixtureDocument())
        val controller = WebPlannerController(
            universe,
            {},
            keepstarStore = WebKeepstarMarkerStore(storage),
        )
        controller.selectSystem(30_000_002)
        controller.setNormalStart(30_000_001)
        controller.addNormalWaypoint(30_000_002)
        controller.setNormalDestination(30_000_004)
        controller.calculateNormalRoute()

        controller.toggleKeepstarSavedMarker(30_000_002)
        assertEquals(setOf(30_000_002), controller.state.keepstarSystemIds)
        assertEquals(30_000_002, controller.state.selectedSystemId)
        assertEquals(listOf(30_000_002), controller.state.normalWaypointSystemIds)
        assertTrue(30_000_002 in assertNotNull(controller.state.normalRoute).systems)
        assertEquals(setOf(30_000_002), WebKeepstarMarkerStore(storage).load())

        controller.toggleKeepstarSavedMarker(30_000_002)
        assertTrue(controller.state.keepstarSystemIds.isEmpty())
        assertTrue(WebKeepstarMarkerStore(storage).load().isEmpty())
        assertEquals(30_000_002, controller.state.selectedSystemId)
        assertTrue(30_000_002 in assertNotNull(controller.state.normalRoute).systems)
    }

    @Test
    fun `Desktop Normal and Capital snapshots load only on request and restore planner intent`() {
        val controller = WebPlannerController(WebUniverseDataAdapter.adapt(fixtureDocument()), {})
        assertNull(controller.state.normalRoute)

        controller.loadRouteHandoff(normalHandoff())
        assertEquals(listOf(30_000_001, 30_000_004, 30_000_003), controller.state.normalRoute?.systems)
        assertEquals(listOf(30_000_004), controller.state.normalWaypointSystemIds)
        assertTrue(controller.state.useAnsiblex)
        assertFalse(controller.state.message.orEmpty().contains("differs"))

        controller.loadRouteHandoff(capitalHandoff(universeBuild = "older-sde"))
        assertEquals(listOf(30_000_001, 30_000_002, 30_000_003), controller.state.capitalRoute?.systems)
        assertEquals(listOf(30_000_002), controller.state.capitalWaypointSystemIds)
        assertEquals(0.6, controller.state.capitalRangeLy)
        assertTrue(controller.state.message.orEmpty().contains("differs"))
    }
}

private fun normalHandoff() = RouteHandoffDto(
    routeHandoffId = "01991d67-5672-7514-9369-482ed563c641",
    workspaceId = "01991d67-5672-7514-9369-482ed563c642",
    publisher = handoffPublisher(),
    createdAt = "2026-09-08T01:00:00Z",
    expiresAt = "2026-09-15T01:00:00Z",
    type = "NORMAL",
    originSystemId = 30_000_001,
    waypointSystemIds = listOf(30_000_004),
    destinationSystemId = 30_000_003,
    useAnsiblex = true,
    resolvedSystemIds = listOf(30_000_001, 30_000_004, 30_000_003),
    resolvedEdges = listOf(
        RouteHandoffResolvedEdgeDto(30_000_001, 30_000_004, "ANSIBLEX"),
        RouteHandoffResolvedEdgeDto(30_000_004, 30_000_003, "STARGATE"),
    ),
    mapMetadata = RouteHandoffMapMetadataDto("1", "1.8.0", "fixture-1"),
)

private fun capitalHandoff(universeBuild: String) = RouteHandoffDto(
    routeHandoffId = "01991d67-5672-7514-9369-482ed563c643",
    workspaceId = "01991d67-5672-7514-9369-482ed563c642",
    publisher = handoffPublisher(),
    createdAt = "2026-09-08T01:00:00Z",
    expiresAt = "2026-09-15T01:00:00Z",
    type = "CAPITAL",
    originSystemId = 30_000_001,
    waypointSystemIds = listOf(30_000_002),
    destinationSystemId = 30_000_003,
    capitalRangeLy = 0.6,
    jumpProfileId = "manual",
    resolvedSystemIds = listOf(30_000_001, 30_000_002, 30_000_003),
    resolvedEdges = listOf(
        RouteHandoffResolvedEdgeDto(30_000_001, 30_000_002, "CAPITAL", 0.5),
        RouteHandoffResolvedEdgeDto(30_000_002, 30_000_003, "CAPITAL", 0.5),
    ),
    mapMetadata = RouteHandoffMapMetadataDto(universeBuild, "1.8.0", "fixture-1"),
)

private fun handoffPublisher() = RouteHandoffPublisherDto(
    memberId = "01991d67-5672-7514-9369-482ed563c644",
    userId = "01991d67-5672-7514-9369-482ed563c645",
    displayName = "Pilot",
    deviceTokenId = "01991d67-5672-7514-9369-482ed563c646",
    deviceName = "Desktop",
)
