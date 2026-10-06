package dev.evestaticmapplanner

import dev.evestaticmapplanner.core.network.ApplicationProxySelector
import java.net.ProxySelector
import java.net.URI

internal const val SYSTEM_PROXY_PROPERTY = "java.net.useSystemProxies"
internal val BRAVE_SEARCH_DIAGNOSTIC_URI: URI = URI.create("https://api.search.brave.com")

internal fun logSystemProxyStartupDiagnostics(
    propertyValue: String? = System.getProperty(SYSTEM_PROXY_PROPERTY),
    proxySelector: ProxySelector? = ProxySelector.getDefault(),
    log: (String) -> Unit = AppDiagnostics::info,
) {
    val enabled = propertyValue.equals("true", ignoreCase = true)
    log("System proxy support: ${if (enabled) "enabled" else "disabled"}")
    (proxySelector as? ApplicationProxySelector)?.let {
        log("Network proxy mode: ${it.configuration().mode}")
    }
    val selection = runCatching {
        proxySelector?.select(BRAVE_SEARCH_DIAGNOSTIC_URI)?.toString() ?: "<no default ProxySelector>"
    }.getOrElse { failure ->
        "<unavailable: ${failure.javaClass.simpleName}>"
    }
    log("ProxySelector for $BRAVE_SEARCH_DIAGNOSTIC_URI: $selection")
}
