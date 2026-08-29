package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.PackId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

/**
 * Development-only preflight that exercises the real Pack Control and isolated Pack classloader
 * with the extracted Portable runtime. It stops immediately after browser launch succeeds or a
 * sanitized SSO stage reports failure; it never waits for or processes an OAuth callback.
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 2) {
        "PortableEsiSsoPackPreflight requires <esi-pack.jar> <report-path>"
    }
    val pack = Path.of(arguments[0]).toAbsolutePath().normalize()
    val report = Path.of(arguments[1]).toAbsolutePath().normalize()
    require(Files.isRegularFile(pack)) { "ESI Pack JAR does not exist: $pack" }

    val root = createTempDirectory("portable-esi-sso-pack-preflight-")
    val events = Collections.synchronizedList(mutableListOf<String>())
    var runtime: ProductionFeaturePackRuntime? = null
    var invoked = false
    var reachedTerminalStage = false
    var timedOut = false
    var observedStageEvents = emptyList<String>()
    try {
        val packRoot = root.resolve("feature-packs")
        val installedPack = packRoot.resolve("esi.pack/pack.jar")
        installedPack.parent.createDirectories()
        Files.copy(pack, installedPack, StandardCopyOption.REPLACE_EXISTING)
        PropertiesFeaturePackManagerStateStore(root.resolve("feature-pack-manager.properties")).save(
            mapOf(PackId("esi.pack") to StoredFeaturePackState(enabled = true)),
        )

        runtime = ProductionFeaturePackRuntime.start(packRoot, root, events::add)
        check(runtime.startReport.loadedPackIds == listOf(PackId("esi.pack"))) {
            "ESI Pack did not load through the production host"
        }
        val controls = runtime.packControlHost.state.value.single { it.packId == PackId("esi.pack") }
        val connect = controls.actions.single { it.key.actionId == "connect" }
        invoked = runtime.packControlHost.invoke(connect.key)
        check(invoked) { "Production Pack Control Host rejected the ESI Connect action" }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val stageEvents = synchronized(events) { events.filter(::isSsoStageEvent) }
            observedStageEvents = stageEvents
            val current = runtime.packControlHost.state.value.single { it.packId == PackId("esi.pack") }
            reachedTerminalStage = stageEvents.any(::isPreflightTerminalEvent) ||
                (current.busyActionId == null && current.lastStatus != null)
            if (reachedTerminalStage) break
            Thread.sleep(10)
        }
        timedOut = !reachedTerminalStage
    } finally {
        if (observedStageEvents.isEmpty()) {
            observedStageEvents = synchronized(events) { events.filter(::isSsoStageEvent) }
        }
        val controlState = runtime?.packControlHost?.state?.value?.singleOrNull {
            it.packId == PackId("esi.pack")
        }
        val closeReport = runtime?.closeSafely()
        report.parent?.let(Files::createDirectories)
        Files.writeString(report, buildString {
            appendLine("javaHome=${System.getProperty("java.home")}")
            appendLine("packInvoked=$invoked")
            appendLine("reachedTerminalStage=$reachedTerminalStage")
            appendLine("timedOut=$timedOut")
            appendLine("controlBusy=${controlState?.busyActionId != null}")
            appendLine("controlStatus=${controlState?.lastStatus?.name.orEmpty()}")
            appendLine("stageEvents=${observedStageEvents.joinToString("|")}")
            appendLine("closeFailures=${closeReport?.failures?.joinToString(",") { it.kind.name }.orEmpty()}")
        })
        root.toFile().deleteRecursively()
    }

    check(reachedTerminalStage) { "Timed out before the production ESI Connect path emitted a terminal stage" }
    val stageEvents = observedStageEvents
    check(stageEvents.none { "outcome=FAILED" in it }) {
        "Production ESI Connect preflight reported a failed stage"
    }
    check(stageEvents.any {
        "stage=BROWSER_LAUNCH" in it && "outcome=SUCCEEDED" in it
    }) { "Production ESI Connect path did not complete browser launch" }
    println("Portable production-host ESI SSO preflight PASS")
    stageEvents.forEach(::println)
}

private fun isSsoStageEvent(event: String): Boolean = "EVE SSO stage:" in event

private fun isPreflightTerminalEvent(event: String): Boolean =
    "outcome=FAILED" in event ||
        ("stage=BROWSER_LAUNCH" in event && "outcome=SUCCEEDED" in event) ||
        ("stage=AUTH_CONNECTED" in event && "outcome=SUCCEEDED" in event)
