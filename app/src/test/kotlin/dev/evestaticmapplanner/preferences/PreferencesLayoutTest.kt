package dev.evestaticmapplanner.preferences

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferencesLayoutTest {
    @Test
    fun `content gutter keeps the complete preferences body away from navigation`() {
        assertEquals(24.dp, PREFERENCES_CONTENT_START_GUTTER)
    }
}
