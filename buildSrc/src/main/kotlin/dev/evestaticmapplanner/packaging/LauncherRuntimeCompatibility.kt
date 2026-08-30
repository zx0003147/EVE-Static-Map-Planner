package dev.evestaticmapplanner.packaging

object LauncherRuntimeCompatibility {
    const val SUPPORTED_JDK_FAMILY = "25.0.4"

    fun isSupported(runtimeVersion: String): Boolean {
        val version = runCatching { Runtime.Version.parse(runtimeVersion) }.getOrNull() ?: return false
        return version.feature() == 25 &&
            version.interim() == 0 &&
            version.update() == 4 &&
            version.pre().isEmpty
    }

    fun requireSupported(runtimeVersion: String) {
        check(isSupported(runtimeVersion)) {
            "The integrated launcher contract supports GA JDK $SUPPORTED_JDK_FAMILY family runtimes only; " +
                "found $runtimeVersion."
        }
    }
}
