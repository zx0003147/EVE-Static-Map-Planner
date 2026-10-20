package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityColorTest {
    @Test
    fun `all SPAI security palette entries are exact`() {
        val expected = listOf(
            Color(0xFFB03A9A),
            Color(0xFFD73000),
            Color(0xFFF04800),
            Color(0xFFF06000),
            Color(0xFFD77700),
            Color(0xFFEFEF00),
            Color(0xFF8FEF2F),
            Color(0xFF00F000),
            Color(0xFF00EF47),
            Color(0xFF48F0C0),
            Color(0xFF2FEFEF),
        )

        expected.forEachIndexed { index, color ->
            assertEquals(color, securityColor(index / 10.0), "security index $index")
        }
    }

    @Test
    fun `rounding matches SPAI positive half-up boundaries`() {
        assertEquals(Color(0xFFB03A9A), securityColor(0.00))
        assertEquals(Color(0xFFB03A9A), securityColor(0.04))
        assertEquals(Color(0xFFD73000), securityColor(0.05))
        assertEquals(Color(0xFFD73000), securityColor(0.14))
        assertEquals(Color(0xFFF04800), securityColor(0.15))
        assertEquals(Color(0xFFF06000), securityColor(0.25))
        assertEquals(Color(0xFFEFEF00), securityColor(0.50))
        assertEquals(Color(0xFF48F0C0), securityColor(0.86))
        assertEquals(Color(0xFF2FEFEF), securityColor(1.00))
    }

    @Test
    fun `out of range and exceptional values are stable`() {
        val lowest = Color(0xFFB03A9A)
        val highest = Color(0xFF2FEFEF)

        assertEquals(lowest, securityColor(-0.30))
        assertEquals(highest, securityColor(1.20))
        assertEquals(lowest, securityColor(Double.NaN))
        assertEquals(lowest, securityColor(Double.NEGATIVE_INFINITY))
        assertEquals(highest, securityColor(Double.POSITIVE_INFINITY))
    }
}
