package dev.evestaticmapplanner.minimap

import dev.evestaticmapplanner.preferences.MiniMapInteractionMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.preferences.MiniMapWindowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MiniMapWindowBehaviorTest {
    @Test
    fun `default Standard window remains interactive and focusable`() {
        val behavior = resolveMiniMapWindowBehavior(
            MiniMapPreferences.Defaults,
            MiniMapHudRuntimeState(MiniMapRecoveryHotkeyStatus.REGISTERED),
        )

        assertFalse(behavior.hudPresentation)
        assertEquals(MiniMapInteractionMode.INTERACTIVE, behavior.interactionMode)
        assertFalse(behavior.clickThrough)
        assertTrue(behavior.focusable)
    }

    @Test
    fun `HUD locked is click-through and non-focusable only with a registered recovery hotkey`() {
        val requested = MiniMapPreferences(
            windowStyle = MiniMapWindowStyle.HUD,
            interactionMode = MiniMapInteractionMode.HUD_LOCKED,
        )

        val locked = resolveMiniMapWindowBehavior(
            requested,
            MiniMapHudRuntimeState(MiniMapRecoveryHotkeyStatus.REGISTERED),
        )
        assertTrue(locked.hudPresentation)
        assertTrue(locked.clickThrough)
        assertFalse(locked.focusable)

        val failed = resolveMiniMapWindowBehavior(
            requested,
            MiniMapHudRuntimeState(MiniMapRecoveryHotkeyStatus.FAILED),
        )
        assertEquals(MiniMapInteractionMode.INTERACTIVE, failed.interactionMode)
        assertFalse(failed.clickThrough)
        assertTrue(failed.focusable)
    }

    @Test
    fun `native style failure restores Standard and Interactive without changing map preferences`() {
        val original = MiniMapPreferences(
            stargateHops = 5,
            includeAnsiblexEdges = true,
            windowStyle = MiniMapWindowStyle.HUD,
            interactionMode = MiniMapInteractionMode.HUD_LOCKED,
        )

        val safe = original.afterNativeHudFailure()

        assertEquals(MiniMapWindowStyle.STANDARD, safe.windowStyle)
        assertEquals(MiniMapInteractionMode.INTERACTIVE, safe.interactionMode)
        assertEquals(5, safe.stargateHops)
        assertTrue(safe.includeAnsiblexEdges)
    }

    @Test
    fun `HUD opacity accepts forty through one hundred percent only`() {
        assertEquals(0.4f, MiniMapPreferences(hudOpacity = 0.4f).hudOpacity)
        assertEquals(1f, MiniMapPreferences(hudOpacity = 1f).hudOpacity)
        assertFailsWith<IllegalArgumentException> { MiniMapPreferences(hudOpacity = 0.39f) }
        assertFailsWith<IllegalArgumentException> { MiniMapPreferences(hudOpacity = 1.01f) }
    }
}
