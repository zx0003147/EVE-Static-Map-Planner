# EVE Static Map Planner 1.8.0

This release adds the complete browser map and the production artifact required by the official self-hosted
distribution, while keeping the Windows Desktop application and local-first workflow available.

## Highlights

- Adds the browser Web client, installable PWA, and tablet-oriented layout.
- Supports Normal routes with Stargates, enabled Ansiblex connections, and ordered waypoints.
- Supports Capital routes, jump-range overlays, coverage analysis, and route visualization.
- Adds Shared Marker access from the browser through an explicitly configured HTTPS Server origin.
- Exports a versioned, checksummed Web Pack from Desktop data without including Shared Marker data.
- Builds the reproducible `eve-map-web-1.8.0.zip` self-hosted Web artifact with release metadata and SHA-256.

## Compatibility

- Shared Map protocol remains version 1.
- The browser client is compatible with EVE Shared Map Server 0.2.0.
- Feature API compatibility remains artifact `2.2.0`, runtime family `2`, and EVE ESI Pack `1.2.0`.
- Desktop remains usable without the optional Shared Map Server.

## Known limitations

- Samsung tablet hardware has not yet completed formal acceptance testing.
- The Web client uses the existing map layout; the Desktop Real XZ/3D presentation is not part of this Web release.
