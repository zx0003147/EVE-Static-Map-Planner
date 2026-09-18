package dev.evestaticmapplanner.shortcut

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardShortcutCodecTest {
    @Test
    fun `supported shortcuts round trip with normalized modifier order`() {
        val shortcuts = listOf(
            KeyboardShortcut(ShortcutKey.F9),
            KeyboardShortcut(ShortcutKey.SPACE, setOf(ShortcutModifier.CTRL)),
            KeyboardShortcut(
                ShortcutKey.SPACE,
                linkedSetOf(ShortcutModifier.ALT, ShortcutModifier.CTRL),
            ),
            KeyboardShortcut(ShortcutKey.F12, setOf(ShortcutModifier.SHIFT)),
        )

        assertEquals(
            listOf(
                "kbd:v1:F9",
                "kbd:v1:CTRL+SPACE",
                "kbd:v1:CTRL+ALT+SPACE",
                "kbd:v1:SHIFT+F12",
            ),
            shortcuts.map(KeyboardShortcutCodec::encode),
        )
        shortcuts.forEach { assertEquals(it, KeyboardShortcutCodec.decode(KeyboardShortcutCodec.encode(it))) }
        assertEquals(
            KeyboardShortcut(ShortcutKey.SPACE, setOf(ShortcutModifier.CTRL)),
            KeyboardShortcutCodec.decode("kbd:v1:CTRL+CTRL+SPACE"),
        )
    }

    @Test
    fun `null unknown and corrupt values are rejected without guessing`() {
        listOf(null, "", "kbd:v2:F9", "kbd:v1:UNKNOWN", "kbd:v1:CTRL+", "F9").forEach {
            assertNull(KeyboardShortcutCodec.decode(it))
        }
    }

    @Test
    fun `serialization is independent from the default locale`() {
        val original = Locale.getDefault()
        try {
            val shortcut = KeyboardShortcut(ShortcutKey.I, setOf(ShortcutModifier.SHIFT))
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = KeyboardShortcutCodec.encode(shortcut)
            Locale.setDefault(Locale.forLanguageTag("zh-CN"))
            assertEquals(turkish, KeyboardShortcutCodec.encode(shortcut))
        } finally {
            Locale.setDefault(original)
        }
    }
}
