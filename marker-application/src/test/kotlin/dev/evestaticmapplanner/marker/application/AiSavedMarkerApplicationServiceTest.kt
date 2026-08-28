package dev.evestaticmapplanner.marker.application

import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.SavedMarkerRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSavedMarkerApplicationServiceTest {
    @Test
    fun `default deny distinguishes denied read from absent marker and does no work`() = runTest {
        val fixture = fixture()
        fixture.awaitReady()
        val before = fixture.savedMarkerService.state.value

        val deniedRead = fixture.aiService.getSystemMarker(1)
        val deniedCreate = fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(1, name = "Denied"))

        assertFailure(AiSavedMarkerErrorCode.CAPABILITY_DENIED, deniedRead)
        assertFailure(AiSavedMarkerErrorCode.CAPABILITY_DENIED, deniedCreate)
        assertEquals(0, fixture.universe.getCalls.get())
        assertEquals(0, fixture.repository.createCalls.get())
        assertEquals(before, fixture.savedMarkerService.state.value)

        fixture.permissions.allow(AiSavedMarkerCapability.READ_SAVED_MARKERS)
        assertEquals(AiSavedMarkerResult.Success(null), fixture.aiService.getSystemMarker(1))
    }

    @Test
    fun `read permission returns notes children and provenance without granting create`() = runTest {
        val marker = savedMarker(1, "Safe", "Private notes", SavedMarkerCreatedBy.AI)
        val fixture = fixture(listOf(marker)).apply {
            repository.seedChild(1, "staging")
            permissions.allow(AiSavedMarkerCapability.READ_SAVED_MARKERS)
        }
        fixture.awaitReady()

        val result = assertIs<AiSavedMarkerResult.Success<AiSavedMarkerSummary?>>(fixture.aiService.getSystemMarker(1))
        val summary = assertIs<AiSavedMarkerSummary>(result.value)

        assertEquals(1, summary.systemId)
        assertEquals("Safe", summary.name)
        assertEquals("Private notes", summary.notes)
        assertEquals(MarkerColor.BLUE, summary.color)
        assertEquals(SavedMarkerCreatedBy.AI, summary.createdBy)
        assertEquals(listOf("staging"), summary.children.map { it.type.key })
        assertFailure(
            AiSavedMarkerErrorCode.CAPABILITY_DENIED,
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(2)),
        )
    }

    @Test
    fun `permission changes take effect immediately without rebuilding service`() = runTest {
        val fixture = fixture()
        fixture.awaitReady()

        assertFailure(AiSavedMarkerErrorCode.CAPABILITY_DENIED, fixture.aiService.getSystemMarker(1))
        fixture.permissions.allowAll()
        assertEquals(AiSavedMarkerResult.Success(null), fixture.aiService.getSystemMarker(1))
        assertIs<AiSavedMarkerResult.Success<AiSavedMarkerSummary>>(
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(1, name = "Allowed")),
        )
        fixture.permissions.denyAll()
        assertFailure(AiSavedMarkerErrorCode.CAPABILITY_DENIED, fixture.aiService.getSystemMarker(1))
        assertFailure(
            AiSavedMarkerErrorCode.CAPABILITY_DENIED,
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(2)),
        )
    }

    @Test
    fun `AI create records provenance and publishes state before returning and after reload`() = runTest {
        val fixture = fixture().apply { permissions.allow(AiSavedMarkerCapability.CREATE_SAVED_MARKERS) }
        fixture.awaitReady()

        val result = assertIs<AiSavedMarkerResult.Success<AiSavedMarkerSummary>>(
            fixture.aiService.createSavedMarker(
                AiSavedMarkerCreateRequest(1, name = "  AI marker  ", notes = "  Notes  ", color = MarkerColor.GREEN),
            ),
        )

        assertEquals("AI marker", result.value.name)
        assertEquals("Notes", result.value.notes)
        assertEquals(SavedMarkerCreatedBy.AI, result.value.createdBy)
        assertEquals(SavedMarkerCreatedBy.AI, fixture.repository.getAll().single().createdBy)
        assertEquals(SavedMarkerCreatedBy.AI, fixture.savedMarkerService.state.value.markersBySystemId[1]?.createdBy)

        val reloaded = SavedMarkerService(fixture.repository, null, this, Dispatchers.Default)
        reloaded.state.first { !it.isLoading }
        assertEquals(SavedMarkerCreatedBy.AI, reloaded.state.value.markersBySystemId[1]?.createdBy)
    }

    @Test
    fun `normal shared service create remains USER`() = runTest {
        val fixture = fixture()
        fixture.awaitReady()

        val marker = fixture.savedMarkerService.create(1, MarkerDraft.create(name = "User marker"))

        assertEquals(SavedMarkerCreatedBy.USER, marker.createdBy)
        assertEquals(SavedMarkerCreatedBy.USER, fixture.repository.getAll().single().createdBy)
    }

    @Test
    fun `duplicate USER or AI marker is a conflict and never overwrites`() = runTest {
        listOf(SavedMarkerCreatedBy.USER, SavedMarkerCreatedBy.AI).forEach { provenance ->
            val original = savedMarker(1, "Original", "Keep", provenance)
            val fixture = fixture(listOf(original)).apply {
                permissions.allow(AiSavedMarkerCapability.CREATE_SAVED_MARKERS)
            }
            fixture.awaitReady()

            val result = fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(1, name = "Replacement"))

            assertFailure(AiSavedMarkerErrorCode.MARKER_ALREADY_EXISTS, result)
            assertEquals(original, fixture.repository.getAll().single())
            assertEquals(original, fixture.savedMarkerService.get(1))
        }
    }

    @Test
    fun `concurrent UI and AI create has exactly one winner and matching repository state`() = runTest {
        val fixture = fixture().apply { permissions.allow(AiSavedMarkerCapability.CREATE_SAVED_MARKERS) }
        fixture.awaitReady()
        val start = CompletableDeferred<Unit>()

        val ui = async(Dispatchers.Default) {
            start.await()
            runCatching { fixture.savedMarkerService.create(1, MarkerDraft.create(name = "UI")) }
        }
        val ai = async(Dispatchers.Default) {
            start.await()
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(1, name = "AI"))
        }
        start.complete(Unit)
        awaitAll(ui, ai)

        val uiSucceeded = ui.await().isSuccess
        val aiResult = ai.await()
        val aiSucceeded = aiResult is AiSavedMarkerResult.Success
        assertEquals(1, listOf(uiSucceeded, aiSucceeded).count { it })
        if (!aiSucceeded) assertFailure(AiSavedMarkerErrorCode.MARKER_ALREADY_EXISTS, aiResult)
        if (!uiSucceeded) assertIs<SavedMarkerAlreadyExistsException>(ui.await().exceptionOrNull())
        assertEquals(1, fixture.repository.getAll().size)
        assertEquals(fixture.repository.getAll().single(), fixture.savedMarkerService.state.value.markersBySystemId[1])
        assertTrue(fixture.repository.getAll().single().createdBy in SavedMarkerCreatedBy.entries)
    }

    @Test
    fun `invalid system unsupported children and unavailable database are distinct failures`() = runTest {
        val fixture = fixture().apply { permissions.allowAll() }
        fixture.awaitReady()

        assertFailure(
            AiSavedMarkerErrorCode.INVALID_ARGUMENT,
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(0)),
        )
        assertFailure(
            AiSavedMarkerErrorCode.SYSTEM_NOT_FOUND,
            fixture.aiService.createSavedMarker(AiSavedMarkerCreateRequest(99)),
        )
        assertFailure(
            AiSavedMarkerErrorCode.INVALID_MARKER_DATA,
            fixture.aiService.createSavedMarker(
                AiSavedMarkerCreateRequest(1, children = setOf(SavedMarkerChildType.of("staging"))),
            ),
        )
        assertEquals(0, fixture.repository.createCalls.get())

        val unavailable = SavedMarkerService(null, "damaged user.db", this, Dispatchers.Default)
        val service = AiSavedMarkerApplicationService(unavailable, fixture.universe, fixture.permissions)
        assertFailure(AiSavedMarkerErrorCode.DATABASE_UNAVAILABLE, service.getSystemMarker(1))
    }

    @Test
    fun `AI application surface has no update delete clear or generic mutation methods`() {
        val names = AiSavedMarkerApplicationService::class.java.declaredMethods.map { it.name.lowercase() }

        assertFalse(names.any { "update" in it })
        assertFalse(names.any { "delete" in it })
        assertFalse(names.any { "clear" in it })
        assertFalse(names.any { "mutate" in it })
    }
}

