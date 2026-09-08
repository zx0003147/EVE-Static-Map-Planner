# Web Client Phase 2

## Scope

Phase 2 adds a browser client for the existing static map, Normal Route, Ansiblex route, Waypoint, Capital Route, Jump Range, and Capital Coverage capabilities. Desktop remains the data-management application. The Web client has no SDE management, Ansiblex editing/import, Shared Marker, AI, MCP, local control, credential management, or PWA code.

The supported publication contract remains Web Pack `schemaVersion = 1`.

## Architecture and technology

The Web client uses Kotlin/JS IR and the browser Canvas/DOM APIs. This keeps the Web platform layer small while allowing both Desktop/JVM and Web/JS to compile the same Kotlin Core algorithms:

```text
manifest.json + gzip Web Pack
             |
             v
Phase 1 web-pack-loader.mjs
  size + SHA-256 + gzip + schema checks
             |
             v
WebPackDocumentDto
             |
             v
WebUniverseDataAdapter
  StaticMapData + RouteLink + in-memory repositories
             |
             v
shared :core
  MapSceneBuilder / SystemSpatialIndex / MapTransform
  RouteGraphBuilder / NormalNavigationPlanner
  CapitalRouteEngine / CapitalJumpCandidateProvider
  UniverseDistanceCalculator / JumpCoverageCalculator
             |
             v
WebPlannerController -> WebMapView -> Canvas and DOM UI
```

`:core` is a focused Kotlin Multiplatform module with JVM and JS targets. JVM-only records with `java.time` management metadata, Desktop synchronized caches, and Desktop repository interfaces remain in `jvmMain`. The map geometry, Official 2D projection, spatial index, route graph and planners, Capital route, jump eligibility/distance, and coverage operations are in `commonMain`. Desktop call sites convert their full Ansiblex/Wormhole records through `buildDesktopRouteGraph`; Web Pack Ansiblex records convert to the same platform-neutral `RouteLink` contract. There is one route implementation and one Capital implementation.

Kotlin/JS is configured to use the system Node installation and npm, so it does not add Node/Yarn download repositories to the dependency-resolution policy.

## Web Pack consumption

The Phase 1 loader remains the integrity boundary. It automatically loads `/data/manifest.json`, checks schema and manifest fields, downloads the versioned gzip file, verifies compressed size and SHA-256 with Web Crypto, decompresses with `DecompressionStream("gzip")`, validates document/manifest agreement, and returns the decoded document. Its optional status callback reports `Loading manifest`, `Loading Web Pack`, and `Validating`; the Kotlin entrypoint reports `Building map` and `Ready`.

The UI never reads raw dynamic JSON. `parseWebPackDocument` creates typed Web DTOs and checks every consumed field. `WebUniverseDataAdapter` then builds Core domain values and browser in-memory repositories:

- Systems preserve universe XYZ, security, effective wormhole classification, and nullable Official 2D positions.
- Missing Official 2D positions remain `null`. They stay searchable and routable but are included in `ProjectedMapScene.omittedSystemIds` and are never placed at `(0, 0)`.
- Stargates become canonical `StargateConnection` values.
- Active Ansiblex become directional `RouteLink` values; `FIRST_TO_SECOND` and `SECOND_TO_FIRST` are not treated as bidirectional.
- Region and Constellation names/relationships come from the Pack. Their required domain center is calculated from member-system universe positions; it is not used as a replacement Official 2D system position.

## Renderer and input

`WebMapView` renders the Official 2D scene with browser Canvas. It draws culled Stargate edges, Jump Range/coverage halos, Normal route legs, Ansiblex route legs, Capital jump legs, nodes, selected/hovered states, Waypoints, and LOD-controlled labels. Visual styles intentionally distinguish Stargate, Ansiblex, Capital, selected, route, Waypoint, and coverage states.

The shared `MapTransform` supplies fit, pan, world/screen conversion, cursor-centered zoom, and visible bounds. The shared `SystemSpatialIndex` supplies viewport node queries and hit testing, avoiding a full-system scan for every pointer move. Edge rendering uses scene-bound intersection culling. Label density increases with zoom; selected, hovered, route, and Waypoint systems have priority.

Supported Phase 2 input:

- mouse wheel: zoom around cursor;
- mouse or single-finger drag: pan;
- click or tap: select;
- mouse hover: hover highlight;
- **Fit Map**: restore Official 2D connected-map bounds.

Pinch zoom and final tablet interaction polish are deferred to Phase 4.

## Search and System Info

Search accepts system IDs and case-insensitive partial names. Exact and prefix matches rank before other substring matches. Selecting a result selects the system and centers it when Official 2D coordinates exist. An unpositioned result is still selected and produces an explicit location message.

System Info displays name, ID, Region, Constellation, security, effective wormhole class when available, Stargate count, active Ansiblex count, Jump Coverage count, universe XYZ, and Official 2D availability.

## Normal Route, Ansiblex, and Waypoints

The Web client builds one Core `RouteGraph` from Pack Stargates and active Pack Ansiblex. `NormalNavigationPlanner` calculates ordered segments over `NormalRouteEngine`:

