package dev.evestaticmapplanner.sde.update

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class JdkSdeHttpTransportTest {
    @Test
    fun `JDK transport follows redirect`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val uri = URI.create("http://127.0.0.1:${server.address.port}/redirect")
            JdkSdeHttpTransport().execute(SdeHttpRequest(uri, timeout = Duration.ofSeconds(5))).use { response ->
                assertEquals(200, response.statusCode)
                assertEquals("ok", response.body.readBytes().decodeToString())
            }
        } finally {
            server.stop(0)
        }
    }
}
