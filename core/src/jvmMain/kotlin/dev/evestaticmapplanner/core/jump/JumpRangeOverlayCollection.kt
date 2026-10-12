package dev.evestaticmapplanner.core.jump

class JumpRangeOverlayCollection(
    private val candidateProvider: CapitalJumpCandidateProvider,
) {
    private val overlays = linkedMapOf<String, JumpRangeOverlay>()

    @Synchronized
    fun all(): List<JumpRangeOverlay> = overlays.values.toList()

    @Synchronized
    fun add(
        id: String,
        originSystemId: Int,
        profile: JumpProfile,
        preset: JumpRangePreset = JumpRangePreset.CUSTOM,
        displayColor: JumpRangeColor = JumpRangeColor.BLUE,
        label: String? = null,
    ): JumpRangeOverlay {
        require(id !in overlays) { "Overlay ID already exists: $id" }
        val result = candidateProvider.reachableFrom(originSystemId, profile)
        require(result.originVerdict is EligibilityVerdict.Eligible) {
            "Overlay origin is not eligible: ${result.originVerdict}"
        }
        return JumpRangeOverlay(
            id = id,
            originSystemId = originSystemId,
            profile = profile,
            reachableSystemIds = result.reachableSystemIds,
            preset = preset,
            displayColor = displayColor,
            label = label,
        )
            .also { overlays[id] = it }
    }

    @Synchronized
    fun remove(id: String): Boolean = overlays.remove(id) != null

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): JumpRangeOverlay? = overlays[id]?.copy(enabled = enabled)
        ?.also { overlays[id] = it }

    @Synchronized
    fun updateProfile(
        id: String,
        profile: JumpProfile,
        preset: JumpRangePreset = overlays[id]?.preset ?: JumpRangePreset.CUSTOM,
        displayColor: JumpRangeColor = overlays[id]?.displayColor ?: JumpRangeColor.BLUE,
        label: String? = overlays[id]?.label,
    ): JumpRangeOverlay? {
        val current = overlays[id] ?: return null
        val result = candidateProvider.reachableFrom(current.originSystemId, profile)
        require(result.originVerdict is EligibilityVerdict.Eligible) {
            "Overlay origin is not eligible: ${result.originVerdict}"
        }
        return current.copy(
            profile = profile,
            reachableSystemIds = result.reachableSystemIds,
            preset = preset,
            displayColor = displayColor,
            label = label,
        )
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
