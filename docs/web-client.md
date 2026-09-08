# Web Client Phase 3

## Scope

Phase 3 keeps the Phase 2 static map and planning features and makes the browser a client of the existing Shared Map Server. It uses the same Server URL, single-use Invite Code, Protocol v1, Workspace roles, marker identity, fields, optimistic versions, and server authority as Desktop. It does not add an account system or a second backend. Desktop remains the SDE, Ansiblex, and Web Pack data-management application; the Web client still has no AI, MCP, local control, PWA, or offline marker editing.

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

shared-client commonMain Protocol v1 DTOs / JSON vocabulary
             |
             +-- Desktop Ktor CIO transport (JVM)
             `-- Browser Fetch transport (JS)
                         |
                         v
WebSharedMarkerController -> dynamic marker overlay and DOM panel
```

`:core` is a focused Kotlin Multiplatform module with JVM and JS targets. `:shared-client` now also has JVM and JS targets: frozen wire DTOs and JSON configuration live in `commonMain`, while Desktop keeps its Ktor CIO transport, `java.time`/UUID domain mapping, DPAPI-backed credentials, and session code in `jvmMain`. Browser Fetch and browser state are implemented in `:web-client`. The Web client does not copy or redefine Protocol v1.

JVM-only records with `java.time` management metadata, Desktop synchronized caches, and Desktop repository interfaces remain in `jvmMain`. Map geometry, Official 2D projection, spatial index, route graph and planners, Capital route, jump eligibility/distance, and coverage operations are in `:core` `commonMain`. Desktop call sites convert their full Ansiblex/Wormhole records through `buildDesktopRouteGraph`; Web Pack Ansiblex records convert to the same platform-neutral `RouteLink` contract. There is one route implementation and one Capital implementation.

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

## Shared Marker connection and permissions

Open **Shared Markers**, enter the Shared Map Server origin (for example `https://marker.example.com`), paste an existing single-use Invite Code, choose a device name, and select **Connect / Join**. The browser calls the existing invite-exchange endpoint, validates Protocol v1 and the Shared Markers feature, loads `/me`, the assigned Workspace, and its authoritative marker snapshot. The Invite Code field is cleared after the attempt. **Disconnect** stops polling and removes the in-memory Shared Marker token and snapshot without changing routes, Waypoints, coverage, local data, or the server.

The browser stores only the last successfully connected Server URL in `localStorage`. The Invite Code and the issued 90-day Device Access Token are never put in Web Pack, URLs, console output, or persistent browser storage; the token is memory-only and is lost on reload or page close. A reload therefore requires a new Server-issued invite. This intentionally trades persistent login convenience for a smaller Phase 3 browser credential surface. Desktop continues to protect its token with Windows DPAPI and is unchanged.

`VIEWER` can load and locate markers. `EDITOR` and `ADMIN` can create a marker for the selected system and edit or delete existing markers. The UI reflects the resolved server role, but every request is still authorized by the server. Create/edit supports only the existing fields: name, one of the frozen colors, tags, and notes. Delete requires confirmation and affects only the selected Shared Marker.

Shared Markers are online dynamic state and remain completely outside Web Pack. Snapshot updates replace only the marker map; they do not rebuild the universe, projected static scene, route graph, or Web Pack data. Markers render as colored outer rings with a small badge, remain distinct from node selection, Waypoints, Normal/Ansiblex/Capital routes, Jump Range, and coverage, and receive a stronger selected treatment. A marker whose system has no Official 2D point remains in the list with an explicit location message and is never placed at `(0, 0)`. An unknown future system ID is also retained in the list as an incompatible Web Pack reference.

## Refresh, reconnect, and page lifecycle

The server exposes polling rather than WebSocket or SSE, so Web deliberately follows Desktop's 30-second full-snapshot polling contract. Successful Web mutations reconcile immediately from the server response; another client's changes appear after the next poll (normally within 30 seconds). Optimistic update/delete sends the current marker version. A `409` conflict adopts the server's current marker when supplied and reports the conflict instead of overwriting it.

Network loss leaves the last in-memory snapshot visible and marks the connection **Reconnecting**. Retries use 5, 10, 20, then 30-second delays and stay capped at 30 seconds. Restoring a hidden tab triggers an immediate refresh; browser timer throttling may lengthen background-tab intervals. A `401` clears the memory token and changes to **Auth failed**, requiring a new invite. A `403` changes to **Forbidden** and clears inaccessible marker state. Page close cancels polling and clears process memory; there is no background sync or offline mutation queue.

