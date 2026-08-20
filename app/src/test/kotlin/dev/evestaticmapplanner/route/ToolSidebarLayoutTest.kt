package dev.evestaticmapplanner.route

import dev.evestaticmapplanner.capital.CapitalRouteUiState
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.JumpRangeOverlay
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.CapitalRouteResult
import dev.evestaticmapplanner.core.route.RouteResult
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.map.GLOBAL_SEARCH_MAX_WIDTH
import dev.evestaticmapplanner.map.GLOBAL_SEARCH_MIN_WIDTH
import dev.evestaticmapplanner.map.GLOBAL_SEARCH_SUGGESTIONS_PRESENTATION
import dev.evestaticmapplanner.map.confirmGlobalSystemSearch
import dev.evestaticmapplanner.search.SearchSuggestionsPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolSidebarLayoutTest {
    @Test
    fun `sidebar contains only the three tools in requested order`() {
        assertEquals(
            listOf(
                ToolSidebarSection.JUMP_RANGE,
                ToolSidebarSection.NORMAL_ROUTE,
                ToolSidebarSection.CAPITAL_ROUTE,
            ),
            TOOL_SIDEBAR_SECTION_ORDER,
        )
    }

    @Test
    fun `all sections default collapsed and can all expand or collapse`() {
        var state = ToolSidebarExpansionState()
        TOOL_SIDEBAR_SECTION_ORDER.forEach { section ->
            assertFalse(state.isExpanded(section))
            state = state.toggle(section)
        }
        assertEquals(TOOL_SIDEBAR_SECTION_ORDER.toSet(), state.expandedSections)

        TOOL_SIDEBAR_SECTION_ORDER.forEach { section -> state = state.toggle(section) }
        assertTrue(state.expandedSections.isEmpty())
    }

    @Test
    fun `each section toggles independently`() {
        val jumpExpanded = ToolSidebarExpansionState().toggle(ToolSidebarSection.JUMP_RANGE)
        assertTrue(jumpExpanded.isExpanded(ToolSidebarSection.JUMP_RANGE))
        assertFalse(jumpExpanded.isExpanded(ToolSidebarSection.NORMAL_ROUTE))
        assertFalse(jumpExpanded.isExpanded(ToolSidebarSection.CAPITAL_ROUTE))

        val capitalAlsoExpanded = jumpExpanded.toggle(ToolSidebarSection.CAPITAL_ROUTE)
        assertTrue(capitalAlsoExpanded.isExpanded(ToolSidebarSection.JUMP_RANGE))
        assertFalse(capitalAlsoExpanded.isExpanded(ToolSidebarSection.NORMAL_ROUTE))
        assertTrue(capitalAlsoExpanded.isExpanded(ToolSidebarSection.CAPITAL_ROUTE))
    }

    @Test
    fun `collapse state is independent of tool business states`() {
        val route = RouteResult(30_000_142, 30_000_142, listOf(30_000_142), emptyList())
        val profile = JumpProfile.manual(7.5)
        val overlay = JumpRangeOverlay("jita-7.5", 30_000_142, profile, setOf(30_000_142))
        val capitalRoute = CapitalRouteResult(
            30_000_142,
            30_000_142,
            profile,
            listOf(30_000_142),
            emptyList(),
        )
        val routeState = RoutePlannerUiState(
            systemQuery = "Jita",
            fromQuery = "Amarr",
            toQuery = "Dodixie",
            activeRoute = route,
        )
        val jumpState = JumpOverlayUiState(
            originQuery = "1DQ1-A",
            manualRangeText = "7.5",
            overlays = listOf(overlay),
        )
        val capitalState = CapitalRouteUiState(
            fromQuery = "Amarr",
            toQuery = "1DQ1-A",
            manualRangeText = "6",
            activeRoute = capitalRoute,
        )
        var expansion = ToolSidebarExpansionState(TOOL_SIDEBAR_SECTION_ORDER.toSet())

        TOOL_SIDEBAR_SECTION_ORDER.forEach { expansion = expansion.toggle(it) }

        assertTrue(expansion.expandedSections.isEmpty())
        assertEquals("Jita", routeState.systemQuery)
        assertEquals("Amarr", routeState.fromQuery)
        assertEquals("1DQ1-A", jumpState.originQuery)
        assertEquals("7.5", jumpState.manualRangeText)
        assertEquals("Amarr", capitalState.fromQuery)
        assertEquals("6", capitalState.manualRangeText)
        assertSame(route, routeState.activeRoute)
        assertSame(overlay, jumpState.overlays.single())
        assertSame(capitalRoute, capitalState.activeRoute)
    }

    @Test
    fun `global search confirmation updates existing search state then calls focus entry`() {
        val system = system(30_000_142, "Jita")
        val calls = mutableListOf<String>()

        confirmGlobalSystemSearch(
            system = system,
            updateSearchSelection = {
                assertSame(system, it)
                calls += "search"
            },
            focusSystem = {
                assertEquals(system.id, it)
                calls += "focus"
            },
        )

        assertEquals(listOf("search", "focus"), calls)
    }

    @Test
    fun `global search uses bounded toolbar width and popup suggestions`() {
        assertEquals(260, GLOBAL_SEARCH_MIN_WIDTH.value.toInt())
        assertEquals(360, GLOBAL_SEARCH_MAX_WIDTH.value.toInt())
        assertEquals(SearchSuggestionsPresentation.DROPDOWN, GLOBAL_SEARCH_SUGGESTIONS_PRESENTATION)
        assertEquals(270, TOOL_SIDEBAR_WIDTH.value.toInt())
    }

    private fun system(id: Int, name: String) = SolarSystem(
        id = id,
        constellationId = 20_000_020,
        regionId = 10_000_002,
        name = name,
        securityStatus = 0.9,
        securityClass = null,
        position = UniversePosition(1.0, 2.0, 3.0),
        schematicPosition = SchematicPosition(4.0, 5.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
}
