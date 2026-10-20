package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import kotlin.math.floor

private val SECURITY_COLORS = listOf(
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

/** EVE SPAI's eleven-step security-status palette. */
internal fun securityColor(security: Double): Color {
    val index = when {
        security.isNaN() -> 0
        security == Double.POSITIVE_INFINITY -> 10
        security == Double.NEGATIVE_INFINITY -> 0
        else -> floor(security * 10.0 + 0.5).toInt().coerceIn(0, 10)
    }
    return SECURITY_COLORS[index]
}
