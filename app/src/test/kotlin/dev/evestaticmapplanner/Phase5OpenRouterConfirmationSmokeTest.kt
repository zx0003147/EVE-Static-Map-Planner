package dev.evestaticmapplanner

import dev.evestaticmapplanner.control.ControlResult
import dev.evestaticmapplanner.control.CreateSavedMarkerCommand
import dev.evestaticmapplanner.control.GetSystemInfoRequest
import dev.evestaticmapplanner.control.MapControlService
import dev.evestaticmapplanner.control.SearchSystemsRequest
import dev.evestaticmapplanner.control.SystemInfoDto
import dev.evestaticmapplanner.control.SystemSummaryDto
import dev.evestaticmapplanner.embeddedai.AiActionConfirmationService
import dev.evestaticmapplanner.embeddedai.AiCredentialResolver
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.ConfiguredKoogAgentFactory
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.embeddedai.UnavailableAiCredentialStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class Phase5OpenRouterConfirmationSmokeTest {
    @Test
    fun `real model can request but cannot execute a persistent action before Planner confirmation`() = runBlocking {
        assumeTrue(System.getenv(ENABLED) == "true", "Set $ENABLED=true to run the paid Phase 5 smoke test")
        val createCalls = AtomicInteger()
        val confirmations = AiActionConfirmationService()
        val factory = ConfiguredKoogAgentFactory(
            mapControlService = smokeService(createCalls),
            configSource = { AiProviderConfig.DefaultOpenRouter },
            credentialResolver = AiCredentialResolver(
                secureStore = UnavailableAiCredentialStore,
                sessionStore = UnavailableAiCredentialStore,
            ),
            actionConfirmationService = confirmations,
        )
        val controller = EmbeddedAiController(factory, confirmationService = confirmations)
        try {
            controller.send("In Jita, permanently save a marker named Trade.")
            val pending = withTimeout(4.minutes) {
                while (controller.confirmation.value == null) {
                    check(controller.state.value.errorMessage == null) { controller.state.value.errorMessage.orEmpty() }
                    delay(25)
                }
                checkNotNull(controller.confirmation.value)
            }
            assertEquals("create_saved_marker", pending.toolName)
            assertEquals(0, createCalls.get())
            assertTrue(controller.denyAction(pending.actionId))
            withTimeout(2.minutes) {
                while (controller.state.value.isLoading) delay(25)
            }
            assertEquals(0, createCalls.get())

            System.getenv(REPORT)?.let(Path::of)?.also { report ->
                report.parent?.let(Files::createDirectories)
                Files.writeString(
                    report,
                    "provider=OpenRouter\ntool=${pending.toolName}\nrisk=${pending.risk}\n" +
                        "executions=${createCalls.get()}\nresult=${controller.state.value.response}\n",
                )
            }
            Unit
        } finally {
            controller.shutdown()
        }
    }

    private companion object {
        const val ENABLED = "OPENROUTER_PHASE5_SMOKE_TEST"
        const val REPORT = "OPENROUTER_PHASE5_SMOKE_REPORT"
    }
}

private fun smokeService(createCalls: AtomicInteger): MapControlService = Proxy.newProxyInstance(
    MapControlService::class.java.classLoader,
    arrayOf(MapControlService::class.java),
) { _, method, arguments ->
    when (method.name) {
        "searchSystems" -> {
            val request = arguments!!.first() as SearchSystemsRequest
            ControlResult.Success(request.requestId, listOf(SMOKE_JITA))
        }
        "getSystemInfo" -> {
            val request = arguments!!.first() as GetSystemInfoRequest
            ControlResult.Success(
                request.requestId,
                SystemInfoDto(SMOKE_JITA, "The Forge", "Kimotoro", 1.0, 2.0, 3.0, 7),
            )
        }
        "createSavedMarker" -> {
            arguments!!.first() as CreateSavedMarkerCommand
            createCalls.incrementAndGet()
            error("Persistent action escaped confirmation smoke")
        }
        "toString" -> "Phase5OpenRouterSmokeService"
        else -> error("Unexpected MapControlService call: ${method.name}")
    }
} as MapControlService

private val SMOKE_JITA = SystemSummaryDto(30_000_142, "Jita", 10_000_002, 20_000_020, 0.9459)
