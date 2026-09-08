package dev.evestaticmapplanner.core.jump

class JumpRangeOverlayCollection(
    private val candidateProvider: CapitalJumpCandidateProvider,
) {
    private val overlays = linkedMapOf<String, JumpRangeOverlay>()

    @Synchronized
    fun all(): List<JumpRangeOverlay> = overlays.values.toList()

    @Synchronized
    fun add(id: String, originSystemId: Int, profile: JumpProfile, label: String? = null): JumpRangeOverlay {
        require(id !in overlays) { "Overlay ID already exists: $id" }
        val result = candidateProvider.reachableFrom(originSystemId, profile)
        require(result.originVerdict is EligibilityVerdict.Eligible) {
            "Overlay origin is not eligible: ${result.originVerdict}"
        }
        return JumpRangeOverlay(id, originSystemId, profile, result.reachableSystemIds, label = label)
            .also { overlays[id] = it }
    }

    @Synchronized
    fun remove(id: String): Boolean = overlays.remove(id) != null

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): JumpRangeOverlay? = overlays[id]?.copy(enabled = enabled)
        ?.also { overlays[id] = it }

    @Synchronized
    fun updateProfile(id: String, profile: JumpProfile, label: String? = overlays[id]?.label): JumpRangeOverlay? {
        val current = overlays[id] ?: return null
        val result = candidateProvider.reachableFrom(current.originSystemId, profile)
        require(result.originVerdict is EligibilityVerdict.Eligible) {
            "Overlay origin is not eligible: ${result.originVerdict}"
        }
        return current.copy(profile = profile, reachableSystemIds = result.reachableSystemIds, label = label)
            .also { overlays[id] = it }
    }

    @Synchronized
    fun clear() = overlays.clear()

    @Synchronized
    fun coverageCounts(enabledOnly: Boolean = true): Map<Int, Int> =
        JumpCoverageCalculator.coverageCounts(overlays.values, enabledOnly)

    @Synchronized
    fun intersection(overlayIds: Set<String>? = null): Set<Int> =
        JumpCoverageCalculator.intersection(overlays.values, overlayIds)
}
