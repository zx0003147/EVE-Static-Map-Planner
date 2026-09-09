package dev.evestaticmapplanner.web

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WebDeviceSessionStoreTest {
    @Test
    fun `IndexedDB store round trips and clears the minimum remembered device session`() = runTest {
        if (js("typeof indexedDB === 'undefined'") as Boolean) return@runTest
        val store = IndexedDbWebDeviceSessionStore()
        val session = RememberedWebDeviceSession(
            serverOrigin = "https://marker.example.com",
            accessToken = "esm_dev_browser_test_secret",
            deviceName = "Browser Test",
            workspaceId = WORKSPACE_ID,
        )

        store.clear()
        store.save(session)
        assertEquals(session, store.load())
        assertFalse(store.load().toString().contains(session.accessToken))
        store.clear()
        assertNull(store.load())
    }
}
