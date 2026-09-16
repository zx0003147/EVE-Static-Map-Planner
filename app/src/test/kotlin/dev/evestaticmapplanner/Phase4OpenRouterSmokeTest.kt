package dev.evestaticmapplanner

import dev.evestaticmapplanner.control.BeginMissionCommand
import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.DefaultMapControlService
import dev.evestaticmapplanner.control.ExistingPlanningPorts
import dev.evestaticmapplanner.control.FitMissionCommand
import dev.evestaticmapplanner.control.FocusSystemCommand
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.MissionMapStateStore
import dev.evestaticmapplanner.control.MissionMutationReceipt
import dev.evestaticmapplanner.control.MissionRouteReceipt
import dev.evestaticmapplanner.control.MissionSummaryDto
import dev.evestaticmapplanner.control.RepositorySystemReadPort
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.ShowJumpRangeCommand
import dev.evestaticmapplanner.control.ShowNormalRouteCommand
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.control.ViewportControlPort
import dev.evestaticmapplanner.control.ViewportOperationOutcome
import dev.evestaticmapplanner.control.MissionJumpRangeReceipt
import dev.evestaticmapplanner.data.repository.SqliteStaticMapRepository
import dev.evestaticmapplanner.data.repository.SqliteSystemSearchRepository
import dev.evestaticmapplanner.data.repository.SqliteUniverseRepository
import dev.evestaticmapplanner.embeddedai.BeginMissionTool
import dev.evestaticmapplanner.embeddedai.ConfiguredKoogAgentFactory
import dev.evestaticmapplanner.embeddedai.FitMissionTool
import dev.evestaticmapplanner.embeddedai.FocusSystemTool
import dev.evestaticmapplanner.embeddedai.OpenRouterKoogAgentFactory
import dev.evestaticmapplanner.embeddedai.SearchSystemTool
import dev.evestaticmapplanner.embeddedai.ShowJumpRangeTool
import dev.evestaticmapplanner.embeddedai.ShowNormalRouteTool
import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class Phase4OpenRouterSmokeTest {
    @Test
    fun `real OpenRouter uses only the safe Phase 4 native map sequence`() = runTest(timeout = 8.minutes) {
        assumeTrue(System.getenv(ENABLED) == "true", "Set $ENABLED=true to run the paid Phase 4 smoke test")
        val database = Path.of(requireNotNull(System.getenv("EVE_STATIC_DB")))
        val mapState = MissionMapStateStore(UnconfinedTestDispatcher(testScheduler))
        val viewport = Phase4SmokeViewport()
        val planning = ExistingPlanningPorts(
            staticMapRepository = SqliteStaticMapRepository(database),
            ansiblexRepository = null,
            wormholeSessionStore = WormholeSessionStore(),
        )
        val service = DefaultMapControlService(
            systemReadPort = RepositorySystemReadPort(
                SqliteSystemSearchRepository(database),
                SqliteUniverseRepository(database),
            ),
            routePlanningPort = planning,
            jumpPlanningPort = planning,
            viewportControlPort = viewport,
            missionRenderStatePort = mapState,
            scope = this,
        )
        val calls = mutableListOf<String>()
        val recording = Phase4RecordingService(service, calls)
        val diagnostics = mutableListOf<String>()
        val agent = ConfiguredKoogAgentFactory(
            mapControlService = recording,
            configSource = { dev.evestaticmapplanner.embeddedai.AiProviderConfig.DefaultOpenRouter },
            credentialResolver = dev.evestaticmapplanner.embeddedai.AiCredentialResolver(
                secureStore = dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore,
                sessionStore = dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore,
            ),
            diagnostics = diagnostics::add,
        ).create()
        try {
            suspend fun runAndCapture(prompt: String): Pair<String, List<String>> {
                val start = calls.size
                val answer = agent.run(prompt)
                return answer to calls.drop(start)
            }

            val (focusAnswer, focusCalls) = runAndCapture("Focus Jita on the map.")
            val (routeAnswer, routeCalls) = runAndCapture(
                "Show the Jita to Amarr route on the map using Stargates only.",
            )
            val (rangeAnswer, rangeCalls) = runAndCapture("Show a 6 LY jump range from 1DQ1-A.")

            assertEquals(listOf(SearchSystemTool.NAME, FocusSystemTool.NAME), focusCalls)
            assertEquals(
                listOf(
                    SearchSystemTool.NAME,
                    SearchSystemTool.NAME,
                    BeginMissionTool.NAME,
                    ShowNormalRouteTool.NAME,
                    FitMissionTool.NAME,
                ),
                routeCalls,
            )
            assertEquals(
                listOf(
                    SearchSystemTool.NAME,
                    BeginMissionTool.NAME,
                    ShowJumpRangeTool.NAME,
                    FitMissionTool.NAME,
                ),
                rangeCalls,
            )
            assertTrue(viewport.focused.contains(30_000_142))
            assertEquals(2, viewport.fitted.size)
            assertEquals(2, mapState.state.value.missions.size)
            assertEquals(1, mapState.state.value.normalRoutes.size)
            assertEquals(1, mapState.state.value.jumpRanges.size)
            assertTrue(diagnostics.none { it.contains(requireNotNull(System.getenv(OpenRouterKoogAgentFactory.OPENROUTER_API_KEY))) })

            val report = System.getenv(REPORT)?.let(Path::of)
            if (report != null) {
                report.parent?.let(Files::createDirectories)
                Files.writeString(
                    report,
                    buildJsonObject {
                        put("provider", "OpenRouter")
                        put("model", OpenRouterKoogAgentFactory.MODEL_ID)
                        put("focusCalls", focusCalls.toJsonArray())
                        put("routeCalls", routeCalls.toJsonArray())
                        put("jumpRangeCalls", rangeCalls.toJsonArray())
                        put("focusAnswer", focusAnswer)
                        put("routeAnswer", routeAnswer)
                        put("jumpRangeAnswer", rangeAnswer)
                        put("missionCount", mapState.state.value.missions.size)
                        put("normalRouteCount", mapState.state.value.normalRoutes.size)
                        put("jumpRangeCount", mapState.state.value.jumpRanges.size)
                        put("newProcesses", 0)
                        put("secondJvm", false)
                    }.toString(),
                )
            }
        } finally {
            agent.close()
            service.close()
        }
    }

    companion object {
        private const val ENABLED = "OPENROUTER_PHASE4_SMOKE_TEST"
        private const val REPORT = "OPENROUTER_PHASE4_SMOKE_REPORT"
    }
}

