package dev.evestaticmapplanner.featurepack

import java.nio.file.Files
import java.nio.file.Path

data class FeaturePackRuntimeValidationArguments(val reportPath: Path) {
    companion object {
        const val OPTION = "--validate-feature-pack-runtime"

        fun parseOrNull(arguments: Array<String>): FeaturePackRuntimeValidationArguments? {
            if (arguments.firstOrNull() != OPTION) return null
            require(arguments.size == 2) { "$OPTION requires exactly one report path" }
            require(arguments[1].isNotBlank()) { "$OPTION requires a non-blank report path" }
            return FeaturePackRuntimeValidationArguments(Path.of(arguments[1]).toAbsolutePath().normalize())
        }
    }
}

object FeaturePackRuntimeValidation {
    fun run(arguments: FeaturePackRuntimeValidationArguments) {
        val events = mutableListOf<String>()
        val runtime = ProductionFeaturePackRuntime.start(eventSink = events::add)
        val packControlPackIds = runtime.packControlHost.state.value.map { it.packId.value }
        val closed = runtime.closeSafely()
        arguments.reportPath.parent?.let(Files::createDirectories)
        Files.writeString(arguments.reportPath, buildString {
            appendLine("packRoot=${runtime.startReport.packRoot}")
            appendLine("candidateCount=${runtime.startReport.candidates.size}")
            appendLine("loadedPackIds=${runtime.startReport.loadedPackIds.joinToString(",") { it.value }}")
            appendLine("packControlPackIds=${packControlPackIds.joinToString(",")}")
            appendLine("startupFailures=${runtime.startReport.failures.joinToString(",") { it.kind.name }}")
            appendLine("packEvents=${events.joinToString("|")}")
            appendLine("closeFailures=${closed.failures.joinToString(",") { it.kind.name }}")
            appendLine("lifecycleClosed=true")
            appendLine("coreContinued=true")
        })
    }
}
