package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.wormhole.WormholeSessionStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppWormholeControlAdapterTest {
    @Test
    fun `AI adapter and UI consumers share one session Store across Views`() = runTest {
        val globalStore = WormholeSessionStore()
        val ai = AppWormholeControlAdapter(globalStore)
        val anotherView = AppWormholeControlAdapter(globalStore)

        val created = ai.createWormhole(3, 1)
        val duplicate = ai.createWormhole(1, 3)

        assertEquals(WormholeCreateStatus.CREATED, created.status)
        assertEquals(WormholeCreateStatus.ALREADY_EXISTS, duplicate.status)
        assertEquals(listOf("wormhole:1:3"), globalStore.connections.value.map { it.id })
        assertEquals(listOf(created.connection), anotherView.listWormholes())
    }
}
