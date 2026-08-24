package dev.evestaticmapplanner.marker

import dev.evestaticmapplanner.core.marker.SavedMarkerChild
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SavedMarkerChildVisualsTest {
    @Test
    fun `known semantic keys have stable labels and cohesive icon mappings`() {
        val expected = listOf(
            "staging" to ("Staging" to SavedMarkerChildIconKind.FLAG),
            "rally" to ("Rally" to SavedMarkerChildIconKind.PEOPLE),
            "danger" to ("Danger" to SavedMarkerChildIconKind.WARNING),
            "logistics" to ("Logistics" to SavedMarkerChildIconKind.PACKAGE),
            "home" to ("Home" to SavedMarkerChildIconKind.HOME),
            "backup" to ("Backup" to SavedMarkerChildIconKind.SHIELD),
            "industrial" to ("Industrial" to SavedMarkerChildIconKind.FACTORY),
            "strategic" to ("Strategic" to SavedMarkerChildIconKind.STAR),
            "keepstar" to ("Keepstar" to SavedMarkerChildIconKind.KEEPSTAR_BRACKET),
        )

        assertEquals(expected.map { it.first }, SavedMarkerChildVisuals.known.map { it.type?.key })
        expected.forEach { (key, metadata) ->
            val visual = SavedMarkerChildVisuals.resolve(SavedMarkerChildType.of(key))
            assertEquals(metadata.first, visual.label)
            assertEquals(metadata.second, visual.iconKind)
        }
    }

    @Test
    fun `keepstar uses traced CCP XL Citadel bracket geometry without a bitmap resource`() {
        val keepstar = SavedMarkerChildVisuals.resolve(SavedMarkerChildType.of("keepstar"))

        assertEquals(SavedMarkerChildIconKind.KEEPSTAR_BRACKET, keepstar.iconKind)
        assertNull(keepstar.resourcePath)
        assertEquals(
            listOf(
                Offset(0.40f, 0.13f),
                Offset(0.07f, 0.13f),
                Offset(0.07f, 0.87f),
                Offset(0.93f, 0.87f),
                Offset(0.93f, 0.13f),
                Offset(0.60f, 0.13f),
                Offset(0.60f, 0.47f),
                Offset(0.40f, 0.47f),
            ),
            KEEPSTAR_BRACKET_POINTS,
        )
    }

    @Test
    fun `unknown semantic key receives safe readable fallback without changing persistence type`() {
        val type = SavedMarkerChildType.of("future_custom_type")

        val visual = SavedMarkerChildVisuals.resolve(type)

        assertEquals(type, visual.type)
        assertEquals("Future Custom Type", visual.label)
        assertEquals(SavedMarkerChildIconKind.GENERIC, visual.iconKind)
        assertNull(visual.resourcePath)
    }

    @Test
    fun `available choices exclude every assigned type and preserve taxonomy order`() {
        val assigned = listOf("staging", "danger", "keepstar").mapIndexed { index, key ->
            SavedMarkerChild.create("child-$index", 1, SavedMarkerChildType.of(key), index)
        }

        val available = SavedMarkerChildVisuals.availableFor(assigned)

        assertEquals(listOf("rally", "logistics", "home", "backup", "industrial", "strategic"), available.map { it.type?.key })
        assertFalse(available.any { it.type?.key in setOf("staging", "danger", "keepstar") })
    }
}
