package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MarkerViewModelTest {
    @Test
    fun `temporary workflow is session-only defaults yellow and never calls persistent mutations`() = runTest {
        val repository = FakeSavedMarkerRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()

        assertTrue(viewModel.addTemporary(10))
        assertEquals(MarkerColor.YELLOW, viewModel.state.value.markersBySystemId[10]?.color)
        assertTrue(viewModel.updateTemporary(10, MarkerDraft.create(name = " Camp ", notes = " Note ", color = MarkerColor.RED)))
        val edited = assertNotNull(viewModel.state.value.markersBySystemId[10])
        assertEquals(MarkerPersistence.TEMPORARY, edited.persistence)
        assertEquals("Camp", edited.name)
        assertEquals("Note", edited.notes)
        assertEquals(MarkerColor.RED, edited.color)
        assertNull(edited.createdAt)
        assertTrue(viewModel.removeTemporary(10))

        assertEquals(0, repository.createCalls)
        assertEquals(0, repository.updateCalls)
        assertEquals(0, repository.deleteCalls)

        viewModel.addTemporary(11)
        val restarted = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()
        assertFalse(11 in restarted.state.value.markersBySystemId)
    }

    @Test
    fun `clear removes only temporary markers and preserves loaded saved markers`() = runTest {
        val saved = savedMarker(20, "Saved")
        val repository = FakeSavedMarkerRepository(listOf(saved))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()
        viewModel.addTemporary(10)
        viewModel.addTemporary(11)

        assertTrue(viewModel.clearTemporaryMarkers())

        assertEquals(mapOf(20 to saved), viewModel.state.value.markersBySystemId)
        assertEquals(0, repository.deleteCalls)
    }

    @Test
    fun `saved markers load create update and delete only after repository success`() = runTest {
        val initial = savedMarker(1, "Initial")
        val repository = FakeSavedMarkerRepository(listOf(initial))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()
        assertEquals(initial, viewModel.state.value.markersBySystemId[1])

        assertTrue(viewModel.createSaved(2, MarkerDraft.create(name = "New", color = MarkerColor.GREEN)))
        assertFalse(2 in viewModel.state.value.markersBySystemId)
        assertTrue(2 in viewModel.state.value.busySystemIds)
        advanceUntilIdle()
        assertEquals("New", viewModel.state.value.markersBySystemId[2]?.name)

        assertTrue(viewModel.updateSaved(2, MarkerDraft.create(name = "Edited", color = MarkerColor.BLUE)))
        assertEquals("New", viewModel.state.value.markersBySystemId[2]?.name)
        advanceUntilIdle()
        assertEquals("Edited", viewModel.state.value.markersBySystemId[2]?.name)

        assertTrue(viewModel.removeSaved(2))
        assertTrue(2 in viewModel.state.value.markersBySystemId)
        advanceUntilIdle()
        assertFalse(2 in viewModel.state.value.markersBySystemId)
    }

    @Test
    fun `one marker per system is enforced for loaded saved and temporary markers`() = runTest {
        val repository = FakeSavedMarkerRepository(listOf(savedMarker(1, "Saved")))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)

        assertFalse(viewModel.addTemporary(2))
        assertFalse(viewModel.state.value.canCreateMarkers)
        advanceUntilIdle()

        assertFalse(viewModel.addTemporary(1))
        assertFalse(viewModel.createSaved(1, MarkerDraft.create()))
        assertTrue(viewModel.addTemporary(2))
        assertFalse(viewModel.addTemporary(2))
        assertFalse(viewModel.createSaved(2, MarkerDraft.create()))
        assertEquals(setOf(1, 2), viewModel.state.value.markersBySystemId.keys)
    }

    @Test
    fun `database load failure leaves persistent state unknown and disables creation`() = runTest {
        val repository = FakeSavedMarkerRepository().apply { loadFailure = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertNotNull(viewModel.state.value.databaseError)
        assertFalse(viewModel.state.value.canCreateMarkers)
        assertFalse(viewModel.addTemporary(1))
        assertFalse(viewModel.createSaved(1, MarkerDraft.create()))

        val unavailable = MarkerViewModel(null, "damaged user.db", this, dispatcher)
        assertEquals("damaged user.db", unavailable.state.value.databaseError)
        assertFalse(unavailable.addTemporary(2))
    }

    @Test
    fun `temporary conversion preserves fields and busy blocks concurrent mutation`() = runTest {
        val repository = FakeSavedMarkerRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()
        viewModel.addTemporary(7)
        viewModel.updateTemporary(7, MarkerDraft.create("Staging", "Fleet area", MarkerColor.PURPLE))
        val temporary = assertNotNull(viewModel.state.value.markersBySystemId[7])

        assertTrue(viewModel.saveTemporaryPermanently(7))
        assertTrue(7 in viewModel.state.value.busySystemIds)
        assertFalse(viewModel.updateTemporary(7, MarkerDraft.create(name = "Racing update")))
        assertEquals(temporary, viewModel.state.value.markersBySystemId[7])
        advanceUntilIdle()

        val saved = assertNotNull(viewModel.state.value.markersBySystemId[7])
        assertEquals(MarkerPersistence.SAVED, saved.persistence)
        assertEquals(temporary.name, saved.name)
        assertEquals(temporary.notes, saved.notes)
        assertEquals(temporary.color, saved.color)
        assertEquals(1, repository.createCalls)
        assertFalse(7 in viewModel.state.value.busySystemIds)
    }

    @Test
    fun `conversion failure keeps temporary marker exactly unchanged`() = runTest {
        val repository = FakeSavedMarkerRepository().apply { createFailure = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()
        viewModel.addTemporary(8)
        viewModel.updateTemporary(8, MarkerDraft.create("Keep", "Keep notes", MarkerColor.ORANGE))
        val before = viewModel.state.value.markersBySystemId[8]

        assertTrue(viewModel.saveTemporaryPermanently(8))
        advanceUntilIdle()

        assertEquals(before, viewModel.state.value.markersBySystemId[8])
        assertNotNull(viewModel.state.value.operationError)
        assertFalse(8 in viewModel.state.value.busySystemIds)
    }

    @Test
    fun `saved create edit and delete failures never change runtime marker data`() = runTest {
        val original = savedMarker(3, "Original")
        val repository = FakeSavedMarkerRepository(listOf(original))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MarkerViewModel(repository, null, this, dispatcher)
        advanceUntilIdle()

        repository.createFailure = true
        assertTrue(viewModel.createSaved(4, MarkerDraft.create(name = "Must fail")))
        advanceUntilIdle()
        assertFalse(4 in viewModel.state.value.markersBySystemId)

        repository.updateFailure = true
        assertTrue(viewModel.updateSaved(3, MarkerDraft.create(name = "Must not replace")))
        advanceUntilIdle()
        assertEquals(original, viewModel.state.value.markersBySystemId[3])

        repository.deleteFailure = true
        assertTrue(viewModel.removeSaved(3))
        advanceUntilIdle()
        assertEquals(original, viewModel.state.value.markersBySystemId[3])
        assertNotNull(viewModel.state.value.operationError)
    }
}

private class FakeSavedMarkerRepository(initial: List<Marker> = emptyList()) : SavedMarkerRepository {
    private val markers = initial.associateByTo(linkedMapOf(), Marker::systemId)
    private val children = linkedMapOf<Int, MutableList<SavedMarkerChild>>()
    var loadFailure = false
    var createFailure = false
    var updateFailure = false
    var deleteFailure = false
    var createCalls = 0
    var updateCalls = 0
    var deleteCalls = 0
    private var tick = 0L

    override fun getAll(): List<Marker> {
        if (loadFailure) error("forced marker load failure")
        return markers.values.toList()
    }

    override fun create(systemId: Int, draft: MarkerDraft): Marker {
        createCalls++
        if (createFailure) error("forced marker create failure")
        check(systemId !in markers) { "duplicate marker" }
        val instant = Instant.EPOCH.plusSeconds(++tick)
        return Marker.saved(systemId, draft, instant, instant).also { markers[systemId] = it }
    }

    override fun update(systemId: Int, draft: MarkerDraft): Marker {
        updateCalls++
        if (updateFailure) error("forced marker update failure")
        val current = checkNotNull(markers[systemId])
        return Marker.saved(
            systemId,
            draft,
            checkNotNull(current.createdAt),
            Instant.EPOCH.plusSeconds(++tick),
        ).also { markers[systemId] = it }
    }

    override fun delete(systemId: Int): Boolean {
        deleteCalls++
        if (deleteFailure) error("forced marker delete failure")
        return (markers.remove(systemId) != null).also { removed ->
            if (removed) children.remove(systemId)
        }
    }

    override fun getChildren(parentSystemId: Int): List<SavedMarkerChild> =
        children[parentSystemId].orEmpty().sortedWith(compareBy(SavedMarkerChild::orderIndex, SavedMarkerChild::id))

    override fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild {
        check(parentSystemId in markers) { "missing parent marker" }
        val owned = children.getOrPut(parentSystemId, ::mutableListOf)
        check(owned.none { it.type == type }) { "duplicate child type" }
        return SavedMarkerChild.create(
            id = "child-${++tick}",
            parentSystemId = parentSystemId,
            type = type,
            orderIndex = (owned.maxOfOrNull(SavedMarkerChild::orderIndex) ?: -1) + 1,
        ).also(owned::add)
    }

    override fun removeChild(parentSystemId: Int, childId: String): Boolean {
        val owned = children[parentSystemId] ?: return false
        return owned.removeAll { it.id == childId }
    }
}

private fun savedMarker(systemId: Int, name: String): Marker = Marker.saved(
    systemId = systemId,
    draft = MarkerDraft.create(name = name),
    createdAt = Instant.parse("2026-08-21T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-21T00:00:00Z"),
)
