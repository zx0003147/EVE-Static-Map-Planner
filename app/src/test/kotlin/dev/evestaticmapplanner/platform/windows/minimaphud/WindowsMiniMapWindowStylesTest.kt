package dev.evestaticmapplanner.platform.windows.minimaphud

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
