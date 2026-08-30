package dev.evestaticmapplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationShutdownCoordinatorTest {
    @Test
    fun `shutdown suspends instead of blocking the UI dispatcher and closes everything once`() {
        val events = CopyOnWriteArrayList<String>()
        val ui = Executors.newSingleThreadExecutor { task -> Thread(task, "test-ui") }.asCoroutineDispatcher()
        try {
            runBlocking {
                val coordinator = ApplicationShutdownCoordinator(
                    shutdownLocalhostMcp = { events += "localhost-mcp" },
                    shutdownAiControl = {
                        withContext(Dispatchers.Default) {
                            withContext(ui) { events += "mission-clear" }
                        }
                        events += "ai-control"
                    },
                    resourceClosers = listOf(
                        { events += "resource-1" },
                        { events += "resource-2" },
                    ),
                    closeDiagnostics = { events += "diagnostics" },
                    exitApplication = { events += "exit" },
                    warningSink = { label, failure -> error("$label: ${failure.message}") },
                )

                withTimeout(5_000) {
                    withContext(ui) { coordinator.shutdown() }
                }
                coordinator.shutdown()
                coordinator.closeOwnedResources()
            }
        } finally {
            ui.close()
        }

        assertEquals(
            listOf(
                "localhost-mcp",
                "mission-clear",
                "ai-control",
                "resource-1",
                "resource-2",
                "diagnostics",
                "exit",
            ),
            events,
        )
    }

    @Test
    fun `cleanup failures do not skip remaining resources or graceful application exit`() = runBlocking {
        val events = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val coordinator = ApplicationShutdownCoordinator(
            shutdownLocalhostMcp = { error("MCP failure") },
            shutdownAiControl = { error("AI failure") },
            resourceClosers = listOf(
                { error("resource failure") },
                { events += "remaining-resource" },
            ),
            closeDiagnostics = { events += "diagnostics" },
            exitApplication = { events += "exit" },
            warningSink = { label, _ -> warnings += label },
        )

        coordinator.shutdown()

        assertEquals(listOf("remaining-resource", "diagnostics", "exit"), events)
        assertEquals(
            listOf(
                "Application shutdown failed: Localhost MCP",
                "Application shutdown failed: AI Control",
                "Application shutdown failed: application resource 1",
            ),
            warnings,
        )
    }
}
