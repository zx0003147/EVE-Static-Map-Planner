package dev.evestaticmapplanner

import dev.evestaticmapplanner.mcp.LocalhostMcpHostDiagnostics

internal object AppLocalhostMcpDiagnostics : LocalhostMcpHostDiagnostics {
    override fun info(message: String) = AppDiagnostics.info(message)

    override fun warning(message: String, failure: Throwable?) = AppDiagnostics.warning(message, failure)
}
