package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.MarkerDraft
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.repository.SqliteSavedMarkerRepository
import dev.evestaticmapplanner.marker.application.AiSavedMarkerApplicationService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerCapability
import dev.evestaticmapplanner.marker.application.AiSavedMarkerCreateRequest
import dev.evestaticmapplanner.marker.application.AiSavedMarkerErrorCode
import dev.evestaticmapplanner.marker.application.AiSavedMarkerPermissionPolicy
import dev.evestaticmapplanner.marker.application.AiSavedMarkerResult
import dev.evestaticmapplanner.marker.application.SavedMarkerAlreadyExistsException
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiSavedMarkerConcurrencyIntegrationTest {
    @Test
    fun `real SQLite concurrent UI and AI create has one winner without overwrite`() = runTest {
        val root = createTempDirectory("ai-marker-concurrency-")
        try {
            val database = root.resolve("user.db")
            UserDatabase.initialize(database)
            val repository = SqliteSavedMarkerRepository(database, initializeDatabase = false)
            val savedMarkerService = SavedMarkerService(repository, null, this, Dispatchers.IO)
            savedMarkerService.state.first { !it.isLoading }
            val aiService = AiSavedMarkerApplicationService(
                savedMarkerService,
                SingleSystemUniverseRepository,
                AiSavedMarkerPermissionPolicy { capability ->
                    capability == AiSavedMarkerCapability.CREATE_SAVED_MARKERS
                },
            )
            val start = CompletableDeferred<Unit>()

            val ui = async(Dispatchers.Default) {
                start.await()
                runCatching { savedMarkerService.create(1, MarkerDraft.create(name = "UI")) }
            }
            val ai = async(Dispatchers.Default) {
                start.await()
                aiService.createSavedMarker(AiSavedMarkerCreateRequest(1, name = "AI"))
            }
            start.complete(Unit)
            awaitAll(ui, ai)

            val uiResult = ui.await()
            val aiResult = ai.await()
            val aiSucceeded = aiResult is AiSavedMarkerResult.Success
            assertEquals(1, listOf(uiResult.isSuccess, aiSucceeded).count { it })
            if (uiResult.isFailure) assertIs<SavedMarkerAlreadyExistsException>(uiResult.exceptionOrNull())
            if (!aiSucceeded) {
                assertEquals(
                    AiSavedMarkerErrorCode.MARKER_ALREADY_EXISTS,
                    assertIs<AiSavedMarkerResult.Failure>(aiResult).error.code,
                )
            }

            val persisted = SqliteSavedMarkerRepository(database, initializeDatabase = false).getAll().single()
            assertEquals(persisted, savedMarkerService.state.value.markersBySystemId[1])
            assertTrue(persisted.createdBy == SavedMarkerCreatedBy.USER || persisted.createdBy == SavedMarkerCreatedBy.AI)
            assertEquals(if (persisted.createdBy == SavedMarkerCreatedBy.USER) "UI" else "AI", persisted.name)
            assertEquals(4, UserDatabase.open(database).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        check(result.next())
                        result.getInt(1)
                    }
                }
            })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private object SingleSystemUniverseRepository : UniverseRepository {
    private val system = SolarSystem(
        id = 1,
        constellationId = 10,
        regionId = 100,
        name = "One",
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(0.0, 0.0, 0.0),
        schematicPosition = SchematicPosition(0.0, 0.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    override fun getRegion(id: Int): Region? = null
    override fun getConstellation(id: Int): Constellation? = null
    override fun getSystem(id: Int): SolarSystem? = system.takeIf { it.id == id }
    override fun findSystemByName(name: String): SolarSystem? = system.takeIf { it.name == name }
    override fun getSystemDetails(id: Int): SolarSystemDetails? = null
}
