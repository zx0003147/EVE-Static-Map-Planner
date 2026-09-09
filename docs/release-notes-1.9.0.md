# EVE Static Map Planner 1.9.0

This release brings the browser map into visual and workflow parity with the Desktop map while preserving the
local-first Desktop application and optional Shared Map integration.

## Highlights

- Aligns Web and Desktop map semantics for Stargates, the complete enabled Ansiblex network, Normal routes,
  directional arrows, Capital routes, selection, Coverage, and Saved Markers.
- Adds configurable Coverage range in LY plus Region, Constellation, and System level-of-detail controls.
- Adds browser-local Personal Ansiblex CSV/JSON import with preview, validation, merge, and Pack-first deduplication.
- Replaces an ordinary system node with the Keepstar shape when a Keepstar Saved Marker is active, without losing
  selection, waypoint, or route state.
- Adds Desktop-to-Web Normal and Capital Route Handoff through Shared Map Protocol v1 feature `route-handoffs`.
- Makes informational notifications transient after about three seconds and errors transient after about six
  seconds, with replacement-safe timers and fade-out.
- Builds the reproducible `eve-map-web-1.9.0.zip` self-hosted Web artifact and SHA-256 metadata.

## Compatibility

- Shared Map protocol remains version 1.
- Route Handoff is negotiated through the `route-handoffs` feature flag.
- The browser and Desktop clients are compatible with EVE Shared Map Server 0.3.0.
- Existing Shared Marker workflows remain compatible.
- Feature API compatibility remains artifact `2.2.0`, runtime family `2`, and EVE ESI Pack `1.2.0`.
- Desktop remains usable without the optional Shared Map Server.

## Acceptance scope

- The production Web artifact is validated with the real 103-link enabled Ansiblex Pack.
- Chrome desktop and tablet automation covers routing, Coverage, notifications, Personal Ansiblex, Keepstar,
  pan/pinch, PWA installation metadata, app/Pack updates, and offline reopen.
- Samsung physical-device acceptance is not part of this release.
