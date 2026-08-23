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
            version = "0.1.2",
            title = "EVE Static Map Planner",
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "Use search_system to resolve canonical solar system IDs before map-changing calls. " +
            "All map objects created by these tools must be Mission-owned and temporary.",
    ).also { server -> McpToolCatalog.register(server, client) }
}
