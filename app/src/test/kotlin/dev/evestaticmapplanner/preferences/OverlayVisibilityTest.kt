package dev.evestaticmapplanner.preferences

import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayLayerState
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OverlayVisibilityTest {
    private val firstKey = OverlayLayerKey("fixture.provider", "first")
    private val secondKey = OverlayLayerKey("fixture.provider", "second")

    @Test
    fun `layers are enabled by default and can be disabled then enabled`() {
        val defaults = OverlayVisibilityPreferences.Defaults

        assertTrue(defaults.isEnabled(firstKey))
        val disabled = defaults.withEnabled(firstKey, false)
        assertFalse(disabled.isEnabled(firstKey))
        assertTrue(disabled.isEnabled(secondKey))
        val restored = disabled.withEnabled(firstKey, true)
        assertTrue(restored.isEnabled(firstKey))
        assertEquals(OverlayVisibilityPreferences.Defaults, restored)
    }

    @Test
    fun `UI state lists readable overlay and provider information`() {
        val rawState = overlayState()
        val preferences = OverlayVisibilityPreferences.Defaults.withEnabled(secondKey, false)

        val uiState = OverlayManagementUiStateBuilder.build(rawState, preferences)

        assertEquals(listOf("First Layer", "Second Layer"), uiState.overlays.map { it.name })
        assertEquals(listOf("Fixture Provider", "Fixture Provider"), uiState.overlays.map { it.providerName })
        assertEquals(listOf(true, false), uiState.overlays.map { it.enabled })
        assertFalse(uiState.showSovereigntyLogoPreferences)
        val toggled = preferences.withEnabled(uiState.overlays[1].key, true)
        assertTrue(toggled.isEnabled(secondKey))
    }

    @Test
    fun `Sovereignty logo setting is absent without registered Sovereignty overlay`() {
        val empty = OverlayManagementUiStateBuilder.build(
            OverlayState(emptyList()),
            OverlayVisibilityPreferences.Defaults,
        )
        val unrelated = OverlayManagementUiStateBuilder.build(
            overlayState(),
            OverlayVisibilityPreferences.Defaults,
        )

        assertFalse(empty.showSovereigntyLogoPreferences)
        assertFalse(unrelated.showSovereigntyLogoPreferences)
    }

    @Test
    fun `Sovereignty logo setting is present with registered Sovereignty overlay`() {
        val provider = OverlayProviderDescriptor("sovereignty.pack.overlay", "Sovereignty")
        val state = OverlayState(listOf(
            OverlayLayerState(
                provider,
                OverlayLayer("sovereignty", "Sovereignty"),
                listOf(OverlayEntry("sovereignty", 30_000_001)),
            ),
        ))

        val uiState = OverlayManagementUiStateBuilder.build(state, OverlayVisibilityPreferences.Defaults)

        assertTrue(uiState.showSovereigntyLogoPreferences)
    }

    @Test
    fun `visibility filter sends only enabled layers to renderer presentation`() {
        val rawState = overlayState()
        val preferences = OverlayVisibilityPreferences.Defaults.withEnabled(firstKey, false)

        val visible = OverlayVisibilityFilter.visibleState(rawState, preferences)

        assertEquals(listOf("second"), visible.layers.map { it.layer.id })
        assertEquals(listOf(30_000_002), visible.layers.flatMap { it.entries }.map { it.systemId })
        assertEquals(2, rawState.layers.size)
        assertSame(rawState.layers[1], visible.layers.single())
    }

    private fun overlayState(): OverlayState {
        val provider = OverlayProviderDescriptor(
            id = "fixture.provider",
            name = "Fixture Provider",
            description = "Provider description",
        )
        return OverlayState(listOf(
            OverlayLayerState(
                provider,
                OverlayLayer("first", "First Layer", "First description"),
                listOf(OverlayEntry("first", 30_000_001)),
            ),
            OverlayLayerState(
                provider,
                OverlayLayer("second", "Second Layer", "Second description"),
                listOf(OverlayEntry("second", 30_000_002)),
            ),
        ))
    }
}