- Ansiblex defaults off and is controlled by **Use active Ansiblex**.
- Directionality is enforced in the shared `RouteLinkEdgeBuilder`.
- Multiple Waypoints can be added, removed, and reordered.
- Segment results are composed into one route without duplicating boundary systems.
- An unreachable segment names its one-based segment and endpoint systems.
- Route summary and Canvas overlay distinguish Stargate and Ansiblex jumps.

The Web client deliberately has no Ansiblex add/delete/enable/import UI.

## Capital Route

The effective jump range is entered in LY, matching the current Desktop manual-range workflow. `UniformGridSystemPositionIndex`, `CapitalJumpCandidateProvider`, and `CapitalRouteEngine` are shared with Desktop. The calculation therefore preserves the existing EVE LY constant, three-dimensional XYZ distance, high-security destination rule, New Eden/wormhole/Pochven/Jove/Abyssal classification, endpoint eligibility, and deterministic breadth-first result.

The result includes jump count, total LY, route systems, and a distinct dashed Capital overlay. Ship-specific presets are not invented because the current Core/Desktop profile exposed here is the user-effective manual range.

## Jump Range and Capital Coverage

**Add Range** calculates direct candidates from one chosen source with the current effective range and shared eligibility rules. Each action creates an independent overlay. Multiple sources can coexist and can be removed individually or cleared together.

Capital Coverage follows the current Desktop meaning: the per-system count of enabled Jump Range overlays. A count greater than one is overlapping coverage. `JumpCoverageCalculator` is shared by JVM and JS, and the Canvas uses a stronger ring for overlap. Phase 2 does not introduce a different fleet or server-side meaning.

## Build, test, and run

Run Web unit/consistency tests:

```powershell
.\gradlew.bat :web-client:jsNodeTest webLoaderTest
```

Export a Web Pack directly from existing Desktop databases (the Preferences export remains available):

```powershell
.\gradlew.bat :app:exportWebPackCli `
  "-PwebStaticDb=C:\path\to\static.db" `
  "-PwebUserDb=C:\path\to\user.db" `
  "-PwebPackOutput=C:\path\to\EVE-Web-Pack"
```

Build a deployable Web directory and stage that Pack under `/data`:

```powershell
.\gradlew.bat :web-client:webProduction `
  "-PwebPackDir=C:\path\to\EVE-Web-Pack"
```

Production output:

```text
web-client/build/dist/js/productionExecutable/
|-- index.html
|-- web-client.css
|-- web-client.js
|-- web-pack-loader.mjs
`-- data/
    |-- manifest.json
    `-- web-pack-<version>.json.gz
```

The data property is optional for application-only builds. Without it, deploy a valid Web Pack at `/data` separately. Serve the directory over HTTP(S); opening `index.html` with `file://` cannot satisfy Fetch/Web Crypto requirements.

For an auto-reloading development server, stage data with the same property and run:

```powershell
.\gradlew.bat :web-client:webDev `
  "-PwebPackDir=C:\path\to\EVE-Web-Pack"
```

Modern Chromium, Google Chrome, and Microsoft Edge are the Phase 2 browser baseline. The loader requires Fetch, Web Crypto, and `DecompressionStream("gzip")`; use HTTPS or localhost.

## Tests and consistency

The JS test suite covers DTO-to-domain mapping, Region/Constellation relationships, Stargates, nullable Official positions, directional Ansiblex, search, Ansiblex off/on, reverse direction, unreachable segments, ordered Waypoints, Capital distance/routing, within/outside range, Jump Range, multi-source coverage/intersection, projection, fit, culling, and indexed picking. Deterministic route and Capital results are compared between direct shared-Core calls and the Web-Pack-backed universe.

`qa/web-client-browser-smoke.mjs` is a dependency-free Chrome DevTools Protocol smoke client for a local headless Chromium browser. It verifies real Pack readiness, Canvas creation, Jita search/System Info, Jita-to-Perimeter Normal Route, 1DQ1-A-to-T5ZI-S Capital Route, Jump Range, Fit, and wheel zoom. It also reports observed ready and route timings without presenting them as a formal benchmark.

## Known limitations and phase boundaries

- Official 2D is the only Web projection in Phase 2; Desktop Real 3D remains Desktop-only.
- 3,005 systems in the current tested SDE Pack have no Official 2D coordinates. They remain searchable/routable and are reported as unpositioned.
- Phase 2 provides single-pointer touch pan/tap, not pinch zoom or final tablet-responsive polish.
- Capital route uses the current effective manual LY profile; no unverified ship/rule presets were added.
- The renderer is event-driven and indexed/culling-aware, but this phase does not claim GPU/WebGL rendering or formal frame-time benchmarks.
- No Web Pack persistence/offline app shell, service worker, install manifest, or PWA behavior exists.

Phase 3 may connect Web to the existing Shared Marker protocol, but no Shared Marker client/server/auth/protocol code is changed here. That work must separately address Server URL and Invite Code UX, reuse of protocol/client models in a browser-compatible module, CORS, HTTPS mixed-content rules, WebSocket/SSE support as applicable, reconnect behavior, and browser-safe credential storage.

Phase 4 remains responsible for PWA work, offline persistence design, pinch zoom, and final tablet layout/gesture refinement.
