package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerColor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkerPresentationTest {
    @Test
    fun `empty system offers both add actions`() {
        val actions = MarkerContextPresentationBuilder.build(null, readyState())

        assertEquals(
            listOf(MarkerContextAction.ADD_TEMPORARY, MarkerContextAction.ADD_SAVED),
            actions.map(PresentedMarkerContextAction::action),
        )
        assertTrue(actions.all(PresentedMarkerContextAction::enabled))
    }

    @Test
    fun `empty system context is flat strictly ordered and has no duplicate system info`() {
        val actions = SystemContextMenuPresentationBuilder.build(null, readyState()).map { it.action }

        assertEquals(
            listOf(
                SystemContextAction.ADD_TEMPORARY_MARKER,
                SystemContextAction.ADD_SAVED_MARKER,
                SystemContextAction.ADD_JUMP_RANGE_OVERLAY,
                SystemContextAction.SET_ROUTE_START,
                SystemContextAction.SET_ROUTE_DESTINATION,
                SystemContextAction.SET_CAPITAL_START,
                SystemContextAction.SET_CAPITAL_DESTINATION,
                SystemContextAction.CREATE_WORMHOLE,
            ),
            actions,
        )
        assertTrue(actions.none { it.label == "Marker ›" || it.label == "System Info" })
    }

    @Test
    fun `temporary and saved markers expose different actions`() {
        val temporary = Marker.temporary(1)
        val saved = Marker.saved(2, MarkerDraft.create(name = "Home"), Instant.EPOCH, Instant.EPOCH)

        assertEquals(
            listOf(MarkerContextAction.EDIT, MarkerContextAction.SAVE_PERMANENTLY, MarkerContextAction.REMOVE),
            MarkerContextPresentationBuilder.build(temporary, readyState(temporary)).map { it.action },
        )
        assertEquals(
            listOf(MarkerContextAction.EDIT, MarkerContextAction.REMOVE),
            MarkerContextPresentationBuilder.build(saved, readyState(saved)).map { it.action },
        )
    }

    @Test
    fun `temporary and saved system contexts replace illegal marker actions and keep navigation order`() {
        val temporary = Marker.temporary(1)
        val saved = Marker.saved(2, MarkerDraft.create(), Instant.EPOCH, Instant.EPOCH)

        assertEquals(
            listOf(
                SystemContextAction.EDIT_MARKER,
                SystemContextAction.SAVE_MARKER_PERMANENTLY,
                SystemContextAction.REMOVE_MARKER,
                SystemContextAction.ADD_JUMP_RANGE_OVERLAY,
                SystemContextAction.SET_ROUTE_START,
                SystemContextAction.SET_ROUTE_DESTINATION,
                SystemContextAction.SET_CAPITAL_START,
                SystemContextAction.SET_CAPITAL_DESTINATION,
                SystemContextAction.CREATE_WORMHOLE,
            ),
            SystemContextMenuPresentationBuilder.build(temporary, readyState(temporary)).map { it.action },
        )
        assertEquals(
            listOf(
                SystemContextAction.EDIT_MARKER,
                SystemContextAction.REMOVE_MARKER,
                SystemContextAction.ADD_JUMP_RANGE_OVERLAY,
                SystemContextAction.SET_ROUTE_START,
                SystemContextAction.SET_ROUTE_DESTINATION,
                SystemContextAction.SET_CAPITAL_START,
                SystemContextAction.SET_CAPITAL_DESTINATION,
                SystemContextAction.CREATE_WORMHOLE,
            ),
            SystemContextMenuPresentationBuilder.build(saved, readyState(saved)).map { it.action },
        )
    }

    @Test
    fun `Wormhole quick actions are the final section and connections appear only when present`() {
        val none = SystemContextMenuPresentationBuilder.build(null, readyState(), wormholeConnectionCount = 0)
        val multiple = SystemContextMenuPresentationBuilder.build(null, readyState(), wormholeConnectionCount = 4)

        assertEquals(SystemContextAction.CREATE_WORMHOLE, none.last().action)
        assertTrue(none.last().startsNewSection)
        assertTrue(none.none { it.action == SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS })
        assertEquals(
            listOf(SystemContextAction.CREATE_WORMHOLE, SystemContextAction.MANAGE_WORMHOLE_CONNECTIONS),
            multiple.takeLast(2).map { it.action },
        )
        assertEquals("Wormhole Connections… (4)", multiple.last().label)
        assertFalse(multiple.last().startsNewSection)
    }

    @Test
    fun `database failure disables marker creation without hiding the menu category`() {
        val actions = MarkerContextPresentationBuilder.build(
            marker = null,
            state = MarkerUiState(isLoading = false, databaseError = "user db unavailable"),
        )

        assertEquals(listOf(MarkerContextAction.UNAVAILABLE), actions.map { it.action })
        assertFalse(actions.single().enabled)
    }

    @Test
    fun `fixed seven color palette maps every domain color to a distinct visible color`() {
        val colors = MarkerColor.entries.map(::markerColor)

        assertEquals(7, colors.size)
        assertEquals(7, colors.distinct().size)
        assertTrue(colors.all { it.alpha > 0f })
    }

    private fun readyState(marker: Marker? = null) = MarkerUiState(
        isLoading = false,
        markersBySystemId = marker?.let { mapOf(it.systemId to it) }.orEmpty(),
    )
}
