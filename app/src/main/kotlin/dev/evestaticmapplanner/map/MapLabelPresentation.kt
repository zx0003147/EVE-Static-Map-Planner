package dev.evestaticmapplanner.map

import dev.evestaticmapplanner.core.map.MapBounds
import dev.evestaticmapplanner.core.map.MapPoint
import dev.evestaticmapplanner.core.map.MapSize
import dev.evestaticmapplanner.core.map.MapTransform
import dev.evestaticmapplanner.core.map.ProjectedMapScene
import dev.evestaticmapplanner.core.map.ProjectedSystemNode

enum class MapLabelType {
    SYSTEM,
    REGION_PRIMARY,
    REGION_BACKGROUND,
    CONSTELLATION,
}

enum class RegionAnchorSource {
    CANONICAL,
    VIEWPORT_MEMBER_FALLBACK,
}

data class PresentedMapLabel(
    val type: MapLabelType,
    val groupId: Int,
    val text: String,
    val worldAnchor: MapPoint,
    val screenTopLeft: MapPoint,
    val screenBounds: ScreenBounds,
    val regionAnchorSource: RegionAnchorSource? = null,
)

data class ScreenBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    fun intersects(other: ScreenBounds, padding: Double = 0.0): Boolean =
        maxX + padding >= other.minX && minX - padding <= other.maxX &&
            maxY + padding >= other.minY && minY - padding <= other.maxY
}

data class MapLabelPresentation(
    val semanticMode: SemanticLabelMode,
    val regionLabelRole: RegionLabelRole,
    val visibleSystemIds: List<Int>,
    val regionLabels: List<PresentedMapLabel>,
    val constellationLabels: List<PresentedMapLabel>,
    val systemLabelSystemIds: List<Int>,
    val emphasizedSystemLabelIds: List<Int>,
)

fun interface MapLabelMetricsProvider {
    fun measure(text: String, type: MapLabelType): MapSize
}

object MapLabelPresentationBuilder {
    fun build(
        scene: ProjectedMapScene,
        transform: MapTransform,
        semanticMode: SemanticLabelMode,
        metricsProvider: MapLabelMetricsProvider,
        emphasizedSystemIds: Set<Int> = emptySet(),
    ): MapLabelPresentation {
        val viewportBounds = transform.visibleWorldBounds()
        val contentBounds = transform.visibleWorldBounds(MAP_CONTENT_CULL_MARGIN_PX)
        val visibleSystemIds = scene.spatialIndex.query(contentBounds).sorted()
        val viewportNodes = scene.spatialIndex.query(viewportBounds).asSequence()
            .map(scene.nodesById::getValue)
            .toList()
        val regionLabels = when (semanticMode.regionLabelRole) {
            RegionLabelRole.PRIMARY -> primaryRegionLabels(scene, transform, viewportBounds, metricsProvider)
            RegionLabelRole.BACKGROUND -> backgroundRegionLabels(scene, transform, viewportNodes, metricsProvider)
        }
        val constellationLabels = if (semanticMode == SemanticLabelMode.CONSTELLATION) {
            constellationLabels(scene, transform, viewportBounds, metricsProvider)
        } else {
            emptyList()
        }
        val regularSystemLabels = if (
            semanticMode == SemanticLabelMode.SYSTEM && visibleSystemIds.size <= MAX_VISIBLE_SYSTEM_LABELS
        ) {
            visibleSystemIds
        } else {
            emptyList()
        }
        val emphasizedSystemLabels = emphasizedSystemLabels(
            scene = scene,
            transform = transform,
            visibleSystemIds = visibleSystemIds.toHashSet(),
            emphasizedSystemIds = emphasizedSystemIds,
            metricsProvider = metricsProvider,
        )
        return MapLabelPresentation(
            semanticMode = semanticMode,
            regionLabelRole = semanticMode.regionLabelRole,
            visibleSystemIds = visibleSystemIds,
            regionLabels = regionLabels,
            constellationLabels = constellationLabels,
            systemLabelSystemIds = regularSystemLabels.filterNot(emphasizedSystemLabels.toHashSet()::contains),
            emphasizedSystemLabelIds = emphasizedSystemLabels,
        )
    }

    private fun emphasizedSystemLabels(
        scene: ProjectedMapScene,
        transform: MapTransform,
        visibleSystemIds: Set<Int>,
        emphasizedSystemIds: Set<Int>,
        metricsProvider: MapLabelMetricsProvider,
    ): List<Int> {
        if (emphasizedSystemIds.isEmpty()) return emptyList()
        val acceptedIds = ArrayList<Int>(minOf(emphasizedSystemIds.size, MAX_EMPHASIZED_SYSTEM_LABELS))
        val acceptedBounds = ArrayList<ScreenBounds>(acceptedIds.size)
        val canvasBounds = ScreenBounds(0.0, 0.0, transform.canvasSize.width, transform.canvasSize.height)
        emphasizedSystemIds.asSequence()
            .filter(visibleSystemIds::contains)
            .takeWhile { acceptedIds.size < MAX_EMPHASIZED_SYSTEM_LABELS }
            .forEach { systemId ->
                val node = scene.nodesById[systemId] ?: return@forEach
                val size = metricsProvider.measure(node.system.name, MapLabelType.SYSTEM)
                val screen = transform.worldToScreen(node.position)
                val topLeft = MapPoint(screen.x + SYSTEM_LABEL_OFFSET_PX, screen.y - size.height / 2.0)
                val bounds = ScreenBounds(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height)
                if (bounds.intersects(canvasBounds) && acceptedBounds.none {
                        it.intersects(bounds, EMPHASIZED_SYSTEM_LABEL_COLLISION_PADDING_PX)
                    }
                ) {
                    acceptedIds += systemId
                    acceptedBounds += bounds
                }
            }
        return acceptedIds
    }

