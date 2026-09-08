package dev.evestaticmapplanner.core.jump

data class JumpRangeOverlay(
    val id: String,
    val originSystemId: Int,
    val profile: JumpProfile,
    val reachableSystemIds: Set<Int>,
    val enabled: Boolean = true,
    val label: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Overlay ID must not be blank" }
        require(originSystemId > 0) { "Overlay origin system ID must be positive" }
    }
}

object JumpCoverageCalculator {
    fun coverageCounts(overlays: Collection<JumpRangeOverlay>, enabledOnly: Boolean = true): Map<Int, Int> {
        val counts = mutableMapOf<Int, Int>()
        overlays.asSequence().filter { !enabledOnly || it.enabled }.forEach { overlay ->
            overlay.reachableSystemIds.forEach { systemId -> counts[systemId] = (counts[systemId] ?: 0) + 1 }
        }
        return counts
    }

    fun intersection(overlays: Collection<JumpRangeOverlay>, overlayIds: Set<String>? = null): Set<Int> {
        val selected = overlays.filter { overlay ->
            overlay.enabled && (overlayIds == null || overlay.id in overlayIds)
        }
        if (selected.isEmpty()) return emptySet()
        return selected.drop(1).fold(selected.first().reachableSystemIds.toMutableSet()) { intersection, overlay ->
            intersection.apply { retainAll(overlay.reachableSystemIds) }
        }
    }
}
