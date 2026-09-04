package dev.evestaticmapplanner.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EveThemeTest {
    @Test
    fun `shell palette keeps the approved restrained colors`() {
        assertEquals(Color(0xFF000000), EveColors.MapBackground)
        assertEquals(Color(0xFF0B1115), EveColors.PrimarySurface)
        assertEquals(Color(0xFF10191E), EveColors.SecondarySurface)
        assertEquals(Color(0xFF283840), EveColors.Border)
        assertEquals(Color(0xFFC3CDD2), EveColors.PrimaryText)
        assertEquals(Color(0xFF77878E), EveColors.SecondaryText)
        assertEquals(Color(0xFF4F9EC3), EveColors.PrimaryAccent)
        assertEquals(Color(0xFFCDA15B), EveColors.Important)
    }

    @Test
    fun `shared density stays compact without shrinking targets too far`() {
        assertEquals(40.dp, EveDimensions.MinimumInteractiveSize)
        assertEquals(36.dp, EveDimensions.ButtonHeight)
        assertEquals(44.dp, EveDimensions.InputMinimumHeight)
        assertEquals(10.dp, EveDimensions.InputHorizontalPadding)
        assertEquals(8.dp, EveDimensions.InputVerticalPadding)
        assertEquals(5.dp, EveDimensions.ScrollbarThickness)
        assertTrue(EveDimensions.CornerRadius <= 2.dp)
    }

    @Test
    fun `native chrome converts compose colors to Windows COLORREF order`() {
        assertEquals(0x0015110B, NativeWindowChrome.colorRef(EveColors.PrimarySurface))
        assertEquals(0x00D2CDC3, NativeWindowChrome.colorRef(EveColors.PrimaryText))
    }
}