    private fun primaryRegionLabels(
        scene: ProjectedMapScene,
        transform: MapTransform,
        viewportBounds: MapBounds,
        metricsProvider: MapLabelMetricsProvider,
    ): List<PresentedMapLabel> = scene.regions.asSequence()
        .filter { it.bounds.intersects(viewportBounds) }
        .mapNotNull { region ->
            centeredLabel(
                type = MapLabelType.REGION_PRIMARY,
                groupId = region.id,
                text = region.name,
                worldAnchor = region.canonicalAnchor,
                transform = transform,
                metricsProvider = metricsProvider,
                regionAnchorSource = RegionAnchorSource.CANONICAL,
            )
        }
        .toList()

    private fun backgroundRegionLabels(
        scene: ProjectedMapScene,
        transform: MapTransform,
        viewportNodes: List<ProjectedSystemNode>,
        metricsProvider: MapLabelMetricsProvider,
    ): List<PresentedMapLabel> {
        val viewportBounds = transform.visibleWorldBounds()
        val visibleMembersByRegion = viewportNodes.groupBy { it.system.regionId }
        return scene.regions.mapNotNull { region ->
            val canonicalVisible = viewportBounds.contains(region.canonicalAnchor)
            val fallback = if (canonicalVisible) {
                null
            } else {
                visibleMembersByRegion[region.id]?.minWithOrNull(
                    compareBy<ProjectedSystemNode> { it.position.distanceSquaredTo(transform.viewport.center) }
                        .thenBy { it.system.id },
                )
            }
            val anchor = when {
                canonicalVisible -> region.canonicalAnchor
                fallback != null -> fallback.position
                else -> return@mapNotNull null
            }
            centeredLabel(
                type = MapLabelType.REGION_BACKGROUND,
                groupId = region.id,
                text = region.name,
                worldAnchor = anchor,
                transform = transform,
                metricsProvider = metricsProvider,
                regionAnchorSource = if (canonicalVisible) {
                    RegionAnchorSource.CANONICAL
                } else {
                    RegionAnchorSource.VIEWPORT_MEMBER_FALLBACK
                },
            )
        }
    }

    private fun constellationLabels(
        scene: ProjectedMapScene,
        transform: MapTransform,
        viewportBounds: MapBounds,
        metricsProvider: MapLabelMetricsProvider,
    ): List<PresentedMapLabel> {
        val candidates = scene.constellations.asSequence()
            .filter { it.bounds.intersects(viewportBounds) }
            .mapNotNull { constellation ->
                centeredLabel(
                    type = MapLabelType.CONSTELLATION,
                    groupId = constellation.id,
                    text = constellation.name,
                    worldAnchor = constellation.canonicalAnchor,
                    transform = transform,
                    metricsProvider = metricsProvider,
                )?.let { label -> ConstellationCandidate(label, constellation.projectedMemberCount) }
            }
            .sortedWith(
                compareBy<ConstellationCandidate> {
                    it.label.worldAnchor.distanceSquaredTo(transform.viewport.center)
                }.thenByDescending { it.projectedMemberCount }
                    .thenBy { it.label.groupId },
            )
            .toList()
        val accepted = ArrayList<PresentedMapLabel>(candidates.size)
        candidates.forEach { candidate ->
            if (accepted.none { it.screenBounds.intersects(candidate.label.screenBounds, CONSTELLATION_COLLISION_PADDING_PX) }) {
                accepted += candidate.label
            }
        }
        return accepted
    }

    private fun centeredLabel(
        type: MapLabelType,
        groupId: Int,
        text: String,
        worldAnchor: MapPoint,
        transform: MapTransform,
        metricsProvider: MapLabelMetricsProvider,
        regionAnchorSource: RegionAnchorSource? = null,
    ): PresentedMapLabel? {
        val size = metricsProvider.measure(text, type)
        val screenAnchor = transform.worldToScreen(worldAnchor)
        val topLeft = MapPoint(screenAnchor.x - size.width / 2.0, screenAnchor.y - size.height / 2.0)
        val bounds = ScreenBounds(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height)
        val canvasBounds = ScreenBounds(0.0, 0.0, transform.canvasSize.width, transform.canvasSize.height)
        if (!bounds.intersects(canvasBounds)) return null
        return PresentedMapLabel(
            type = type,
            groupId = groupId,
            text = text,
            worldAnchor = worldAnchor,
            screenTopLeft = topLeft,
            screenBounds = bounds,
            regionAnchorSource = regionAnchorSource,
        )
    }

    private data class ConstellationCandidate(
        val label: PresentedMapLabel,
        val projectedMemberCount: Int,
    )
}

private const val MAP_CONTENT_CULL_MARGIN_PX = 80.0
private const val CONSTELLATION_COLLISION_PADDING_PX = 6.0
private const val MAX_VISIBLE_SYSTEM_LABELS = 700
private const val MAX_EMPHASIZED_SYSTEM_LABELS = 80
private const val EMPHASIZED_SYSTEM_LABEL_COLLISION_PADDING_PX = 2.0
private const val SYSTEM_LABEL_OFFSET_PX = 5.0
