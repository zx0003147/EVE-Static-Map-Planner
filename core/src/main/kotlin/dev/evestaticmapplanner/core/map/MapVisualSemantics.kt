package dev.evestaticmapplanner.core.map

import dev.evestaticmapplanner.core.route.RouteEdgeType
import kotlin.math.roundToInt

/** Small platform-neutral visual contract shared by Desktop Compose and Web Canvas. */
data class MapStrokeSemantics(
    val argb: Long,
    val widthPx: Double,
    val dashPatternPx: List<Double> = emptyList(),
    val curved: Boolean = false,
)

enum class PrimarySystemNodeShape { SYSTEM, FORTIZAR, KEEPSTAR }

enum class SystemNodeInteractionState { NORMAL, HOVERED, SELECTED }

data class SystemNodeOutlineSemantics(
    val widthPx: Double,
    val argb: Long? = null,
    val whiteMixFraction: Double = 0.0,
)

enum class SystemStateOverlayPriority {
    COVERAGE,
    ROUTE,
    WAYPOINT,
    SELECTED_OR_HOVERED,
}

object MapVisualSemantics {
    const val KEEPSTAR_MARKER_TAG = "keepstar"
    const val FORTIZAR_MARKER_TAG = "fortizar"
    const val PRIMARY_STRUCTURE_NODE_SIZE_PX = 14.0

    val stargateNetwork = MapStrokeSemantics(0x553F6685L, 1.0)
    val ansiblexNetwork = MapStrokeSemantics(0x997C5CE0L, 1.5, listOf(6.0, 5.0), curved = true)
    val capitalRoute = MapStrokeSemantics(0xFFB388FFL, 4.0)

    val normalRouteByEdgeType: Map<RouteEdgeType, MapStrokeSemantics> = mapOf(
        RouteEdgeType.STARGATE to MapStrokeSemantics(0xFF42D6F5L, 3.0),
        RouteEdgeType.ANSIBLEX to MapStrokeSemantics(0xFFFF9F43L, 4.0, listOf(12.0, 7.0), curved = true),
        RouteEdgeType.WORMHOLE to MapStrokeSemantics(0xFF32D6C5L, 4.0),
    )

    val overlayPriority = SystemStateOverlayPriority.entries

    fun primaryNodeShape(
        savedMarkerTags: Iterable<String>,
        sharedMarkerTags: Iterable<String>,
    ): PrimarySystemNodeShape {
        val tags = (savedMarkerTags.asSequence() + sharedMarkerTags.asSequence())
            .map(::normalizeMarkerTag)
            .toSet()
        return when {
            KEEPSTAR_MARKER_TAG in tags -> PrimarySystemNodeShape.KEEPSTAR
            FORTIZAR_MARKER_TAG in tags -> PrimarySystemNodeShape.FORTIZAR
            else -> PrimarySystemNodeShape.SYSTEM
        }
    }

    fun primaryNodeShapes(
        savedMarkerTagsBySystemId: Map<Int, Iterable<String>>,
        sharedMarkerTagsBySystemId: Map<Int, Iterable<String>>,
    ): Map<Int, PrimarySystemNodeShape> = buildMap {
        (savedMarkerTagsBySystemId.keys + sharedMarkerTagsBySystemId.keys).forEach { systemId ->
            val shape = primaryNodeShape(
                savedMarkerTagsBySystemId[systemId].orEmpty(),
                sharedMarkerTagsBySystemId[systemId].orEmpty(),
            )
            if (shape != PrimarySystemNodeShape.SYSTEM) put(systemId, shape)
        }
    }

    fun interactionState(isHovered: Boolean, isSelected: Boolean): SystemNodeInteractionState = when {
        isSelected -> SystemNodeInteractionState.SELECTED
        isHovered -> SystemNodeInteractionState.HOVERED
        else -> SystemNodeInteractionState.NORMAL
    }

