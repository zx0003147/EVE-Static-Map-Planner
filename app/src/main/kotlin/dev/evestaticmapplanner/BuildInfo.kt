package dev.evestaticmapplanner

import java.io.InputStream
import java.util.Properties

data class BuildInfo(
    val appVersion: String,
    val gitCommit: String,
    val jdkVersion: String,
    val jdkVendor: String,
    val kotlinVersion: String,
    val composeVersion: String,
    val gradleVersion: String,
    val targetOs: String,
    val targetArch: String,
)

object ApplicationBuildInfo {
    val current: BuildInfo by lazy {
        val stream = ApplicationBuildInfo::class.java.getResourceAsStream("/build-info.properties")
        load(stream)
    }

    internal fun load(stream: InputStream?): BuildInfo {
        val properties = Properties()
        stream?.use(properties::load)
        fun value(name: String) = properties.getProperty(name)?.takeIf(String::isNotBlank) ?: "unknown"
        return BuildInfo(
            appVersion = value("appVersion"),
            gitCommit = value("gitCommit"),
            jdkVersion = value("jdkVersion"),
            jdkVendor = value("jdkVendor"),
            kotlinVersion = value("kotlinVersion"),
            composeVersion = value("composeVersion"),
            gradleVersion = value("gradleVersion"),
            targetOs = value("targetOs"),
            targetArch = value("targetArch"),
        )
    }
}
