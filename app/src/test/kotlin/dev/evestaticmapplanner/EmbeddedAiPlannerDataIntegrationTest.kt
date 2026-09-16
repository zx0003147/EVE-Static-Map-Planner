package dev.evestaticmapplanner

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CalculateNormalRouteRequest
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.ExistingPlanningPorts
import dev.evestaticmapplanner.control.MissionRenderStatePort
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.OptimizeMultiPointRouteRequest
import dev.evestaticmapplanner.control.RepositorySystemReadPort
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.Stargate
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.data.db.StaticDatabaseBuildSession
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteAnsiblexRepository
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.embeddedai.CalculateCapitalRouteTool
import dev.evestaticmapplanner.embeddedai.CalculateNormalRouteTool
import dev.evestaticmapplanner.embeddedai.GetSystemInfoTool
import dev.evestaticmapplanner.embeddedai.OpenRouterKoogAgentFactory
import dev.evestaticmapplanner.embeddedai.OptimizeMultiPointRouteTool
import dev.evestaticmapplanner.embeddedai.SearchSystemTool
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
    fun `Phase 2 tools call the real application service and existing route engines`() = runTest {
        val directory = createTempDirectory("embedded-ai-phase2-")
        val database = directory.resolve("static.db")
        val userDatabase = directory.resolve("user.db")
        try {
            createPhase2RouteFixture(database)
            val ansiblex = SqliteAnsiblexRepository(userDatabase).apply {
                addManual(AnsiblexDraft(PHASE2_FIRST_SYSTEM_ID, PHASE2_FIRST_SYSTEM_ID + 4))
            }
            val service = plannerService(database, ansiblex)
            try {
                val search = SearchSystemTool(service).execute(SearchSystemTool.Args("Fixture 02"))
                val normal = CalculateNormalRouteTool(service).execute(
                    CalculateNormalRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        destinationSystemId = PHASE2_FIRST_SYSTEM_ID + 4,
                        useAnsiblex = false,
                    ),
                )
                val ansiblexRoute = CalculateNormalRouteTool(service).execute(
                    CalculateNormalRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        destinationSystemId = PHASE2_FIRST_SYSTEM_ID + 4,
                        useAnsiblex = true,
                    ),
                )
                val capital = CalculateCapitalRouteTool(service).execute(
                    CalculateCapitalRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        destinationSystemId = PHASE2_FIRST_SYSTEM_ID + 4,
                        effectiveRangeLy = 2.1,
                    ),
                )
                val optimizer = OptimizeMultiPointRouteTool(service)
                val exact = optimizer.execute(
                    OptimizeMultiPointRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        targetSystemIds = listOf(PHASE2_FIRST_SYSTEM_ID + 4, PHASE2_FIRST_SYSTEM_ID + 1),
                        useAnsiblex = false,
                    ),
                )
                val heuristic = optimizer.execute(
                    OptimizeMultiPointRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        targetSystemIds = (1..16).map(PHASE2_FIRST_SYSTEM_ID::plus),
                        useAnsiblex = false,
                    ),
                )
                val duplicateTargets = optimizer.execute(
                    OptimizeMultiPointRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        targetSystemIds = listOf(
                            PHASE2_FIRST_SYSTEM_ID + 1,
                            PHASE2_FIRST_SYSTEM_ID + 1,
                            PHASE2_FIRST_SYSTEM_ID + 4,
                        ),
                        useAnsiblex = false,
                    ),
                )
                val overLimit = optimizer.execute(
                    OptimizeMultiPointRouteTool.Args(
                        startSystemId = PHASE2_FIRST_SYSTEM_ID,
                        targetSystemIds = (1..51).map(PHASE2_FIRST_SYSTEM_ID::plus),
                        useAnsiblex = false,
                    ),
                )

                assertTrue(search.contains("\"systemId\":${PHASE2_FIRST_SYSTEM_ID + 1}"))
                assertTrue(normal.contains("\"totalJumps\":4"))
                assertTrue(normal.contains("\"stargateJumps\":4"))
                assertTrue(ansiblexRoute.contains("\"totalJumps\":1"))
                assertTrue(ansiblexRoute.contains("\"ansiblexJumps\":1"))
                assertTrue(capital.contains("\"totalJumps\":2"))
                assertTrue(capital.contains("\"totalDistanceLy\":4.0"))
                assertTrue(exact.contains("\"method\":\"EXACT_HELD_KARP\""))
                assertTrue(exact.contains("\"totalJumps\":4"))
                assertTrue(heuristic.contains("\"method\":\"HEURISTIC\""))
                assertTrue(heuristic.contains("\"visited\":16"))
                assertTrue(duplicateTargets.contains("\"inputTargetCount\":3"))
                assertTrue(duplicateTargets.contains("\"uniqueTargetCount\":2"))
                assertTrue(overLimit.contains("\"success\":false"))
                assertTrue(overLimit.contains("\"error\":\"TOO_MANY_TARGETS\""))
                assertTrue(overLimit.contains("\"maximumTargetCount\":50"))
            } finally {
                service.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
            assertTrue(!Files.exists(directory))
        }
    }

    @Test
    fun `Phase 2 tools return known routes from an explicitly supplied real SDE database`() = runTest {
        val database = System.getenv("EVE_STATIC_DB")?.let(Path::of) ?: return@runTest
        val service = plannerService(database)
        try {
            suspend fun exactSystem(name: String): dev.evestaticmapplanner.control.SystemSummaryDto {
                val result = assertIs<ControlResult.Success<List<dev.evestaticmapplanner.control.SystemSummaryDto>>>(
                    service.searchSystems(SearchSystemsRequest("phase2-real-$name", name)),
                )
                return result.value.single { it.name.equals(name, ignoreCase = true) }
            }

            val jita = exactSystem("Jita")
            val amarr = exactSystem("Amarr")
            val oneDq = exactSystem("1DQ1-A")
            val nol = exactSystem("NOL-M9")

            val search = SearchSystemTool(service).execute(SearchSystemTool.Args("Jita"))
            val jitaToAmarr = CalculateNormalRouteTool(service).execute(
                CalculateNormalRouteTool.Args(jita.systemId, amarr.systemId, useAnsiblex = false),
            )
            val oneDqToNol = CalculateNormalRouteTool(service).execute(
                CalculateNormalRouteTool.Args(oneDq.systemId, nol.systemId, useAnsiblex = false),
            )

            assertTrue(search.contains("\"systemId\":30000142"))
            assertTrue(jitaToAmarr.contains("\"totalJumps\":11"))
            assertTrue(jitaToAmarr.contains("\"ansiblexJumps\":0"))
            assertTrue(oneDqToNol.contains("\"totalJumps\":6"))
        } finally {
            service.close()
        }
    }

    @Test
    fun `live OpenRouter DeepSeek uses native information route and optimizer tools against real SDE`() = runTest(
        timeout = 6.minutes,
    ) {
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
        val toolCalls = mutableListOf<String>()
        val service = RecordingMapControlService(baseService, requestedIds, toolCalls)
        val diagnostics = mutableListOf<String>()
        try {
            suspend fun ask(prompt: String): String {
                val agent = OpenRouterKoogAgentFactory(
                    mapControlService = service,
                    environment = { name ->
                        if (name == OpenRouterKoogAgentFactory.OPENROUTER_API_KEY) apiKey else null
                    },
                    diagnostics = diagnostics::add,
                ).create()
                return try {
                    agent.run(prompt)
                } finally {
                    agent.close()
                }
            }

            val informationStart = toolCalls.size
            val informationAnswer = ask("Tell me about system 30000142")
            val informationCalls = toolCalls.drop(informationStart)

            val normalStart = toolCalls.size
            val normalAnswer = ask("How many jumps from Jita to Amarr? Use Stargates only.")
            val normalCalls = toolCalls.drop(normalStart)

            val optimizerStart = toolCalls.size
            val optimizerAnswer = ask(
                "Starting from 1DQ1-A, visit NOL-M9 and GE-8JV in the shortest order. Do not use Ansiblex.",
            )
            val optimizerCalls = toolCalls.drop(optimizerStart)

            assertEquals(listOf(JITA_SYSTEM_ID), requestedIds)
            assertEquals(listOf(GetSystemInfoTool.NAME), informationCalls)
            assertEquals(
                listOf(SearchSystemTool.NAME, SearchSystemTool.NAME, CalculateNormalRouteTool.NAME),
                normalCalls,
            )
            assertEquals(
                listOf(
                    SearchSystemTool.NAME,
                    SearchSystemTool.NAME,
                    SearchSystemTool.NAME,
                    OptimizeMultiPointRouteTool.NAME,
                ),
                optimizerCalls,
            )
            assertTrue(informationAnswer.contains("Jita", ignoreCase = true))
            assertTrue(normalAnswer.contains("11"))
            assertTrue(optimizerAnswer.contains("NOL-M9", ignoreCase = true))
            assertTrue(optimizerAnswer.contains("GE-8JV", ignoreCase = true))
            assertTrue(informationAnswer.contains("trade hub", ignoreCase = true).not())
            assertTrue(informationAnswer.contains("high-sec", ignoreCase = true).not())
            assertTrue(informationAnswer.contains("low-sec", ignoreCase = true).not())
            assertTrue(informationAnswer.contains("null-sec", ignoreCase = true).not())
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
                    put("informationToolCalls", buildJsonArray { informationCalls.forEach { add(JsonPrimitive(it)) } })
                    put("normalRouteToolCalls", buildJsonArray { normalCalls.forEach { add(JsonPrimitive(it)) } })
                    put("multiPointToolCalls", buildJsonArray { optimizerCalls.forEach { add(JsonPrimitive(it)) } })
                    put("toolResult", "success")
                    put("toolCallCount", toolCalls.size)
                    put("informationAnswer", informationAnswer)
                    put("normalRouteAnswer", normalAnswer)
                    put("multiPointAnswer", optimizerAnswer)
                    put("actualProviderEndpoint", JsonNull)
                    put("usage", JsonNull)
                    put("costUsd", JsonNull)
                }.toString(),
            )
        } finally {
            baseService.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.plannerService(
        database: Path,
        ansiblexRepository: AnsiblexRepository? = null,
    ): DefaultMapControlService {
        val planning = ExistingPlanningPorts(
            staticMapRepository = SqliteStaticMapRepository(database),
            ansiblexRepository = ansiblexRepository,
            wormholeSessionStore = WormholeSessionStore(),
        )
        return DefaultMapControlService(
            systemReadPort = RepositorySystemReadPort(
                SqliteSystemSearchRepository(database),
                SqliteUniverseRepository(database),
            ),
            routePlanningPort = planning,
            jumpPlanningPort = planning,
            viewportControlPort = unusedViewportControlPort,
            missionRenderStatePort = MissionRenderStatePort { },
            scope = this,
        )
    }
}

