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
import dev.evestaticmapplanner.control.AddMissionMarkerCommand
import dev.evestaticmapplanner.control.AiSavedMarkerControlAdapter
import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.ClearMissionCommand
import dev.evestaticmapplanner.control.ControlErrorCode
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.GetSystemMarkersRequest
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.control.mission.MissionMarkerRole
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
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

    @Test
    fun `real SQLite concurrent UI and Control create has exactly one durable winner`() = runTest {
        val root = createTempDirectory("control-marker-concurrency-")
        try {
            val database = root.resolve("user.db")
            UserDatabase.initialize(database)
            val repository = SqliteSavedMarkerRepository(database, initializeDatabase = false)
            val savedMarkerService = SavedMarkerService(repository, null, this, Dispatchers.IO)
            savedMarkerService.state.first { !it.isLoading }
            val aiService = AiSavedMarkerApplicationService(
                savedMarkerService,
                SingleSystemUniverseRepository,
                AiSavedMarkerPermissionPolicy { true },
            )
            val control = controlService(this, AiSavedMarkerControlAdapter(aiService))
            val start = CompletableDeferred<Unit>()
            try {
                val ui = async(Dispatchers.Default) {
                    start.await()
                    runCatching { savedMarkerService.create(1, MarkerDraft.create(name = "UI")) }
                }
                val controlCreate = async(Dispatchers.Default) {
                    start.await()
                    control.createSavedMarker(
                        CreateSavedMarkerCommand("control", "control-key", 1, "Control", null, MarkerColor.GREEN),
                    )
                }
                start.complete(Unit)
                awaitAll(ui, controlCreate)

                val uiResult = ui.await()
                val controlResult = controlCreate.await()
                val controlSucceeded = controlResult is ControlResult.Success
                assertEquals(1, listOf(uiResult.isSuccess, controlSucceeded).count { it })
                if (!controlSucceeded) {
                    assertEquals(
                        ControlErrorCode.MARKER_ALREADY_EXISTS,
                        assertIs<ControlResult.Failure>(controlResult).error.code,
                    )
                }
                val persisted = SqliteSavedMarkerRepository(database, initializeDatabase = false).getAll().single()
                assertEquals(persisted, savedMarkerService.state.value.markersBySystemId[1])
                assertEquals(if (persisted.createdBy == SavedMarkerCreatedBy.USER) "UI" else "Control", persisted.name)
            } finally {
                control.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `real marker application aggregation keeps saved marker after Mission cleanup`() = runTest {
        val root = createTempDirectory("control-marker-lifecycle-")
        try {
            val database = root.resolve("user.db")
            UserDatabase.initialize(database)
            val repository = SqliteSavedMarkerRepository(database, initializeDatabase = false)
            val savedMarkerService = SavedMarkerService(repository, null, this, Dispatchers.IO)
            savedMarkerService.state.first { !it.isLoading }
            val aiService = AiSavedMarkerApplicationService(
                savedMarkerService,
                SingleSystemUniverseRepository,
                AiSavedMarkerPermissionPolicy { true },
            )
            val control = controlService(this, AiSavedMarkerControlAdapter(aiService))
            try {
                assertIs<ControlResult.Success<*>>(
                    control.createSavedMarker(
                        CreateSavedMarkerCommand("create", "create", 1, "Durable", "Notes", MarkerColor.BLUE),
                    ),
                )
                val mission = assertIs<ControlResult.Success<*>>(
                    control.beginMission(BeginMissionCommand("begin", "begin", "Transient")),
                ).value as dev.evestaticmapplanner.control.MissionSummaryDto
                assertIs<ControlResult.Success<*>>(
                    control.addMissionMarker(
                        AddMissionMarkerCommand("mission-marker", "mission-marker", mission.missionId, 1, MissionMarkerRole.RALLY),
                    ),
                )
                val before = assertIs<ControlResult.Success<*>>(
                    control.getSystemMarkers(GetSystemMarkersRequest("before", 1)),
                ).value as dev.evestaticmapplanner.control.SystemMarkersDto
                assertTrue(before.savedMarker != null && before.missionMarkers.size == 1)

                assertIs<ControlResult.Success<*>>(
                    control.clearMission(ClearMissionCommand("clear", "clear", mission.missionId)),
                )
                val after = assertIs<ControlResult.Success<*>>(
                    control.getSystemMarkers(GetSystemMarkersRequest("after", 1)),
                ).value as dev.evestaticmapplanner.control.SystemMarkersDto
                assertEquals(SavedMarkerCreatedBy.AI, after.savedMarker?.createdBy)
                assertTrue(after.missionMarkers.isEmpty())
                assertEquals(
                    "Durable",
                    SqliteSavedMarkerRepository(database, initializeDatabase = false).getAll().single().name,
                )
            } finally {
                control.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Control marker operations preserve default deny through real application adapter`() = runTest {
        val root = createTempDirectory("control-marker-denied-")
        try {
            val database = root.resolve("user.db")
            UserDatabase.initialize(database)
            val repository = SqliteSavedMarkerRepository(database, initializeDatabase = false)
            val savedMarkerService = SavedMarkerService(repository, null, this, Dispatchers.IO)
            savedMarkerService.state.first { !it.isLoading }
            val before = savedMarkerService.state.value
            val aiService = AiSavedMarkerApplicationService(
                savedMarkerService,
                SingleSystemUniverseRepository,
                AiSavedMarkerPermissionPolicy { false },
            )
            val control = controlService(this, AiSavedMarkerControlAdapter(aiService))
            try {
                val read = assertIs<ControlResult.Failure>(
                    control.getSystemMarkers(GetSystemMarkersRequest("read-denied", 1)),
                )
                val create = assertIs<ControlResult.Failure>(
                    control.createSavedMarker(
                        CreateSavedMarkerCommand("create-denied", "create-denied", 1, "Denied", null, MarkerColor.RED),
                    ),
                )
                assertEquals(ControlErrorCode.CAPABILITY_DENIED, read.error.code)
                assertEquals(ControlErrorCode.CAPABILITY_DENIED, create.error.code)
                assertEquals(before, savedMarkerService.state.value)
                assertTrue(repository.getAll().isEmpty())
            } finally {
                control.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Control adapter keeps missing system and unavailable database errors distinct`() = runTest {
        val unavailable = SavedMarkerService(null, "damaged user.db", this, Dispatchers.IO)
        val aiService = AiSavedMarkerApplicationService(
            unavailable,
            SingleSystemUniverseRepository,
            AiSavedMarkerPermissionPolicy { true },
        )
        val control = controlService(this, AiSavedMarkerControlAdapter(aiService))
        try {
            assertEquals(
                ControlErrorCode.SYSTEM_NOT_FOUND,
                assertIs<ControlResult.Failure>(
                    control.getSystemMarkers(GetSystemMarkersRequest("missing", 99)),
                ).error.code,
            )
            assertEquals(
                ControlErrorCode.DATABASE_UNAVAILABLE,
                assertIs<ControlResult.Failure>(
                    control.getSystemMarkers(GetSystemMarkersRequest("database-read", 1)),
                ).error.code,
            )
            assertEquals(
                ControlErrorCode.DATABASE_UNAVAILABLE,
                assertIs<ControlResult.Failure>(
                    control.createSavedMarker(
                        CreateSavedMarkerCommand("database-create", "database-create", 1, null, null, MarkerColor.YELLOW),
                    ),
                ).error.code,
            )
        } finally {
            control.close()
        }
    }
}

private fun controlService(
    scope: kotlinx.coroutines.CoroutineScope,
    markerPort: dev.evestaticmapplanner.control.SavedMarkerControlPort,
): DefaultMapControlService {
    val system = SystemSummaryDto(1, "One", 100, 10, 0.0)
    return DefaultMapControlService(
        systemReadPort = object : SystemReadPort {
            override suspend fun searchSystems(query: String, limit: Int) = listOf(system)
            override suspend fun getSystemInfo(systemId: Int) = if (systemId == 1) {
                SystemInfoDto(system, "Region", "Constellation", 0.0, 0.0, 0.0, 0)
            } else {
                null
            }
        },
        routePlanningPort = object : RoutePlanningPort {
            override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
                RouteCalculationOutcome.InvalidEndpoint(emptySet(), startSystemId, destinationSystemId)
            override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) =
                CapitalRouteOutcome.InvalidEndpoint(emptySet())
        },
        jumpPlanningPort = JumpPlanningPort { _, _ -> error("unused") },
        viewportControlPort = object : ViewportControlPort {
            override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.COMPLETED
            override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.COMPLETED
        },
        missionRenderStatePort = MissionRenderStatePort { },
        scope = scope,
        savedMarkerControlPort = markerPort,
    )
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
