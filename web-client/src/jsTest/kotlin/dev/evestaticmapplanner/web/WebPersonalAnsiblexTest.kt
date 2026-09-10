package dev.evestaticmapplanner.web

import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.core.route.NormalNavigationOutcome
import dev.evestaticmapplanner.core.route.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebPersonalAnsiblexTest {
    private val universe = WebUniverseDataAdapter.adapt(fixtureDocument())

    @Test
    fun `CSV parses Desktop columns direction invalid rows and duplicates against Web Pack`() {
        val preview = PersonalAnsiblexParser.parse(
            "links.csv",
            """from_system_name,to_system_name,direction,enabled
                |Alpha,Delta,FORWARD,true
                |Beta,Isolated,FORWARD,false
                |Gamma,Gamma,BIDIRECTIONAL,true
                |Missing,Beta,BIDIRECTIONAL,true
                |Beta,Isolated,FORWARD,false
            """.trimMargin(),
            universe.staticData.systems,
            universe.packAnsiblexLinks,
            emptyList(),
        )

        assertEquals(listOf(2, 6), preview.duplicateRows)
        assertEquals(2, preview.errors.size)
        assertEquals(1, preview.valid.size)
        assertEquals(WebAnsiblexDirection.FIRST_TO_SECOND, preview.valid.single().direction)
        assertFalse(preview.valid.single().enabled)
        assertFalse(preview.canApply)
    }

    @Test
    fun `Desktop JSON parses endpoints reverse direction and enabled state`() {
        val preview = PersonalAnsiblexParser.parse(
            "links.json",
            """{"format_version":1,"connections":[{
                |"from":{"system_name":"Isolated"},"to":{"system_id":30000002},
                |"direction":"FORWARD","enabled":true
                |}]}""".trimMargin(),
            universe.staticData.systems,
            universe.packAnsiblexLinks,
            emptyList(),
        )

        assertTrue(preview.errors.isEmpty())
        val draft = preview.valid.single()
        assertEquals(30_000_005, draft.fromSystemId)
        assertEquals(30_000_002, draft.toSystemId)
        assertEquals(WebAnsiblexDirection.SECOND_TO_FIRST, draft.direction)
    }

    @Test
    fun `Desktop JSON rejects missing or unsupported format version`() {
        listOf(
            """{"connections":[]}""",
            """{"format_version":2,"connections":[]}""",
        ).forEach { content ->
            val preview = PersonalAnsiblexParser.parse(
                "links.json",
                content,
                universe.staticData.systems,
                universe.packAnsiblexLinks,
                emptyList(),
            )

            assertEquals("Desktop JSON format_version must be 1.", preview.errors.single().message)
            assertFalse(preview.canApply)
        }
    }

    @Test
    fun `storage reload keeps offline data and effective graph preserves Web Pack precedence`() {
        val storage = MemoryBrowserStore()
        val store = PersonalAnsiblexStore(storage)
        val personal = PersonalAnsiblexConnection(
            "personal-1",
            30_000_002,
            30_000_005,
            WebAnsiblexDirection.BIDIRECTIONAL,
        )
        store.save(listOf(personal))
        assertEquals(listOf(personal), PersonalAnsiblexStore(storage).load())

        val graph = universe.routeGraphWith(store.load())
        val route = assertIs<NormalNavigationOutcome.Found>(
            universe.normalPlanner.calculate(
                graph,
                NavigationIntent(30_000_001, destinationSystemId = 30_000_005),
                RouteOptions(useAnsiblex = true),
            ),
        ).route
        assertEquals(listOf(30_000_001, 30_000_002, 30_000_005), route.systems)
        assertEquals(1, route.ansiblexJumps)

        val duplicateOfPack = PersonalAnsiblexConnection(
            "duplicate",
            30_000_001,
            30_000_004,
            WebAnsiblexDirection.BIDIRECTIONAL,
        )
        assertTrue(universe.effectivePersonalAnsiblex(listOf(duplicateOfPack)).isEmpty())
        assertEquals(1, universe.packAnsiblexLinks.size)
    }

    @Test
    fun `Keepstar Saved Marker storage is browser local and removal restores the ordinary node`() {
        val storage = MemoryBrowserStore()
        val store = WebKeepstarMarkerStore(storage)

        assertTrue(store.load().isEmpty())
        store.save(setOf(30_000_002, 30_000_004))
        assertEquals(setOf(30_000_002, 30_000_004), WebKeepstarMarkerStore(storage).load())

        store.save(setOf(30_000_004))
        assertEquals(setOf(30_000_004), WebKeepstarMarkerStore(storage).load())
    }

    @Test
    fun `Fortizar Saved Marker storage is browser local and does not alter Keepstar data`() {
        val storage = MemoryBrowserStore()
        val fortizar = WebFortizarMarkerStore(storage)
        val keepstar = WebKeepstarMarkerStore(storage)

        fortizar.save(setOf(30_000_002))
        keepstar.save(setOf(30_000_004))

        assertEquals(setOf(30_000_002), WebFortizarMarkerStore(storage).load())
        assertEquals(setOf(30_000_004), WebKeepstarMarkerStore(storage).load())
    }
}
