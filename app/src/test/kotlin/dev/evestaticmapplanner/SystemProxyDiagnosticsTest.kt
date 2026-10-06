package dev.evestaticmapplanner

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemProxyDiagnosticsTest {
    @Test
    fun `startup diagnostics report enabled system proxy and Brave selection`() {
        val messages = mutableListOf<String>()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system-proxy.test", 8080))

        logSystemProxyStartupDiagnostics(
            propertyValue = "true",
            proxySelector = FixedProxySelector(proxy),
            log = messages::add,
        )

        assertEquals(
            listOf(
                "System proxy support: enabled",
                "ProxySelector for https://api.search.brave.com: [HTTP @ system-proxy.test/<unresolved>:8080]",
            ),
            messages,
        )
    }

    @Test
    fun `startup diagnostics report direct selection without exposing request data`() {
        val messages = mutableListOf<String>()

        logSystemProxyStartupDiagnostics(
            propertyValue = null,
            proxySelector = FixedProxySelector(Proxy.NO_PROXY),
            log = messages::add,
        )

        assertEquals(
            listOf(
                "System proxy support: disabled",
                "ProxySelector for https://api.search.brave.com: [DIRECT]",
            ),
            messages,
        )
    }
}

private class FixedProxySelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(proxy)

    override fun connectFailed(uri: URI?, socketAddress: SocketAddress?, failure: IOException?) = Unit
}
