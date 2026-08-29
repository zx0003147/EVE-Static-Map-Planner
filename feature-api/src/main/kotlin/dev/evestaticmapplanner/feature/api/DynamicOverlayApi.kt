package dev.evestaticmapplanner.feature.api

/**
 * Optional Host capability for overlay providers whose snapshot may change after registration.
 *
 * The Pack supplies display-neutral snapshots and only signals that a provider may have changed.
 * The Host retains aggregation and rendering ownership; this contract exposes no renderer,
 * Compose object, coordinates, StateFlow, ViewModel, or mutable Host state.
 */
interface DynamicOverlayCapability : FeatureCapability {
    /** Registers a provider whose snapshots may later be refreshed. */
    fun register(provider: OverlayProvider): DynamicOverlayRegistration
}

/**
 * Registration for one dynamic provider.
 *
 * [requestRefresh] tells the Host that the provider data may have changed and that the Host should
 * read that provider's snapshot again. Host execution behavior is intentionally outside contract 2;
 * a future Host implementation may target the provider, coalesce requests, run non-blockingly, and
 * retain the last good snapshot.
 */
interface DynamicOverlayRegistration : OverlayRegistration {
    fun requestRefresh()
}
