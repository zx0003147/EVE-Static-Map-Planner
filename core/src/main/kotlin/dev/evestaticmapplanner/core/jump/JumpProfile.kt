package dev.evestaticmapplanner.core.jump

data class JumpProfile(
    val id: String,
    val displayName: String,
    val maxRangeLy: Double,
) {
    init {
        require(id.isNotBlank()) { "Jump profile ID must not be blank" }
        require(displayName.isNotBlank()) { "Jump profile display name must not be blank" }
        require(maxRangeLy.isFinite() && maxRangeLy > 0.0) {
            "Manual maximum jump range must be finite and positive"
        }
    }

    companion object {
        fun manual(maxRangeLy: Double, id: String = "manual"): JumpProfile = JumpProfile(
            id = id,
            displayName = "Manual ${formatRange(maxRangeLy)} LY",
            maxRangeLy = maxRangeLy,
        )

        private fun formatRange(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
