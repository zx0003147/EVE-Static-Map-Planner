package dev.evestaticmapplanner.wormhole

import dev.evestaticmapplanner.core.wormhole.WormholeConnection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WormholeSessionStoreTest {
    @Test
    fun `initial state is empty`() {
        assertEquals(emptyList(), WormholeSessionStore().connections.value)
    }

    @Test
    fun `adds one connection and publishes it`() {
        val store = WormholeSessionStore()

        assertEquals(AddWormholeResult.CREATED, store.add(30_000_002, 30_000_001))
        assertEquals(
            listOf(WormholeConnection.between(30_000_001, 30_000_002)),
            store.connections.value,
        )
    }

    @Test
    fun `one system can connect to multiple other systems`() {
        val store = WormholeSessionStore()

        assertEquals(AddWormholeResult.CREATED, store.add(30_000_001, 30_000_002))
        assertEquals(AddWormholeResult.CREATED, store.add(30_000_001, 30_000_003))
        assertEquals(AddWormholeResult.CREATED, store.add(30_000_001, 30_000_004))

        assertEquals(3, store.connectionsForSystem(30_000_001).size)
    }

    @Test
    fun `duplicate pair in the same order is reported without adding another connection`() {
        val store = WormholeSessionStore()

        assertEquals(AddWormholeResult.CREATED, store.add(30_000_001, 30_000_002))
        assertEquals(AddWormholeResult.ALREADY_EXISTS, store.add(30_000_001, 30_000_002))
        assertEquals(1, store.connections.value.size)
    }

    @Test
    fun `duplicate pair in reverse order is reported without adding another connection`() {
        val store = WormholeSessionStore()

        assertEquals(AddWormholeResult.CREATED, store.add(30_000_001, 30_000_002))
        assertEquals(AddWormholeResult.ALREADY_EXISTS, store.add(30_000_002, 30_000_001))
        assertEquals(1, store.connections.value.size)
    }

    @Test
    fun `invalid self-loop is rejected`() {
        val store = WormholeSessionStore()

        assertFailsWith<IllegalArgumentException> { store.add(30_000_001, 30_000_001) }
        assertTrue(store.connections.value.isEmpty())
    }

    @Test
    fun `removes an existing connection`() {
        val store = WormholeSessionStore()
        store.add(30_000_001, 30_000_002)

        assertTrue(store.remove("wormhole:30000001:30000002"))
        assertTrue(store.connections.value.isEmpty())
    }

    @Test
    fun `removing a missing connection leaves all other connections intact`() {
        val store = WormholeSessionStore()
        store.add(30_000_001, 30_000_002)

        assertFalse(store.remove("wormhole:30000001:30000003"))
        assertEquals(listOf("wormhole:30000001:30000002"), store.connections.value.map { it.id })
    }

    @Test
    fun `clear returns the actual number removed`() {
        val store = WormholeSessionStore()
        store.add(30_000_001, 30_000_002)
        store.add(30_000_001, 30_000_003)

        assertEquals(2, store.clear())
        assertTrue(store.connections.value.isEmpty())
        assertEquals(0, store.clear())
    }

    @Test
    fun `connections for system returns only incident connections`() {
        val store = WormholeSessionStore()
        store.add(30_000_001, 30_000_003)
        store.add(30_000_002, 30_000_003)
        store.add(30_000_004, 30_000_005)

        assertEquals(
            listOf("wormhole:30000001:30000003", "wormhole:30000002:30000003"),
            store.connectionsForSystem(30_000_003).map { it.id },
        )
    }

    @Test
    fun `snapshots use deterministic endpoint order`() {
        val store = WormholeSessionStore()
        store.add(30_000_005, 30_000_003)
        store.add(30_000_004, 30_000_001)
        store.add(30_000_003, 30_000_001)
        store.add(30_000_004, 30_000_002)

        assertEquals(
            listOf(
                "wormhole:30000001:30000003",
                "wormhole:30000001:30000004",
                "wormhole:30000002:30000004",
                "wormhole:30000003:30000005",
            ),
            store.connections.value.map { it.id },
        )
    }

    @Test
    fun `published snapshot cannot be mutated and does not change later`() {
        val store = WormholeSessionStore()
        store.add(30_000_001, 30_000_002)
        val snapshot = store.connections.value

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot as MutableList<WormholeConnection>).clear()
        }

        store.add(30_000_001, 30_000_003)
        assertEquals(listOf("wormhole:30000001:30000002"), snapshot.map { it.id })
        assertEquals(2, store.connections.value.size)
    }

    @Test
    fun `concurrent duplicate adds create exactly one connection`() {
        val store = WormholeSessionStore()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(
                List(100) { index ->
                    Callable {
                        if (index % 2 == 0) {
                            store.add(30_000_001, 30_000_002)
                        } else {
                            store.add(30_000_002, 30_000_001)
                        }
                    }
                },
            ).map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it == AddWormholeResult.CREATED })
            assertEquals(99, results.count { it == AddWormholeResult.ALREADY_EXISTS })
            assertEquals(1, store.connections.value.size)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
