package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.preferences.MarkerPreferences
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkerManagerPresentationTest {
    private val alpha = saved(1, "Home", MarkerColor.GREEN, "Quiet staging")
    private val bravo = saved(2, "Trade", MarkerColor.BLUE, "Market")
    private val temporary = Marker.temporary(3, MarkerDraft.create(name = "Session"))
    private val names = mapOf(1 to "Amarr", 2 to "Jita", 3 to "1DQ1-A")

    @Test
    fun `manager shows only saved markers in stable system-name order`() {
        val presentation = build(query = "", selectedSystemId = null)

        assertEquals(listOf(1, 2), presentation.rows.map { it.systemId })
        assertEquals(listOf("Amarr", "Jita"), presentation.rows.map { it.systemName })
        assertTrue(presentation.rows.none { it.systemId == temporary.systemId })
        assertNull(presentation.selectedRow)
        assertFalse(presentation.selectionActionsEnabled)
    }

    @Test
    fun `manager remains available when marker rendering preference is off`() {
        val renderingPreference = MarkerPreferences(showMarkers = false, showMarkerNames = false)

        assertFalse(renderingPreference.showMarkers)
        assertEquals(listOf(1, 2), build("", null).rows.map { it.systemId })
    }

    @Test
    fun `manager searches system marker name and notes locally`() {
        assertEquals(listOf(2), build("jit", null).rows.map { it.systemId })
        assertEquals(listOf(1), build("home", null).rows.map { it.systemId })
        assertEquals(listOf(1), build("quiet", null).rows.map { it.systemId })
    }

    @Test
    fun `selection enables actions only while selected saved row remains visible and idle`() {
        assertTrue(build("", 2).selectionActionsEnabled)
        assertFalse(build("amarr", 2).selectionActionsEnabled)
        val busy = state().copy(busySystemIds = setOf(2))
        assertFalse(MarkerManagerPresentationBuilder.build(busy, names, "", 2).selectionActionsEnabled)
    }

    @Test
    fun `temporary and saved conflicts produce distinct non destructive guidance`() {
        assertNull(markerCreationConflict(null))
        assertEquals("This system already has a marker.", markerCreationConflict(alpha))
        assertTrue(markerCreationConflict(temporary)?.contains("Save Permanently") == true)
    }

    @Test
    fun `show button and row double click can share the same select and focus entry point`() {
        val events = mutableListOf<String>()
        val invoke = { showSavedMarkerOnMap(2, { events += "select:$it" }, { events += "focus:$it" }) }

        invoke()
        invoke()

        assertEquals(listOf("select:2", "focus:2", "select:2", "focus:2"), events)
    }

    @Test
    fun `manager add editor requires a system search selection while context editor stays locked`() {
        val managerRequest = MarkerEditorRequest(MarkerEditorMode.CREATE_SAVED, null, null)
        val lockedRequest = MarkerEditorRequest(MarkerEditorMode.EDIT_SAVED, 2, "Jita", bravo)

        assertNull(markerEditorSystemId(managerRequest, MarkerEditorSystemSearch("Jit", emptyList(), null)))
        assertEquals(
            2,
            markerEditorSystemId(managerRequest, MarkerEditorSystemSearch("Jita", emptyList(), system(2, "Jita"))),
        )
        assertEquals(2, markerEditorSystemId(lockedRequest, null))
    }

    private fun build(query: String, selectedSystemId: Int?) =
        MarkerManagerPresentationBuilder.build(state(), names, query, selectedSystemId)

    private fun state() = MarkerUiState(
        isLoading = false,
        markersBySystemId = listOf(alpha, bravo, temporary).associateBy(Marker::systemId),
    )

    private fun saved(systemId: Int, name: String, color: MarkerColor, notes: String) = Marker.saved(
        systemId,
        MarkerDraft.create(name = name, notes = notes, color = color),
        Instant.EPOCH,
        Instant.EPOCH,
    )

    private fun system(id: Int, name: String) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 100,
        name = name,
        securityStatus = 0.9,
        securityClass = null,
        position = UniversePosition(0.0, 0.0, 0.0),
        schematicPosition = SchematicPosition(0.0, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )
}
