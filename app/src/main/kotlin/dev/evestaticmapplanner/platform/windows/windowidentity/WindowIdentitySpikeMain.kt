package dev.evestaticmapplanner.platform.windows.windowidentity

import com.sun.jna.Platform
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object WindowIdentitySpikeMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(Platform.isWindows()) { "The EVE Client Identity spike requires Windows" }
        val duration = parseDuration(arguments)
        val reader = WindowsForegroundWindowSnapshotReader()
        val lastCharacter = AtomicReference<String?>()
        val finished = CountDownLatch(1)

        println("EVE Client Identity Spike")
        println("Mode: EVENT_SYSTEM_FOREGROUND / WINEVENT_OUTOFCONTEXT")
        println("Startup + watchdog fallback: GetForegroundWindow every 2 seconds")
        println("Own PID: ${ProcessHandle.current().pid()}")
        println("Duration: ${duration.seconds} seconds")
        println("No process memory, injection, hooks inside EVE, OCR, credentials, or network access are used.")
        println()

        WindowsForegroundWindowMonitor().use { monitor ->
            Runtime.getRuntime().addShutdownHook(Thread({ finished.countDown() }, "window-identity-spike-shutdown"))
            monitor.start(
                onForegroundChanged = { event ->
                    val snapshot = reader.read(event.hwnd)
                    snapshot.characterName?.let(lastCharacter::set)
                    printSnapshot(event, snapshot, lastCharacter.get())
                },
                onFailure = { failure ->
                    System.err.println("Window identity diagnostic failure: ${failure.message}")
                    failure.printStackTrace(System.err)
                },
            )
            finished.await(duration.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    private fun parseDuration(arguments: Array<String>): Duration {
        if (arguments.isEmpty()) return Duration.ofMinutes(2)
        require(arguments.size == 2 && arguments[0] == "--duration-seconds") {
            "Usage: runWindowIdentitySpike --args='--duration-seconds 300'"
        }
        val seconds = arguments[1].toLongOrNull()
            ?: throw IllegalArgumentException("--duration-seconds requires an integer")
        require(seconds in 1..3_600) { "--duration-seconds must be between 1 and 3600" }
        return Duration.ofSeconds(seconds)
    }

    private fun printSnapshot(
        event: ForegroundWindowEvent,
        snapshot: ForegroundWindowSnapshot,
        lastCharacterName: String?,
    ) {
        println("Foreground changed")
        println("Captured At: ${snapshot.capturedAt}")
        println("Source: ${event.source}")
        println("HWND: ${snapshot.hwnd.asHexHandle()}")
        println("PID: ${snapshot.processId ?: "unavailable"}")
        println("Thread ID: ${snapshot.threadId ?: "unavailable"}")
        println("Executable: ${snapshot.processPath ?: "unavailable"}")
        println("Executable Name: ${snapshot.processName ?: "unavailable"}")
        println("Process Start Time: ${snapshot.processStartTime ?: "unavailable"}")
        println("Class: ${snapshot.className.ifBlank { "unavailable" }}")
        println("Raw Title: ${snapshot.title.ifBlank { "<blank>" }}")
        println("Own Process: ${snapshot.isOwnProcess}")
        println("Classification: ${snapshot.classification}")
        println("Character: ${snapshot.characterName ?: "unavailable"}")
        println("Reason: ${snapshot.reason}")
        println("Session Identity: ${snapshot.sessionIdentity ?: "unavailable"}")
        println("Last Recognized EVE Character: ${lastCharacterName ?: "unavailable"}")
        println()
    }

    private fun Long.asHexHandle(): String = String.format(Locale.ROOT, "0x%016X", this)
}
