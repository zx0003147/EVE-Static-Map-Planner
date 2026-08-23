package dev.evestaticmapplanner.mcp

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SdkStdioTransportHardLimitTest {
    @Test
    fun `official stdio transport rejects a frame larger than 16 MiB`() = runBlocking {
        val expectedMaxFrameSize = 16 * 1024 * 1024
        val input = Buffer().apply {
            write(ByteArray(expectedMaxFrameSize + 1) { 'x'.code.toByte() })
        }
        val output = Buffer()
        val failure = CompletableDeferred<Throwable>()
        val closed = CompletableDeferred<Unit>()
        val transport = StdioServerTransport(input, output) {}
        transport.onError { cause -> failure.complete(cause) }
        transport.onClose { closed.complete(Unit) }

        try {
            transport.start()
            val tooLong = assertIs<TooLongFrameException>(withTimeout(10_000) { failure.await() })
            assertEquals(expectedMaxFrameSize, tooLong.maxFrameSize)
            assertTrue(tooLong.frameSize > tooLong.maxFrameSize)
            withTimeout(10_000) { closed.await() }
        } finally {
            runCatching { transport.close() }
        }
    }
}
