package dev.evestaticmapplanner

import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object AppDiagnostics {
    private val logger = Logger.getLogger("dev.evestaticmapplanner.application").apply {
        useParentHandlers = false
        level = Level.INFO
    }

    @Volatile
    private var initialized = false

    fun initialize(applicationRoot: Path = ApplicationDirectories.root()) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                val logDirectory = applicationRoot.resolve("logs")
                Files.createDirectories(logDirectory)
                FileHandler(
                    logDirectory.resolve("app-%g.log").toString(),
                    1_048_576,
                    3,
                    true,
                ).apply {
                    level = Level.INFO
                    formatter = SimpleFormatter()
                    logger.addHandler(this)
                }
            }
            initialized = true
        }
    }

    fun info(message: String) = logger.log(Level.INFO, message)

    fun warning(message: String, error: Throwable? = null) = logger.log(Level.WARNING, message, error)

    fun fatal(message: String, error: Throwable? = null) = logger.log(Level.SEVERE, message, error)

    fun close() {
        logger.handlers.forEach {
            runCatching { it.flush() }
            runCatching { it.close() }
            logger.removeHandler(it)
        }
    }
}