private class RecordingMapControlService(
    private val delegate: MapControlService,
    private val requestedIds: MutableList<Int>,
    private val toolCalls: MutableList<String>,
) : MapControlService by delegate {
    override suspend fun getSystemInfo(request: GetSystemInfoRequest): ControlResult<dev.evestaticmapplanner.control.SystemInfoDto> {
        requestedIds += request.systemId
        toolCalls += GetSystemInfoTool.NAME
        return delegate.getSystemInfo(request)
    }

    override suspend fun searchSystems(
        request: SearchSystemsRequest,
    ): ControlResult<List<dev.evestaticmapplanner.control.SystemSummaryDto>> {
        toolCalls += SearchSystemTool.NAME
        return delegate.searchSystems(request)
    }

    override suspend fun calculateNormalRoute(
        request: CalculateNormalRouteRequest,
    ): ControlResult<dev.evestaticmapplanner.control.NormalRouteDto> {
        toolCalls += CalculateNormalRouteTool.NAME
        return delegate.calculateNormalRoute(request)
    }

    override suspend fun optimizeMultiPointRoute(
        request: OptimizeMultiPointRouteRequest,
    ): ControlResult<dev.evestaticmapplanner.control.MultiPointRouteOptimizationDto> {
        toolCalls += OptimizeMultiPointRouteTool.NAME
        return delegate.optimizeMultiPointRoute(request)
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

private fun createPhase2RouteFixture(database: Path) {
    StaticDatabaseBuildSession.create(database).use { session ->
        session.insert(Region(PHASE2_REGION_ID, "Fixture Region", UniversePosition(0.0, 0.0, 0.0), null))
        session.insert(
            Constellation(
                PHASE2_CONSTELLATION_ID,
                PHASE2_REGION_ID,
                "Fixture Constellation",
                UniversePosition(0.0, 0.0, 0.0),
                null,
            ),
        )
        repeat(PHASE2_SYSTEM_COUNT) { index ->
            val systemId = PHASE2_FIRST_SYSTEM_ID + index
            session.insert(
                SolarSystem(
                    id = systemId,
                    constellationId = PHASE2_CONSTELLATION_ID,
                    regionId = PHASE2_REGION_ID,
                    name = "Fixture ${(index + 1).toString().padStart(2, '0')}",
                    securityStatus = -0.5,
                    securityClass = "F",
                    position = UniversePosition(
                        index * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
                        0.0,
                        0.0,
                    ),
                    schematicPosition = null,
                    radius = 1.0,
                    factionId = null,
                    wormholeClassId = null,
                ),
            )
            if (index > 0) {
                val previousId = systemId - 1
                val forwardGateId = 50_000_000 + index * 2
                val reverseGateId = forwardGateId + 1
                val position = UniversePosition(0.0, 0.0, 0.0)
                session.insert(Stargate(forwardGateId, previousId, systemId, reverseGateId, 1, position))
                session.insert(Stargate(reverseGateId, systemId, previousId, forwardGateId, 1, position))
            }
        }
        session.commit()
    }
}

private val unusedViewportControlPort = object : ViewportControlPort {
    override suspend fun focusSystem(systemId: Int) = ViewportOperationOutcome.APP_NOT_READY
    override suspend fun fitSystems(systemIds: Set<Int>) = ViewportOperationOutcome.APP_NOT_READY
}

private const val JITA_SYSTEM_ID = 30_000_142
private const val THE_FORGE_REGION_ID = 10_000_002
private const val KIMOTORO_CONSTELLATION_ID = 20_000_020
private const val PHASE2_REGION_ID = 10_000_001
private const val PHASE2_CONSTELLATION_ID = 20_000_001
private const val PHASE2_FIRST_SYSTEM_ID = 30_000_001
private const val PHASE2_SYSTEM_COUNT = 17
private const val OPENROUTER_SMOKE_ENABLED = "OPENROUTER_SMOKE_TEST"
private const val OPENROUTER_SMOKE_REPORT = "OPENROUTER_SMOKE_REPORT"
private val SAFE_DIAGNOSTICS = setOf(
    OpenRouterKoogAgentFactory.PROVIDER_DIAGNOSTIC,
    OpenRouterKoogAgentFactory.MODEL_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_CALL_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_SUCCESS_DIAGNOSTIC,
    GetSystemInfoTool.TOOL_FAILURE_DIAGNOSTIC,
    SearchSystemTool.TOOL_CALL_DIAGNOSTIC,
    CalculateNormalRouteTool.TOOL_CALL_DIAGNOSTIC,
    CalculateCapitalRouteTool.TOOL_CALL_DIAGNOSTIC,
    OptimizeMultiPointRouteTool.TOOL_CALL_DIAGNOSTIC,
)