## Browser origin, CORS, and HTTPS

The Shared Map Server must configure the exact Web application origin in `SHARED_MAP_ALLOWED_ORIGINS`, for example:

```text
SHARED_MAP_ALLOWED_ORIGINS=https://map.example.com
```

Multiple origins are comma-separated. The server does not accept `*`; credentials/cookies are disabled; only the existing REST methods and required headers (`Authorization`, `Content-Type`, `X-Request-Id`, and `Idempotency-Key`) are allowed. `OPTIONS` preflight is handled by the server. Requests without an `Origin` header continue to work for Desktop. Local development may use `http://localhost:<port>` or `http://127.0.0.1:<port>`; non-loopback origins must be HTTPS.

Production must serve both Web and Shared Map Server over HTTPS, normally with the existing Caddy TLS reverse proxy in front of Ktor. An HTTPS page cannot connect to a plain-HTTP remote server because browsers block mixed content, and the Web client does not offer an unsafe bypass. Protocol v1 currently has no WebSocket/SSE transport, so no `wss://` endpoint is required in Phase 3.

## Build, test, and run

Run protocol and Web unit/consistency tests:

```powershell
.\gradlew.bat :shared-client:jvmTest :shared-client:jsNodeTest :web-client:jsNodeTest webLoaderTest
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

Modern Chromium, Google Chrome, and Microsoft Edge are the browser baseline. The loader requires Fetch, Web Crypto, and `DecompressionStream("gzip")`; use HTTPS or localhost.

For local Shared Marker development, start the existing development PostgreSQL/server per the server README, serve the Web production/dev output from an origin in `SHARED_MAP_ALLOWED_ORIGINS`, create an invite through the existing Admin/Desktop flow, and enter it in the browser. Do not put the invite in a Gradle property, command line, URL, or checked-in fixture.

## Tests and consistency

The common/JVM/JS suites cover Protocol v1 DTO round trips and frozen vocabulary, redaction, exact REST paths/headers/bodies, invite exchange, marker CRUD, optimistic conflict reconciliation, permissions, malformed responses, disconnected/connecting/connected/reconnecting/auth-failed states, capped retry timing, and positioned/unpositioned/unknown marker systems. Existing mapping, route, Capital, Jump Range, coverage, projection, culling, and picking tests remain in place. Deterministic route and Capital results are compared between direct shared-Core calls and the Web-Pack-backed universe.

`qa/web-client-browser-smoke.mjs` is a dependency-free Chrome DevTools Protocol smoke client for a local headless Chromium browser. It verifies real Pack readiness, Canvas creation, Shared Marker disconnected controls, Jita search/System Info, Jita-to-Perimeter Normal Route, 1DQ1-A-to-T5ZI-S Capital Route, Jump Range, Fit, and wheel zoom. It also reports observed ready and route timings without presenting them as a formal benchmark. A real Desktop/Web interoperability acceptance still requires one running PostgreSQL-backed Shared Map Server and fresh role-appropriate single-use invites; record all six create/edit/delete directions rather than substituting mock clients.

For a manual local browser smoke, serve the production directory with `node qa/static-web-server.mjs`, start Chrome
or Edge headless with a local remote-debugging port, and run:

```powershell
node qa/web-client-browser-smoke.mjs <port> http://127.0.0.1:8765/
```

The helper serves only local static artifacts and is not a production server.

## Known limitations and phase boundaries

- Official 2D is the only Web projection; Desktop Real 3D remains Desktop-only.
- 3,005 systems in the current tested SDE Pack have no Official 2D coordinates. They remain searchable/routable and are reported as unpositioned.
- Web provides single-pointer touch pan/tap, not pinch zoom or final tablet-responsive polish.
- Capital route uses the current effective manual LY profile; no unverified ship/rule presets were added.
- The renderer is event-driven and indexed/culling-aware, but this phase does not claim GPU/WebGL rendering or formal frame-time benchmarks.
- No Web Pack persistence/offline app shell, service worker, install manifest, PWA behavior, offline Shared Marker editing, or mutation queue exists.
- Browser Device Access Tokens are memory-only. Reloading needs a fresh single-use invite; persistent browser login is not implemented.
- Shared Marker live updates use the server's existing 30-second snapshot polling, not push delivery. Background tab throttling can increase observed latency.
- Phase 3 does not expose Shared Marker member, invite, role, or device administration in Web. Those existing administrative workflows remain Desktop/server responsibilities.

Phase 4 remains responsible for PWA work, offline persistence design, pinch zoom, and final tablet layout/gesture refinement.
