package dev.evestaticmapplanner

import dev.evestaticmapplanner.feature.api.CharacterTrackingProvider
import dev.evestaticmapplanner.feature.api.CharacterTrackingSnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.TrackedCharacterAuthorizationState
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.feature.api.TrackedCharacterOnlineState
import dev.evestaticmapplanner.feature.api.TrackedCharacterSnapshot
import dev.evestaticmapplanner.featurepack.CharacterTrackingHost
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniMapCapabilityGatingTest {
    @Test
    fun `no Character Tracking provider hides the complete Mini-map menu`() {
        val host = CharacterTrackingHost()

        assertFalse(host.availability.value)
        assertFalse(hasMiniMapMenu(host.availability.value))
        host.close()
    }

    @Test
    fun `registered capability with zero characters still shows the Mini-map menu`() {
        val host = CharacterTrackingHost()
        val registration = host.scopedCapability(PackId("esi.pack"))
            .register(Provider(emptyList()))

        assertTrue(host.availability.value)
        assertTrue(host.state.value.isEmpty())
        assertTrue(hasMiniMapMenu(host.availability.value))

        registration.close()
        host.close()
    }

    @Test
    fun `registered capability with characters shows the normal Mini-map menu`() {
        val host = CharacterTrackingHost()
        val registration = host.scopedCapability(PackId("esi.pack"))
            .register(Provider(listOf(character())))

        assertTrue(host.availability.value)
        assertTrue(host.state.value.isNotEmpty())
        assertTrue(hasMiniMapMenu(host.availability.value))

        registration.close()
        host.close()
    }

    @Test
    fun `old Pack without a typed provider leaves Mini-map hidden`() {
        val host = CharacterTrackingHost()
        val oldPackCapability = host.scopedCapability(PackId("old.esi.pack"))

        assertFalse(host.availability.value)
        assertFalse(hasMiniMapMenu(host.availability.value))

        oldPackCapability.close()
        host.close()
    }

    @Test
    fun `provider unload hides Mini-map and reload makes its menu available again`() {
        val host = CharacterTrackingHost()
        val firstCapability = host.scopedCapability(PackId("esi.pack"))
        firstCapability.register(Provider(emptyList()))
        assertTrue(hasMiniMapMenu(host.availability.value))

        firstCapability.close()
        assertFalse(host.availability.value)
        assertFalse(hasMiniMapMenu(host.availability.value))

        val reloadedCapability = host.scopedCapability(PackId("esi.pack"))
        reloadedCapability.register(Provider(emptyList()))
        assertTrue(host.availability.value)
        assertTrue(hasMiniMapMenu(host.availability.value))

        reloadedCapability.close()
        host.close()
    }

    @Test
    fun `unrelated Pack scope load and unload does not affect Character Tracking availability`() {
        val host = CharacterTrackingHost()
        val trackingCapability = host.scopedCapability(PackId("esi.pack"))
        trackingCapability.register(Provider(emptyList()))
        val unrelatedCapability = host.scopedCapability(PackId("unrelated.pack"))

        unrelatedCapability.close()

        assertTrue(host.availability.value)
        assertTrue(hasMiniMapMenu(host.availability.value))
        trackingCapability.close()
        host.close()
    }

    @Test
    fun `capability loss immediately removes open Mini-map surfaces and requests durable close`() {
        val decision = miniMapCapabilityUiDecision(
            characterTrackingAvailable = false,
            miniMapEnabled = true,
            settingsWindowOpen = true,
        )

        assertFalse(decision.showMiniMapWindow)
        assertFalse(decision.showSettingsWindow)
        assertTrue(decision.disableMiniMap)
        assertTrue(decision.closeSettingsWindow)
    }

    private fun hasMiniMapMenu(available: Boolean): Boolean = plannerTopMenus(
        state = PlannerTopMenuState(
            markerManagerOpen = false,
            sharedMarkerManagerOpen = false,
            temporaryMarkerCount = 0,
            characterTrackingAvailable = available,
            miniMapEnabled = false,
            staticDataOpen = false,
        ),
        actions = PlannerTopMenuActions(
            openMarkerManager = {},
            openSharedMarkerManager = {},
            clearTemporaryMarkers = {},
            openMarkerSettings = {},
            toggleMiniMap = {},
            openMiniMapSettings = {},
            openPreferences = {},
            openStaticData = {},
        ),
    ).any { it.label == "Mini-map" }

    private class Provider(
        private val characters: List<TrackedCharacterSnapshot>,
    ) : CharacterTrackingProvider {
        override fun snapshot() = CharacterTrackingSnapshot(characters)
    }

    private fun character() = TrackedCharacterSnapshot(
        characterId = 90_000_001,
        characterName = "Alpha",
        authorizationState = TrackedCharacterAuthorizationState.CONNECTED,
        trackingEnabled = true,
        solarSystemId = 30_000_001,
        locationStatus = TrackedCharacterLocationStatus.CURRENT,
        locationObservedAt = Instant.EPOCH,
        lastValidatedAt = Instant.EPOCH,
        lastChangedAt = Instant.EPOCH,
        onlineState = TrackedCharacterOnlineState.UNKNOWN,
        retryAfter = null,
        lastErrorCategory = null,
    )
}
