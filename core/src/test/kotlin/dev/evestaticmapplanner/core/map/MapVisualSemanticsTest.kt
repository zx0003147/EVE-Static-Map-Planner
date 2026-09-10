package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MapVisualSemanticsTest {
    @Test
    fun `Desktop and Web route classifications share exact stroke semantics`() {
        assertEquals(MapStrokeSemantics(0x553F6685L, 1.0), MapVisualSemantics.stargateNetwork)
        assertEquals(MapStrokeSemantics(0x997C5CE0L, 1.5, listOf(6.0, 5.0), true), MapVisualSemantics.ansiblexNetwork)
        assertEquals(MapStrokeSemantics(0xFF42D6F5L, 3.0), MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.STARGATE))
        assertEquals(
            MapStrokeSemantics(0xFFFF9F43L, 4.0, listOf(12.0, 7.0), true),
            MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.ANSIBLEX),
        )
        assertEquals(MapStrokeSemantics(0xFF32D6C5L, 4.0), MapVisualSemantics.normalRouteByEdgeType.getValue(RouteEdgeType.WORMHOLE))
        assertEquals(MapStrokeSemantics(0xFFB388FFL, 4.0), MapVisualSemantics.capitalRoute)
    }

    @Test
    fun `Saved and Shared structure tags resolve once with Keepstar priority`() {
        fun shape(saved: List<String> = emptyList(), shared: List<String> = emptyList()) =
            MapVisualSemantics.primaryNodeShape(saved, shared)

        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(saved = listOf("keepstar")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(shared = listOf("keepstar")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(listOf("keepstar"), listOf("keepstar")))
        assertEquals(PrimarySystemNodeShape.FORTIZAR, shape(saved = listOf("fortizar")))
        assertEquals(PrimarySystemNodeShape.FORTIZAR, shape(shared = listOf("fortizar")))
        assertEquals(PrimarySystemNodeShape.FORTIZAR, shape(listOf("fortizar"), listOf("fortizar")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(listOf("keepstar"), listOf("fortizar")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(listOf("fortizar"), listOf("keepstar")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(saved = listOf("fortizar", "keepstar")))
        assertEquals(PrimarySystemNodeShape.SYSTEM, shape(saved = listOf("staging"), shared = listOf("home")))
        assertEquals(PrimarySystemNodeShape.KEEPSTAR, shape(shared = listOf(" KeepStar ")))

        assertEquals(
            mapOf(7 to PrimarySystemNodeShape.KEEPSTAR),
            MapVisualSemantics.primaryNodeShapes(
                savedMarkerTagsBySystemId = mapOf(7 to listOf("fortizar")),
                sharedMarkerTagsBySystemId = mapOf(7 to listOf("keepstar")),
            ),
        )
    }

    @Test
    fun `interaction emphasis is applied to the final shape without changing priority`() {
        PrimarySystemNodeShape.entries.forEach { shape ->
            val normal = MapVisualSemantics.nodeOutlineSemantics(shape, SystemNodeInteractionState.NORMAL)
            val hovered = MapVisualSemantics.nodeOutlineSemantics(shape, SystemNodeInteractionState.HOVERED)
            val selected = MapVisualSemantics.nodeOutlineSemantics(shape, SystemNodeInteractionState.SELECTED)
            if (shape == PrimarySystemNodeShape.SYSTEM) {
                assertTrue(selected.widthPx > hovered.widthPx)
                assertTrue(hovered.widthPx > normal.widthPx)
            } else {
                assertEquals(normal.widthPx, hovered.widthPx)
                assertEquals(normal.widthPx, selected.widthPx)
            }
        }
        val baseStructureColor = 0xFF42BFF5L
        PrimarySystemNodeShape.entries.filterNot { it == PrimarySystemNodeShape.SYSTEM }.forEach { shape ->
            val normal = MapVisualSemantics.nodeOutlineColorArgb(
                shape,
                SystemNodeInteractionState.NORMAL,
                baseStructureColor,
            )
            val hovered = MapVisualSemantics.nodeOutlineColorArgb(
                shape,
                SystemNodeInteractionState.HOVERED,
                baseStructureColor,
            )
            val selected = MapVisualSemantics.nodeOutlineColorArgb(
                shape,
                SystemNodeInteractionState.SELECTED,
                baseStructureColor,
            )
            assertEquals(baseStructureColor, normal)
            assertTrue(rgbBrightness(selected) > rgbBrightness(hovered))
            assertTrue(rgbBrightness(hovered) > rgbBrightness(normal))
            assertNotEquals(0xFF76E6A5L, selected)
        }
        assertEquals(
            0xFF76E6A5L,
            MapVisualSemantics.nodeOutlineColorArgb(
                PrimarySystemNodeShape.SYSTEM,
                SystemNodeInteractionState.SELECTED,
                baseStructureColor,
            ),
        )
        assertEquals(
            SystemNodeInteractionState.SELECTED,
            MapVisualSemantics.interactionState(isHovered = true, isSelected = true),
        )
        assertTrue(SystemStateOverlayPriority.ROUTE < SystemStateOverlayPriority.WAYPOINT)
        assertTrue(SystemStateOverlayPriority.WAYPOINT < SystemStateOverlayPriority.SELECTED_OR_HOVERED)
    }

    @Test
    fun `Fortizar and Keepstar use distinct normalized closed-outline inputs`() {
        val fortizar = MapVisualSemantics.primaryNodeOutline(PrimarySystemNodeShape.FORTIZAR)
        val keepstar = MapVisualSemantics.primaryNodeOutline(PrimarySystemNodeShape.KEEPSTAR)

        assertNotEquals(fortizar, keepstar)
        listOf(fortizar, keepstar).forEach { outline ->
            assertTrue(outline.size >= 8)
            assertTrue(outline.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        }
    }
}

private fun rgbBrightness(argb: Long): Long =
    ((argb shr 16) and 0xFFL) + ((argb shr 8) and 0xFFL) + (argb and 0xFFL)
