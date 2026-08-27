package dev.evestaticmapplanner.preferences

import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayState

data class OverlayLayerKey(
    val providerId: String,
    val layerId: String,
) {
    init {
        require(providerId.length in 1..64) { "Overlay provider ID length is invalid" }
        require(layerId.length in 1..64) { "Overlay layer ID length is invalid" }
        require(OVERLAY_ID_SYNTAX.matches(providerId)) { "Overlay provider ID is invalid" }
        require(OVERLAY_ID_SYNTAX.matches(layerId)) { "Overlay layer ID is invalid" }
    }

    internal fun encode(): String = "$providerId/$layerId"

    companion object {
        internal fun decode(value: String): OverlayLayerKey? {
            val parts = value.split('/')
            if (parts.size != 2) return null
            return runCatching { OverlayLayerKey(parts[0], parts[1]) }.getOrNull()
        }
    }
}

data class OverlayVisibilityPreferences(
    val disabledLayers: Set<OverlayLayerKey> = emptySet(),
) {
    fun isEnabled(key: OverlayLayerKey): Boolean = key !in disabledLayers

    fun withEnabled(key: OverlayLayerKey, enabled: Boolean): OverlayVisibilityPreferences = copy(
        disabledLayers = if (enabled) disabledLayers - key else disabledLayers + key,
    )

    companion object {
        val Defaults = OverlayVisibilityPreferences()
    }
}

data class OverlayManagementItem(
    val key: OverlayLayerKey,
    val name: String,
    val description: String?,
    val providerName: String,
    val providerDescription: String?,
    val enabled: Boolean,
)

data class OverlayManagementUiState(
    val overlays: List<OverlayManagementItem>,
    val showSovereigntyLogoPreferences: Boolean,
)

object OverlayManagementUiStateBuilder {
    fun build(
        overlayState: OverlayState,
        visibility: OverlayVisibilityPreferences,
    ): OverlayManagementUiState = OverlayManagementUiState(
        overlays = overlayState.layers.map { layerState ->
            val key = layerState.key()
            OverlayManagementItem(
                key = key,
                name = layerState.layer.name,
                description = layerState.layer.description,
                providerName = layerState.provider.name,
                providerDescription = layerState.provider.description,
                enabled = visibility.isEnabled(key),
            )
        },
        showSovereigntyLogoPreferences = overlayState.layers.any { layerState ->
            layerState.provider.id == SOVEREIGNTY_PROVIDER_ID && layerState.layer.id == SOVEREIGNTY_LAYER_ID
        },
    )
}

object OverlayVisibilityFilter {
    fun visibleState(
        overlayState: OverlayState,
        visibility: OverlayVisibilityPreferences,
    ): OverlayState = OverlayState(
        overlayState.layers.filter { visibility.isEnabled(it.key()) },
    )
}

private fun OverlayLayerState.key() = OverlayLayerKey(provider.id, layer.id)

private val OVERLAY_ID_SYNTAX = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
private const val SOVEREIGNTY_PROVIDER_ID = "sovereignty.pack.overlay"
private const val SOVEREIGNTY_LAYER_ID = "sovereignty"