private data class Fixture(
    val repository: ConcurrentSavedMarkerRepository,
    val universe: CountingUniverseRepository,
    val permissions: MutablePermissionPolicy,
    val savedMarkerService: SavedMarkerService,
    val aiService: AiSavedMarkerApplicationService,
) {
    suspend fun awaitReady() {
        savedMarkerService.state.first { !it.isLoading }
    }
}

private fun kotlinx.coroutines.test.TestScope.fixture(initial: List<Marker> = emptyList()): Fixture {
    val repository = ConcurrentSavedMarkerRepository(initial)
    val universe = CountingUniverseRepository(listOf(system(1), system(2)))
    val permissions = MutablePermissionPolicy()
    val savedMarkerService = SavedMarkerService(repository, null, this, Dispatchers.Default)
    return Fixture(
        repository,
        universe,
        permissions,
        savedMarkerService,
        AiSavedMarkerApplicationService(savedMarkerService, universe, permissions),
    )
}

private class MutablePermissionPolicy : AiSavedMarkerPermissionPolicy {
    private val allowed = ConcurrentHashMap.newKeySet<AiSavedMarkerCapability>()

    override fun isAllowed(capability: AiSavedMarkerCapability): Boolean = capability in allowed
    fun allow(capability: AiSavedMarkerCapability) {
        allowed += capability
    }
    fun allowAll() {
        allowed += AiSavedMarkerCapability.entries
    }
    fun denyAll() = allowed.clear()
}

