package dev.evestaticmapplanner

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.RepositorySystemReadPort
import dev.evestaticmapplanner.control.RoutePlanningPort
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.route.CapitalRouteOutcome
import dev.evestaticmapplanner.core.route.RouteCalculationOutcome
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.embeddedai.GetSystemInfoTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EmbeddedAiPlannerDataIntegrationTest {
    @Test
    fun `native Koog tool reads system information through the real Planner service and repository`() = runTest {
        val directory = createTempDirectory("embedded-ai-planner-data-")
        val database = directory.resolve("static.db")
        try {
            createJitaFixture(database)
            val service = plannerService(database)
            try {
                val result = GetSystemInfoTool(service).execute(GetSystemInfoTool.Args(JITA_SYSTEM_ID))

                assertTrue(result.contains("\"systemId\":30000142"))
                assertTrue(result.contains("\"name\":\"Jita\""))
                assertTrue(result.contains("\"regionName\":\"The Forge\""))
                assertTrue(result.contains("\"constellationName\":\"Kimotoro\""))
                assertTrue(result.contains("\"stargateCount\":0"))
            } finally {
                service.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
            assertTrue(!Files.exists(directory))
        }
    }

    @Test
    fun `native Koog tool can read Jita from an explicitly supplied real SDE database`() = runTest {
        val database = System.getenv("EVE_STATIC_DB")?.let(Path::of) ?: return@runTest
        val service = plannerService(database)
        val tool = GetSystemInfoTool(service)
        val question = "Tell me about system 30000142"
        val expectedAnswer = "Jita is in The Forge."
        val executor = getMockExecutor {
            mockLLMToolCall(tool, GetSystemInfoTool.Args(JITA_SYSTEM_ID)) onRequestEquals question
            mockLLMAnswer(expectedAnswer) onRequestContains "\"name\":\"Jita\""
        }
        try {
            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = OpenAIModels.Chat.GPT4oMini,
                toolRegistry = ToolRegistry { tool(tool) },
                systemPrompt = "Use get_system_info for numeric system IDs and never invent map data.",
                strategy = singleRunStrategy(),
                maxIterations = 6,
            )

            assertEquals(expectedAnswer, agent.run(question))
        } finally {
            executor.close()
            service.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.plannerService(database: Path) = DefaultMapControlService(
        systemReadPort = RepositorySystemReadPort(
            SqliteSystemSearchRepository(database),
            SqliteUniverseRepository(database),
        ),
        routePlanningPort = unusedRoutePlanningPort,
        jumpPlanningPort = JumpPlanningPort { _, _ -> error("Jump planning is not used by this test") },
        viewportControlPort = unusedViewportControlPort,
        missionRenderStatePort = MissionRenderStatePort { },
        scope = this,
    )
}

private fun createJitaFixture(database: java.nio.file.Path) {
    StaticDatabaseBuildSession.create(database).use { session ->
        session.insert(Region(THE_FORGE_REGION_ID, "The Forge", UniversePosition(0.0, 0.0, 0.0), null))
        session.insert(
            Constellation(
                KIMOTORO_CONSTELLATION_ID,
                THE_FORGE_REGION_ID,
                "Kimotoro",
                UniversePosition(0.0, 0.0, 0.0),
                null,
            ),
        )
        session.insert(
            SolarSystem(
                id = JITA_SYSTEM_ID,
                constellationId = KIMOTORO_CONSTELLATION_ID,
                regionId = THE_FORGE_REGION_ID,
                name = "Jita",
                securityStatus = 0.945913116664154,
                securityClass = "A",
                position = UniversePosition(
                    -1.2906486217319209E17,
                    6.07553069223847E16,
                    1.1746922706072243E17,
                ),
                schematicPosition = null,
                radius = 1.0,
                factionId = 500001,
                wormholeClassId = null,
            ),
        )
        session.commit()
    }
}

private val unusedRoutePlanningPort = object : RoutePlanningPort {
    override suspend fun calculateNormalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): RouteCalculationOutcome = error("Normal routing is not used by this test")

    override suspend fun calculateCapitalRoute(
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): CapitalRouteOutcome = error("Capital routing is not used by this test")
}

private val unusedViewportControlPort = object : ViewportControlPort {
    override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.APP_NOT_READY
    override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.APP_NOT_READY
}

private const val JITA_SYSTEM_ID = 30_000_142
private const val THE_FORGE_REGION_ID = 10_000_002
private const val KIMOTORO_CONSTELLATION_ID = 20_000_020
