# EVE Static Map Planner 1.7.0

This release improves route readability across the main map, Real 3D view, and Mini-map, while keeping route
calculation and existing map-connection behavior unchanged.

## Highlights

### Directional route Chevrons

- Adds open Chevron direction markers to Normal, Capital, AI Normal, and AI Capital routes.
- Shows the same ordered route direction in the 2D map, Real 3D view, and Mini-map.
- Uses the local curve tangent for direction markers on curved Ansiblex route segments.
- Keeps ordinary Stargate, Ansiblex, and Wormhole network connections free of route arrows.

### Mission routes in the Mini-map

- Displays authoritative AI Normal and AI Capital mission routes without recalculating them.
- Allows the user's Normal route and both AI route types to remain visible together.
- Preserves the existing route colors, line styles, and route-index color sequence.
- Draws only route segments whose two endpoints are already present in the current Mini-map slice.

### Mini-map hop behavior

- Counts Stargates as one hop when building the Mini-map neighborhood.
- Optionally counts enabled Ansiblex connections as one hop when Ansiblex display is enabled.
- Does not change the configured Hop Range or expand the slice solely to include route segments.
- Keeps existing route overlays independent from the `Show Ansiblex connections` network-line toggle.

## Compatibility and architecture

- Normal and Capital route computation is unchanged.
- AI route generation and prompts are unchanged.
- No database schema, ESI integration, EVE ESI Pack, or Feature API changes are included.
- Compatibility remains Feature API artifact `2.2.0`, runtime family `2`, and EVE ESI Pack `1.2.0`.

## Known behavior

- A Mini-map route segment is intentionally hidden when either endpoint falls outside the current slice.
