package dev.evestaticmapplanner

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.JumpPlanningPort
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.MapControlService
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
import dev.evestaticmapplanner.embeddedai.OpenRouterKoogAgentFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.time.Duration.Companion.minutes

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
                llmModel = LLModel(
                    provider = LLMProvider.OpenRouter,
                    id = OpenRouterKoogAgentFactory.MODEL_ID,
                    capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.ToolChoice),
                ),
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

    @Test
    fun `live OpenRouter DeepSeek calls the native tool against the real SDE database`() = runTest(timeout = 3.minutes) {
        assumeTrue(
            System.getenv(OPENROUTER_SMOKE_ENABLED) == "true",
            "Set $OPENROUTER_SMOKE_ENABLED=true to run the real OpenRouter smoke test",
        )
        val apiKey = requireNotNull(System.getenv(OpenRouterKoogAgentFactory.OPENROUTER_API_KEY)) {
            "OPENROUTER_API_KEY is required when $OPENROUTER_SMOKE_ENABLED=true"
        }
        val database = Path.of(requireNotNull(System.getenv("EVE_STATIC_DB")))
        val report = Path.of(requireNotNull(System.getenv(OPENROUTER_SMOKE_REPORT)))
        val baseService = plannerService(database)
        val requestedIds = mutableListOf<Int>()
        val service = RecordingMapControlService(baseService, requestedIds)
        val diagnostics = mutableListOf<String>()
        val agent = OpenRouterKoogAgentFactory(
            mapControlService = service,
            environment = { name ->
                if (name == OpenRouterKoogAgentFactory.OPENROUTER_API_KEY) apiKey else null
            },
            diagnostics = diagnostics::add,
        ).create()
        try {
            val answer = agent.run("Tell me about system 30000142")

            assertEquals(listOf(JITA_SYSTEM_ID), requestedIds)
            assertTrue(answer.contains("Jita", ignoreCase = true))
            assertTrue(diagnostics.contains(OpenRouterKoogAgentFactory.PROVIDER_DIAGNOSTIC))
            assertTrue(diagnostics.contains(OpenRouterKoogAgentFactory.MODEL_DIAGNOSTIC))
            assertTrue(diagnostics.contains(GetSystemInfoTool.TOOL_CALL_DIAGNOSTIC))
            assertTrue(diagnostics.contains(GetSystemInfoTool.TOOL_SUCCESS_DIAGNOSTIC))
            assertTrue(diagnostics.all(SAFE_DIAGNOSTICS::contains))

            report.parent?.let(Files::createDirectories)
            Files.writeString(
                report,
                buildJsonObject {
                    put("provider", "OpenRouter")
                    put("model", OpenRouterKoogAgentFactory.MODEL_ID)
                    put("toolCall", GetSystemInfoTool.NAME)
                    put("toolResult", "success")
                    put("toolCallCount", requestedIds.size)
                    put("systemId", requestedIds.single())
                    put("finalAnswer", answer)
                    put("actualProviderEndpoint", JsonNull)
                    put("usage", JsonNull)
                    put("costUsd", JsonNull)
                }.toString(),
            )
        } finally {
            agent.close()
            baseService.close()
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

private class RecordingMapControlService(
    private val delegate: MapControlService,
    private val requestedIds: MutableList<Int>,
) : MapControlService by delegate {
    override suspend fun getSystemInfo(request: GetSystemInfoRequest): ControlResult<dev.evestaticmapplanner.control.SystemInfoDto> {
        requestedIds += request.systemId
        return delegate.getSystemInfo(request)
    }
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
private const val OPENROUTER_SMOKE_ENABLED = "OPENROUTER_SMOKE_TEST"
private const val OPENROUTER_SMOKE_REPORT = "OPENROUTER_SMOKE_REPORT"
private val SAFE_DIAGNOSTICS = setOf(
    OpenRouterKoogAgentFactory.PROVIDER_DIAGNOSTIC,
    OpenRouterKoogAgentFactory.MODEL_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_CALL_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_SUCCESS_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_FAILURE_DIAGNOSTIC,
)
