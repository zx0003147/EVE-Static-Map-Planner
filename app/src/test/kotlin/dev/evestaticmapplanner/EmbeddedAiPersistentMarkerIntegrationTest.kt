package dev.evestaticmapplanner

import dev.evestaticmapplanner.control.AiSavedMarkerControlAdapter
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemReadPort
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.core.marker.SavedMarkerCreatedBy
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.SolarSystemDetails
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.data.repository.SqliteSavedMarkerRepository
import dev.evestaticmapplanner.embeddedai.AiActionConfirmationService
import dev.evestaticmapplanner.embeddedai.CreateSavedMarkerTool
import dev.evestaticmapplanner.embeddedai.EmbeddedAiAgent
import dev.evestaticmapplanner.embeddedai.EmbeddedAiAgentFactory
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.marker.application.AiSavedMarkerApplicationService
import dev.evestaticmapplanner.marker.application.AiSavedMarkerPermissionPolicy
import dev.evestaticmapplanner.marker.application.SavedMarkerService
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedAiPersistentMarkerIntegrationTest {
    @Test
    fun `approved embedded AI marker persists after repository reopen`() = runTest {
        val database = createTempDirectory("embedded-ai-marker").resolve("user.db")
        val repository = SqliteSavedMarkerRepository(database)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val markerService = SavedMarkerService(repository, null, this, dispatcher)
        advanceUntilIdle()
        markerService.state.first { !it.isLoading }
        val applicationService = AiSavedMarkerApplicationService(
            markerService,
            JitaUniverse,
            AiSavedMarkerPermissionPolicy { true },
        )
        val control = markerControlService(this, AiSavedMarkerControlAdapter(applicationService))
        val confirmations = AiActionConfirmationService()
        val tool = CreateSavedMarkerTool(control, confirmations)
        val controller = EmbeddedAiController(
            agentFactory = EmbeddedAiAgentFactory {
                object : EmbeddedAiAgent {
                    override suspend fun run(prompt: String): String = tool.execute(
                        CreateSavedMarkerTool.Args(JITA.systemId, name = "Trade"),
                    )

                    override suspend fun close() = Unit
                }
            },
            dispatcher = dispatcher,
            confirmationService = confirmations,
        )
        try {
            controller.send("In Jita, permanently save a marker named Trade.")
            runCurrent()
            val pending = assertNotNull(controller.confirmation.value)
            assertTrue(controller.approveAction(pending.actionId))
            advanceUntilIdle()

            assertTrue(controller.state.value.response.contains("\"success\":true"))
            val reopened = SqliteSavedMarkerRepository(database)
            val marker = reopened.getAll().single()
            assertEquals("Trade", marker.name)
            assertEquals(SavedMarkerCreatedBy.AI, marker.createdBy)
        } finally {
            controller.shutdown()
            control.close()
        }
    }
}

private fun markerControlService(
    scope: kotlinx.coroutines.CoroutineScope,
    markerPort: dev.evestaticmapplanner.control.SavedMarkerControlPort,
): DefaultMapControlService = DefaultMapControlService(
    systemReadPort = object : SystemReadPort {
        override suspend fun searchSystems(query: String, limit: Int) = listOf(JITA)
        override suspend fun getSystemInfo(systemId: Int) = if (systemId == JITA.systemId) {
            SystemInfoDto(JITA, "The Forge", "Kimotoro", 1.0, 2.0, 3.0, 7)
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

private object JitaUniverse : UniverseRepository {
    private val system = SolarSystem(
        id = JITA.systemId,
        constellationId = JITA.constellationId,
        regionId = JITA.regionId,
        name = JITA.name,
        securityStatus = JITA.securityStatus,
        securityClass = null,
        position = UniversePosition(1.0, 2.0, 3.0),
        schematicPosition = SchematicPosition(1.0, 2.0),
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

private val JITA = SystemSummaryDto(30_000_142, "Jita", 10_000_002, 20_000_020, 0.9459)