    fun nodeOutlineSemantics(
        shape: PrimarySystemNodeShape,
        interaction: SystemNodeInteractionState,
    ): SystemNodeOutlineSemantics = when {
        shape == PrimarySystemNodeShape.SYSTEM && interaction == SystemNodeInteractionState.SELECTED ->
            SystemNodeOutlineSemantics(3.0, SELECTED_NODE_ARGB)
        shape == PrimarySystemNodeShape.SYSTEM && interaction == SystemNodeInteractionState.HOVERED ->
            SystemNodeOutlineSemantics(2.4, HOVERED_NODE_ARGB)
        interaction == SystemNodeInteractionState.SELECTED ->
            SystemNodeOutlineSemantics(
                NORMAL_STRUCTURE_OUTLINE_WIDTH_PX,
                whiteMixFraction = SELECTED_STRUCTURE_WHITE_MIX,
            )
        interaction == SystemNodeInteractionState.HOVERED ->
            SystemNodeOutlineSemantics(
                NORMAL_STRUCTURE_OUTLINE_WIDTH_PX,
                whiteMixFraction = HOVERED_STRUCTURE_WHITE_MIX,
            )
        interaction == SystemNodeInteractionState.NORMAL -> SystemNodeOutlineSemantics(
            widthPx = if (shape == PrimarySystemNodeShape.SYSTEM) 0.0 else NORMAL_STRUCTURE_OUTLINE_WIDTH_PX,
        )
        else -> error("Unhandled node interaction state: $interaction")
    }

    fun nodeOutlineColorArgb(
        shape: PrimarySystemNodeShape,
        interaction: SystemNodeInteractionState,
        baseArgb: Long,
    ): Long {
        val semantics = nodeOutlineSemantics(shape, interaction)
        return semantics.argb ?: mixArgbWithWhite(baseArgb, semantics.whiteMixFraction)
    }

    fun primaryNodeOutline(shape: PrimarySystemNodeShape): List<MapPoint> = when (shape) {
        PrimarySystemNodeShape.SYSTEM -> emptyList()
        PrimarySystemNodeShape.FORTIZAR -> FORTIZAR_OUTLINE
        PrimarySystemNodeShape.KEEPSTAR -> KEEPSTAR_OUTLINE
    }

    fun isPrimaryNodeShapeTag(tag: String): Boolean = normalizeMarkerTag(tag) in PRIMARY_NODE_SHAPE_TAGS

    private fun normalizeMarkerTag(tag: String): String = tag.trim().lowercase()

    private val PRIMARY_NODE_SHAPE_TAGS = setOf(FORTIZAR_MARKER_TAG, KEEPSTAR_MARKER_TAG)
    private const val HOVERED_NODE_ARGB = 0xFFF3D36AL
    private const val SELECTED_NODE_ARGB = 0xFF76E6A5L
    private const val NORMAL_STRUCTURE_OUTLINE_WIDTH_PX = 1.8
    private const val HOVERED_STRUCTURE_WHITE_MIX = 0.15
    private const val SELECTED_STRUCTURE_WHITE_MIX = 0.30

    private val KEEPSTAR_OUTLINE = listOf(
        MapPoint(0.40, 0.13),
        MapPoint(0.07, 0.13),
        MapPoint(0.07, 0.87),
        MapPoint(0.93, 0.87),
        MapPoint(0.93, 0.13),
        MapPoint(0.60, 0.13),
        MapPoint(0.60, 0.47),
        MapPoint(0.40, 0.47),
    )

    private val FORTIZAR_OUTLINE = listOf(
        MapPoint(0.07, 0.82),
        MapPoint(0.07, 0.36),
        MapPoint(0.29, 0.36),
        MapPoint(0.29, 0.13),
        MapPoint(0.71, 0.13),
        MapPoint(0.71, 0.36),
        MapPoint(0.93, 0.36),
        MapPoint(0.93, 0.82),
    )
}

private fun mixArgbWithWhite(argb: Long, fraction: Double): Long {
    require(fraction in 0.0..1.0) { "ARGB white mix fraction must be between zero and one" }
    val normalized = argb and 0xFFFF_FFFFL
    val alpha = (normalized shr 24) and 0xFFL
    fun mixedChannel(shift: Int): Long {
        val channel = ((normalized shr shift) and 0xFFL).toInt()
        return (channel + (255 - channel) * fraction).roundToInt().coerceIn(0, 255).toLong()
    }
    return (alpha shl 24) or
        (mixedChannel(16) shl 16) or
        (mixedChannel(8) shl 8) or
        mixedChannel(0)
}

private fun Iterable<String>?.orEmpty(): Iterable<String> = this ?: emptyList()
