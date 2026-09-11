---
name: eve-multi-point-route
description: Optimize the visit order for 1-50 EVE map solar-system targets from a fixed start using the live normal-route graph. Use for patrol, inspection, delivery, or marker-visiting requests that ask to cover many systems with as few jumps as practical; do not use for capital, Wormhole, fixed-end, or return-to-start routing.
---

# EVE Multi-Point Route

Use the bundled Python optimizer for the mathematical route ordering. The EVE Map MCP graph snapshot is the only
route-topology source.

## Prepare the request

1. Resolve the user's start and selected/created markers to canonical `systemId` values. Retain those IDs; do not
   search again by display name later.
2. Pass all marker system IDs to the optimizer. It safely deduplicates repeated systems and treats the start system
   as already visited when it also appears in the targets.
3. Set `useAnsiblex` to `true` unless the user explicitly requests Stargate-only routing or says not to use jump
   bridges. This workflow never uses Wormholes.
4. Keep the target count between 1 and 50 after deduplication and removal of the start.

Request JSON:

```json
{"startSystemId":30004759,"targetSystemIds":[30004760,30004761],"useAnsiblex":true}
```

## Run the optimizer

Use a Python 3 interpreter to run `scripts/optimize_route.py`. Pass a JSON file with `--input`, or pipe JSON to
stdin. Stdout is one compact JSON result; diagnostics use stderr.

```text
python scripts/optimize_route.py --input request.json
```

Launcher discovery order is:

1. `--mcp-command <path-to-eve-map-mcp>`
2. `EVE_MAP_MCP_COMMAND`
3. `%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json`, published by the running packaged map
4. `eve-map-mcp` on `PATH`

Use `--mcp-locator <file>` only when a non-default schema-1 locator is intentional. The script directly starts the
resolved STDIO launcher, initializes MCP, and calls `get_normal_route_graph` exactly once. It never calls
`calculate_normal_route`.

## Preserve optimizer authority

- Do not sort by visual coordinates, calculate TSP yourself, guess distances, or change `orderedTargets`.
- Every required target remains an explicit waypoint even if a shortest segment might pass through it.
- Treat `success: false` or any unreachable target as a failed plan; do not claim completion.
- If `guaranteedOptimal` is `true`, describe the result as the strict shortest route for this input.
- If `guaranteedOptimal` is `false`, describe it as an optimized approximate shortest route, never a guaranteed
  global optimum.

Report the ordered systems, `totalJumps`, and the coverage facts in this form: `X / X required systems covered`,
`Missing = 0`. Display a route segment on the map only when the user asks; this optimizer does not mutate map state,
markers, or route overlays.
