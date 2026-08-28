package dev.evestaticmapplanner.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

internal fun createMcpServer(client: McpMapClient): Server {
    // kotlin-logging 8 enables an initialization banner by default. It must be
    // disabled before the MCP SDK initializes any logger because stdout is the
    // stdio protocol channel.
    KotlinLoggingConfiguration.logStartupMessage = false
    return Server(
        serverInfo = Implementation(
            name = "eve-static-map-planner",
            version = McpBuildInfo.version,
            title = "EVE Static Map Planner",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Use search_system to resolve canonical solar system IDs before map-changing calls. " +
            "Marker requests are temporary Mission markers unless the user explicitly requests permanent Saved storage. " +
            "Saved Marker access is permission-gated and limited to read and create without overwrite.",
    ).also { server -> McpToolCatalog.register(server, client) }
}
