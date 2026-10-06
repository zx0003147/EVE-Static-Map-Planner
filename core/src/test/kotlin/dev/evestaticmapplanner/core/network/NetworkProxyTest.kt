package dev.evestaticmapplanner.core.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkProxyTest {
    private val target = URI.create("https://api.search.brave.com")
    private val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system.test", 8080))
    private val systemSelector = FixedProxySelector(systemProxy)

    @Test
    fun `direct ignores the system selector`() {
        val selector = ApplicationProxySelector(
            systemSelector,
            NetworkProxyConfiguration(mode = NetworkProxyMode.DIRECT),
        )
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(target))
    }

    @Test
    fun `system delegates to the captured operating system selector`() {
        val selector = ApplicationProxySelector(
            systemSelector,
            NetworkProxyConfiguration(mode = NetworkProxyMode.SYSTEM),
        )
        assertEquals(listOf(systemProxy), selector.select(target))
    }

    @Test
    fun `manual HTTP is independent of the system selector`() {
        val selector = ApplicationProxySelector(
            systemSelector,
            NetworkProxyConfiguration(NetworkProxyMode.MANUAL, host = "manual.test", port = 3128),
        )
        val selected = selector.select(target).single()
        assertEquals(Proxy.Type.HTTP, selected.type())
        assertEquals("manual.test", (selected.address() as InetSocketAddress).hostString)
        assertEquals(3128, (selected.address() as InetSocketAddress).port)
    }

    @Test
    fun `all loopback spellings bypass every proxy mode`() {
        val selector = ApplicationProxySelector(
            systemSelector,
            NetworkProxyConfiguration(NetworkProxyMode.MANUAL, host = "manual.test", port = 3128),
        )
        listOf("http://localhost:1", "http://127.0.0.1:1", "http://[::1]:1").forEach {
            assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI.create(it)))
        }
    }

    @Test
    fun `JDK HttpClient retains the dynamic application selector`() {
        val selector = ApplicationProxySelector(systemSelector, NetworkProxyConfiguration.LegacyDefaults)
        val client = HttpClient.newBuilder().proxy(selector).build()
        assertSame(selector, client.proxy().orElseThrow())
        selector.update(NetworkProxyConfiguration(NetworkProxyMode.MANUAL, host = "manual.test", port = 3128))
        assertEquals("manual.test", (client.proxy().orElseThrow().select(target).single().address() as InetSocketAddress).hostString)
    }

    @Test
    fun `proxy test treats an HTTP error response as successful connectivity`() {
        var acceptHeader: String? = null
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/probe") { exchange ->
                acceptHeader = exchange.requestHeaders.getFirst("Accept")
                exchange.sendResponseHeaders(415, -1)
                exchange.close()
            }
            start()
        }
        try {
            val probeTarget = URI.create("http://127.0.0.1:${server.address.port}/probe")
            val result = NetworkProxyTester(targets = listOf(probeTarget)).test(FixedProxySelector(Proxy.NO_PROXY))

            assertTrue(result.successful)
            assertEquals(415, result.statusCode)
            assertEquals(probeTarget, result.target)
            assertEquals("*/*", acceptHeader)
            assertNull(result.failure)
        } finally {
            server.stop(0)
        }
    }
}

private class FixedProxySelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(proxy)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
