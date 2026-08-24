package dev.evestaticmapplanner.mcp

import java.util.Properties

internal object McpBuildInfo {
    val version: String by lazy {
        val properties = Properties()
        McpBuildInfo::class.java.getResourceAsStream("/mcp-build.properties")?.use(properties::load)
        properties.getProperty("version")?.takeIf(String::isNotBlank) ?: "unknown"
    }
}