private class CountingUniverseRepository(systems: List<SolarSystem>) : UniverseRepository {
    private val systems = systems.associateBy(SolarSystem::id)
    val getCalls = AtomicInteger()
    override fun getRegion(id: Int): Region? = null
    override fun getConstellation(id: Int): Constellation? = null
    override fun getSystem(id: Int): SolarSystem? = systems[id].also { getCalls.incrementAndGet() }
    override fun findSystemByName(name: String): SolarSystem? = null
    override fun getSystemDetails(id: Int): SolarSystemDetails? = null
}

private class ConcurrentSavedMarkerRepository(initial: List<Marker>) : SavedMarkerRepository {
    private val markers = ConcurrentHashMap(initial.associateBy(Marker::systemId))
    private val children = ConcurrentHashMap<Int, MutableList<SavedMarkerChild>>()
    private val ticks = AtomicInteger()
    val createCalls = AtomicInteger()

    override fun getAll(): List<Marker> = markers.values.sortedBy(Marker::systemId)

    override fun create(systemId: Int, draft: MarkerDraft, createdBy: SavedMarkerCreatedBy): Marker {
        createCalls.incrementAndGet()
        val instant = Instant.EPOCH.plusSeconds(ticks.incrementAndGet().toLong())
        val marker = Marker.saved(systemId, draft, instant, instant, createdBy)
        check(markers.putIfAbsent(systemId, marker) == null) { "duplicate marker" }
        return marker
    }

    override fun update(systemId: Int, draft: MarkerDraft): Marker = error("not used")
    override fun delete(systemId: Int): Boolean = error("not used")
    override fun getChildren(parentSystemId: Int): List<SavedMarkerChild> = children[parentSystemId].orEmpty()
    override fun getAllChildren(): Map<Int, List<SavedMarkerChild>> = children.mapValues { it.value.toList() }
    override fun addChild(parentSystemId: Int, type: SavedMarkerChildType): SavedMarkerChild = error("not used")
    override fun removeChild(parentSystemId: Int, childId: String): Boolean = error("not used")

    fun seedChild(parentSystemId: Int, type: String) {
        children[parentSystemId] = mutableListOf(
            SavedMarkerChild.create("child-1", parentSystemId, SavedMarkerChildType.of(type), 0),
        )
    }
}

private fun system(id: Int) = SolarSystem(
    id = id,
    constellationId = 10,
    regionId = 100,
    name = "System $id",
    securityStatus = 0.0,
    securityClass = null,
    position = UniversePosition(id.toDouble(), 0.0, 0.0),
    schematicPosition = SchematicPosition(id.toDouble(), 0.0),
    radius = 1.0,
    factionId = null,
    wormholeClassId = null,
)

private fun savedMarker(
    systemId: Int,
    name: String,
    notes: String,
    createdBy: SavedMarkerCreatedBy,
) = Marker.saved(
    systemId,
    MarkerDraft.create(name, notes, MarkerColor.BLUE),
    Instant.parse("2026-08-28T00:00:00Z"),
    Instant.parse("2026-08-28T00:00:00Z"),
    createdBy,
)

private fun assertFailure(expected: AiSavedMarkerErrorCode, result: AiSavedMarkerResult<*>) {
    assertEquals(expected, assertIs<AiSavedMarkerResult.Failure>(result).error.code)
}
