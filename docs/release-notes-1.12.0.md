# EVE Static Map Planner 1.12.0

This release adds native multi-point route optimization for reliable AI-assisted planning directly inside Planner.

## Highlights

### Native Multi-Point Route Optimization

- Adds the `optimize_multi_point_route` MCP tool.
- Plans an efficient visit order for up to 50 required systems from a fixed start.
- Uses exact Held-Karp optimization for 1-15 targets and deterministic heuristic optimization for 16-50 targets.
- Supports the current enabled Ansiblex network when requested; Wormholes are excluded.
- Validates full coverage so missing or unreachable targets are reported instead of silently omitted.

### AI Integration

- Multi-point route planning now runs directly inside Planner.
- Normal use requires no Python subprocess, second MCP process, or localhost bridge.
- Integrates with the separate EVE Map Assistant Plugin while keeping Planner independent of the Plugin.

### Performance / Resource Behavior

- Optimization is calculated only for the current request, with no background optimizer or GPU computation.
- Temporary calculation memory is not retained after the request completes.
- Validated with up to 50 required systems.

## Compatibility

- The EVE Map Assistant Plugin multi-point route Skill requires EVE Static Map Planner 1.12.0 or later and the
  `optimize_multi_point_route` MCP tool.
- Python scripts in the AI Plugin are reference and offline-verification material only; they are not part of the
  production runtime path.
- Existing user data and database schemas require no migration for this release.
- The read-only `get_normal_route_graph` MCP tool remains available for compatible integrations.

## Downloads

- Windows x64 Portable ZIP, including the Desktop application and MCP launchers.
- Self-hosted Web package with checksum and release metadata.
