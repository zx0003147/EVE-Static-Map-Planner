package dev.evestaticmapplanner.preferences

enum class MiniMapFollowMode { AUTO, PINNED }

enum class MiniMapWindowStyle { STANDARD, HUD }

enum class MiniMapInteractionMode { INTERACTIVE, HUD_LOCKED }

data class MiniMapWindowBounds(
    val x: Float = 80f,
    val y: Float = 80f,
    val width: Float = 420f,
    val height: Float = 360f,
) {
    init {
        require(x.isFinite() && y.isFinite())
        require(width.isFinite() && width in 280f..2_000f)
        require(height.isFinite() && height in 240f..2_000f)
    }
}

data class MiniMapPreferences(
    val enabled: Boolean = false,
    val stargateHops: Int = 2,
    val followMode: MiniMapFollowMode = MiniMapFollowMode.AUTO,
    val pinnedCharacterId: Long? = null,
    val windowBounds: MiniMapWindowBounds = MiniMapWindowBounds(),
    val includeAnsiblexEdges: Boolean = false,
    val windowStyle: MiniMapWindowStyle = MiniMapWindowStyle.STANDARD,
    val interactionMode: MiniMapInteractionMode = MiniMapInteractionMode.INTERACTIVE,
    val hudOpacity: Float = 0.88f,
    val snapToScreenEdges: Boolean = true,
) {
    init {
        require(stargateHops in 1..5)
        require(pinnedCharacterId == null || pinnedCharacterId > 0)
        require(hudOpacity.isFinite() && hudOpacity in 0.4f..1f)
    }

    companion object { val Defaults = MiniMapPreferences() }
}
