package dev.evestaticmapplanner.platform.windows.minimaphud

import dev.evestaticmapplanner.miniMapCapabilityUiDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsMiniMapWindowStylesTest {
    @Test
    fun `repeated locked application retains ownership and interactive restores unrelated styles`() {
        val unrelatedStyle = 0x00000100
        val firstAdded = MiniMapExtendedStylePolicy.addedBits(unrelatedStyle, null)
        val locked = MiniMapExtendedStylePolicy.lockedStyle(unrelatedStyle)

        val repeatedAdded = MiniMapExtendedStylePolicy.addedBits(locked, firstAdded)
        val repeatedlyLocked = MiniMapExtendedStylePolicy.lockedStyle(locked)
        val restored = MiniMapExtendedStylePolicy.interactiveStyle(repeatedlyLocked, repeatedAdded)

        assertEquals(firstAdded, repeatedAdded)
        assertEquals(locked, repeatedlyLocked)
        assertEquals(unrelatedStyle, restored)
    }

    @Test
    fun `capability loss closes HUD Locked surface and cleanup restores click-through ownership`() {
        val unrelatedStyle = 0x00000100
        val addedByMiniMap = MiniMapExtendedStylePolicy.addedBits(unrelatedStyle, null)
        val locked = MiniMapExtendedStylePolicy.lockedStyle(unrelatedStyle)
        val decision = miniMapCapabilityUiDecision(
            characterTrackingAvailable = false,
            miniMapEnabled = true,
            settingsWindowOpen = false,
        )

        assertFalse(decision.showMiniMapWindow)
        assertTrue(decision.disableMiniMap)
        assertEquals(
            unrelatedStyle,
            MiniMapExtendedStylePolicy.interactiveStyle(locked, addedByMiniMap),
        )
    }
}
