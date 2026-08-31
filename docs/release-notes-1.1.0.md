# EVE Static Map Planner 1.1.0 release candidate

## Temporary Wormhole connections

- Add bidirectional, application-session Wormhole connections through the global Manager or system right-click menu.
- Display the shared topology with static Peacock Teal map geometry; no animation or separate visibility toggle is used.
- Include Wormholes in Normal Routes when the current Planning View enables `Use Wormholes`; each traversal counts as one jump.
- Keep topology global while preserving an independent `Use Wormholes` choice in every Planning View.
- Remove affected user and Mission Normal Routes when a used Wormhole is deleted or cleared, without recalculation or over-clearing unrelated Mission state.
- Let AI/MCP list and create Wormholes. AI cannot update, remove, delete, clear, replace, import, export, or persist them.
- Clear the entire Wormhole network when the application session ends; no database schema or migration was added.

Feature API v2 remains frozen at artifact `2.0.0`. User routes containing Wormholes deliberately have no Feature Route
Actions because v2 has no Wormhole route-segment kind. Capital Routes and Jump Range calculations ignore Wormholes.

## Compatibility

- Core application: `1.1.0`
- Feature API: `2.0.0`
- User database schema: `4`
- MCP catalog: `30` tools
- Control requests that omit `useWormholes` retain the previous `false` behavior.