private class Phase4RecordingService(
    private val delegate: MapControlService,
    private val calls: MutableList<String>,
) : MapControlService by delegate {
    override suspend fun searchSystems(request: SearchSystemsRequest): ControlResult<List<SystemSummaryDto>> {
        calls += SearchSystemTool.NAME
        return delegate.searchSystems(request)
    }

    override suspend fun focusSystem(command: FocusSystemCommand): ControlResult<SystemSummaryDto> {
        calls += FocusSystemTool.NAME
        return delegate.focusSystem(command)
    }

    override suspend fun beginMission(command: BeginMissionCommand): ControlResult<MissionSummaryDto> {
        calls += BeginMissionTool.NAME
        return delegate.beginMission(command)
    }

    override suspend fun showNormalRoute(command: ShowNormalRouteCommand): ControlResult<MissionRouteReceipt> {
        calls += ShowNormalRouteTool.NAME
        return delegate.showNormalRoute(command)
    }

    override suspend fun showJumpRange(command: ShowJumpRangeCommand): ControlResult<MissionJumpRangeReceipt> {
        calls += ShowJumpRangeTool.NAME
        return delegate.showJumpRange(command)
    }

    override suspend fun fitMission(command: FitMissionCommand): ControlResult<MissionMutationReceipt> {
        calls += FitMissionTool.NAME
        return delegate.fitMission(command)
    }
}

private class Phase4SmokeViewport : ViewportControlPort {
    val focused = mutableListOf<Int>()
    val fitted = mutableListOf<Set<Int>>()

    override suspend fun focusSystem(systemId: Int): ViewportOperationOutcome {
        focused += systemId
        return ViewportOperationOutcome.COMPLETED
    }

    override suspend fun fitSystems(systemIds: Set<Int>): ViewportOperationOutcome {
        fitted += systemIds
        return ViewportOperationOutcome.COMPLETED
    }
}

private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
