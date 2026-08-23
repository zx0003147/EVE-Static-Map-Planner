package dev.evestaticmapplanner.mcp

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main(): Unit = runBlocking {
    LocalMcpMapClient().use { client ->
        val server = createMcpServer(client)
        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered(),
        ) {}
        val closed = CompletableDeferred<Unit>()
        transport.onClose { closed.complete(Unit) }
        try {
            server.createSession(transport)
            closed.await()
        } finally {
            runCatching { transport.close() }
            runCatching { server.close() }
        }
    }
}
