package dev.evestaticmapplanner.marker.application

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMarkerServiceTest {
    @Test
    fun `initial load exposes existing markers and children through authoritative state`() = runTest {
        val existing = savedMarker(1, "Existing")
        val repository = FakeSavedMarkerRepository(listOf(existing)).apply {
            seedChild(1, "staging")
        }
        val service = service(repository)
        advanceUntilIdle()

        assertFalse(service.state.value.isLoading)
        assertNull(service.state.value.databaseError)
        assertEquals(existing, service.get(1))
        assertEquals(listOf(existing), service.getAll())
        assertEquals(listOf("staging"), service.state.value.childrenByParentSystemId[1]?.map { it.type.key })
    }

    @Test
    fun `create update and delete publish observable state before each operation returns`() = runTest {
        val repository = FakeSavedMarkerRepository()
        val service = service(repository)
        advanceUntilIdle()

        val created = service.create(7, MarkerDraft.create(name = "Created"))
        assertEquals(created, service.state.value.markersBySystemId[7])
        assertEquals(emptyList(), service.state.value.childrenByParentSystemId[7])

        val updated = service.update(7, MarkerDraft.create(name = "Updated"))
        assertEquals(updated, service.state.value.markersBySystemId[7])
        assertEquals("Updated", service.get(7)?.name)

        assertTrue(service.delete(7))
        assertFalse(7 in service.state.value.markersBySystemId)
        assertFalse(7 in service.state.value.childrenByParentSystemId)
    }

    @Test
    fun `child mutations publish observable state before each operation returns`() = runTest {
        val repository = FakeSavedMarkerRepository(listOf(savedMarker(9, "Parent")))
        val service = service(repository)
        advanceUntilIdle()

        val child = service.addChild(9, SavedMarkerChildType.of("danger"))
        assertEquals(listOf(child), service.state.value.childrenByParentSystemId[9])

        assertTrue(service.removeChild(9, child.id))
        assertEquals(emptyList(), service.state.value.childrenByParentSystemId[9])
    }

    @Test
    fun `duplicate system behavior remains a repository failure and preserves state`() = runTest {
        val original = savedMarker(5, "Original")
        val repository = FakeSavedMarkerRepository(listOf(original))
        val service = service(repository)
        advanceUntilIdle()

        val error = assertFailsWith<IllegalStateException> {
            service.create(5, MarkerDraft.create(name = "Duplicate"))
        }

        assertEquals("duplicate marker", error.message)
        assertEquals(original, service.state.value.markersBySystemId[5])
    }

    @Test
    fun `repository mutation failures propagate without changing authoritative state`() = runTest {
        val original = savedMarker(3, "Original")
        val repository = FakeSavedMarkerRepository(listOf(original))
        val service = service(repository)
        advanceUntilIdle()
        val before = service.state.value

        repository.failure = Failure.CREATE
        assertFailsWith<IllegalStateException> { service.create(4, MarkerDraft.create()) }
        repository.failure = Failure.UPDATE
        assertFailsWith<IllegalStateException> { service.update(3, MarkerDraft.create(name = "Changed")) }
        repository.failure = Failure.DELETE
        assertFailsWith<IllegalStateException> { service.delete(3) }
        repository.failure = Failure.ADD_CHILD
        assertFailsWith<IllegalStateException> { service.addChild(3, SavedMarkerChildType.of("danger")) }
        repository.failure = Failure.REMOVE_CHILD
        assertFailsWith<IllegalStateException> { service.removeChild(3, "missing") }

        assertEquals(before, service.state.value)
    }

    @Test
    fun `load failure and unavailable database retain degraded behavior`() = runTest {
        val failed = service(FakeSavedMarkerRepository().apply { failure = Failure.LOAD })
        advanceUntilIdle()
        assertFalse(failed.state.value.isLoading)
        assertNotNull(failed.state.value.databaseError)
        assertFailsWith<IllegalStateException> { failed.create(1, MarkerDraft.create()) }

        val unavailable = SavedMarkerService(
            repository = null,
            userDatabaseError = "damaged user.db",
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals("damaged user.db", unavailable.state.value.databaseError)
        assertFailsWith<IllegalStateException> { unavailable.create(2, MarkerDraft.create()) }
    }

    private fun TestScope.service(repository: SavedMarkerRepository): SavedMarkerService = SavedMarkerService(
        repository = repository,
        userDatabaseError = null,
        scope = this,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )
}

private enum class Failure {
    LOAD,
    CREATE,
    UPDATE,
    DELETE,
    ADD_CHILD,
    REMOVE_CHILD,
}

private class FakeSavedMarkerRepository(initial: List<Marker> = emptyList()) : SavedMarkerRepository {
    private val markers = initial.associateByTo(linkedMapOf(), Marker::systemId)
    private val children = linkedMapOf<Int, MutableList<SavedMarkerChild>>()
    var failure: Failure? = null
    private var tick = 0L

    override fun getAll(): List<Marker> {
        failIf(Failure.LOAD)
        return markers.values.toList()
    }

    override fun create(systemId: Int, draft: MarkerDraft): Marker {
        failIf(Failure.CREATE)
        check(systemId !in markers) { "duplicate marker" }
        val instant = Instant.EPOCH.plusSeconds(++tick)
        return Marker.saved(systemId, draft, instant, instant).also { markers[systemId] = it }
    }

    override fun update(systemId: Int, draft: MarkerDraft): Marker {
        failIf(Failure.UPDATE)
        val current = checkNotNull(markers[systemId])
        return Marker.saved(
            systemId,
            draft,
            checkNotNull(current.createdAt),
            Instant.EPOCH.plusSeconds(++tick),
        ).also { markers[systemId] = it }
    }

    override fun delete(systemId: Int): Boolean {
        failIf(Failure.DELETE)
        return (markers.remove(systemId) != null).also { removed ->
            if (removed) children.remove(systemId)
        }
    }

    override fun getChildren(parentSystemId: Int): List<SavedMarkerChild> =
        children[parentSystemId].orEmpty().sortedWith(compareBy(SavedMarkerChild::orderIndex, SavedMarkerChild::id))

    override fun getAllChildren(): Map<Int, List<SavedMarkerChild>> =
        children.keys.sorted().associateWith(::getChildren)

    override fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild {
        failIf(Failure.ADD_CHILD)
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
        failIf(Failure.REMOVE_CHILD)
        return children[parentSystemId]?.removeAll { it.id == childId } == true
    }

    fun seedChild(parentSystemId: Int, type: String) {
        children.getOrPut(parentSystemId, ::mutableListOf).add(
            SavedMarkerChild.create(
                id = "seed-${++tick}",
                parentSystemId = parentSystemId,
                type = SavedMarkerChildType.of(type),
                orderIndex = children[parentSystemId]?.size ?: 0,
            ),
        )
    }

    private fun failIf(expected: Failure) {
        if (failure == expected) error("forced ${expected.name.lowercase()} failure")
    }
}

private fun savedMarker(systemId: Int, name: String): Marker = Marker.saved(
    systemId = systemId,
    draft = MarkerDraft.create(name = name),
    createdAt = Instant.parse("2026-08-21T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-21T00:00:00Z"),
)
