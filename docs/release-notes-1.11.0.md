# EVE Static Map Planner 1.11.0

This release improves the Desktop navigation workspace and adds a read-only normal-route graph API for external AI
and automation integrations.

## Highlights

### Desktop UX

- Adds an always-on-top Pin toggle to the Desktop window.
- Adds a collapsible sidebar with a compact icon rail that preserves section state and returns map space when closed.
- Unifies Search, Jump Range Overlays, Normal Route, and Capital Route into consistent expandable sidebar sections.
- Improves Normal and Capital Route icons, including optical alignment in the compact rail.
- Groups system context-menu actions by marker, jump range, normal navigation, capital navigation, and Wormhole use.
- Aligns Jump Range input styling with the Normal Route fields while preserving clearly visible numeric input.

### AI / MCP

- Adds the read-only `get_normal_route_graph` MCP tool for a versioned snapshot of the current directed normal-route
  topology.
- Includes Stargates and, when requested, enabled Ansiblex connections; Wormholes are excluded from graph V1.
- Enables advanced external integrations such as the separate EVE Map Assistant Plugin multi-point route skill.

## Compatibility / Notes

- The multi-point route skill requires EVE Static Map Planner 1.11.0 or later and is distributed through the
  separate EVE Map Assistant Plugin repository; it is not bundled in the Planner ZIP.
- Existing user data and database schemas require no migration for this release.
- Shared Map protocol version `1`, Shared Map Server target `0.3.1`, Flyway schema `4`, self-hosted distribution
  target `1.2.0`, and Feature API artifact `2.2.0` remain unchanged.

## Downloads

- Windows x64 Portable ZIP, including the Desktop application and MCP launchers.
- Self-hosted Web package with checksum and release metadata.
