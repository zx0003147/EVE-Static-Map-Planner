# Web Client

## Scope

The browser client provides tablet-friendly static planning, a Shared Marker client, browser-local Personal Ansiblex, configurable semantic label thresholds, and explicit Desktop Route Handoff loading. It does not add an account system, second backend, AI, MCP, localhost control, SDE management, server-side Personal Ansiblex, or offline Shared Marker editing.

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

browser localStorage
  Map label preferences + Personal Ansiblex + Keepstar Saved Marker state

browser IndexedDB
  Optional remembered Device Token + Server origin + device name + Workspace ID

shared-client commonMain Protocol v1 DTOs / JSON vocabulary
             |
             +-- Desktop Ktor CIO transport (JVM)
             `-- Browser Fetch transport (JS)
                         |
                         v
WebSharedMarkerController -> dynamic marker/Route Handoff state and DOM panels
```

`:core` is a focused Kotlin Multiplatform module with JVM and JS targets. `:shared-client` now also has JVM and JS targets: frozen wire DTOs and JSON configuration live in `commonMain`, while Desktop keeps its Ktor CIO transport, `java.time`/UUID domain mapping, DPAPI-backed credentials, and session code in `jvmMain`. Browser Fetch and browser state are implemented in `:web-client`. The Web client does not copy or redefine Protocol v1.

JVM-only records with `java.time` management metadata, Desktop synchronized caches, and Desktop repository interfaces remain in `jvmMain`. Map geometry, Official 2D projection, spatial index, route graph and planners, Capital route, jump eligibility/distance, and coverage operations are in `:core` `commonMain`. Desktop call sites convert their full Ansiblex/Wormhole records through `buildDesktopRouteGraph`; Web Pack Ansiblex records convert to the same platform-neutral `RouteLink` contract. There is one route implementation and one Capital implementation.

Kotlin/JS is configured to use the system Node installation and npm, so it does not add Node/Yarn download repositories to the dependency-resolution policy.

## Web Pack consumption

The Phase 1 loader remains the integrity boundary. It automatically loads `/data/manifest.json`, checks schema and manifest fields, downloads the versioned gzip file, verifies compressed size and SHA-256 with Web Crypto, decompresses with `DecompressionStream("gzip")`, validates document/manifest agreement, and returns the decoded document. Its status callback now distinguishes manifest fetch/parse, Pack fetch, checksum, gzip decode, JSON parse, validation, typed DTO conversion, domain conversion, scene/index construction, UI initialization, and Ready timing. Integrity and referential validation remain mandatory.

The UI never reads raw dynamic JSON. `parseWebPackDocument` creates typed Web DTOs and checks every consumed field. `WebUniverseDataAdapter` then builds Core domain values and browser in-memory repositories:

- Systems preserve universe XYZ, security, effective wormhole classification, and nullable Official 2D positions.
- Missing Official 2D positions remain `null`. They stay searchable and routable but are included in `ProjectedMapScene.omittedSystemIds` and are never placed at `(0, 0)`.
- Stargates become canonical `StargateConnection` values.
- Active Ansiblex become directional `RouteLink` values; `FIRST_TO_SECOND` and `SECOND_TO_FIRST` are not treated as bidirectional.
- Region and Constellation names/relationships come from the Pack. Their required domain center is calculated from member-system universe positions; it is not used as a replacement Official 2D system position.

## Renderer and input

`WebMapView` renders the Official 2D scene with browser Canvas. It draws culled Stargate edges, every enabled Pack and Personal Ansiblex, Jump Range/coverage halos, Normal route legs, Capital jump legs, nodes, selected/hovered states, Waypoints, and LOD-controlled labels. Shared presentation constants align the map semantics with Desktop: muted solid Stargates, amber curved/dashed base Ansiblex, cyan solid Normal Stargate legs, orange curved/dashed Normal Ansiblex legs, teal Wormhole legs, and purple solid Capital legs. Directional route arrows use the same spacing, count, minimum-length, size, and dark halo semantics. Route endpoints, Normal versus Capital Waypoints, selection, hover, coverage, and overlap remain visually distinct.

The drawing order is topology, coverage, routes, primary nodes, Shared Marker overlays, hierarchy labels, interaction highlights, then Waypoint badges. Route/Waypoint/selection therefore remain legible over coverage. A Keepstar Saved Marker changes the system's primary node shape instead of adding a second ordinary marker ring; selection, hover, route and Waypoint treatments remain above it.

The shared `MapTransform` supplies fit, pan, world/screen conversion, cursor-centered zoom, and visible bounds. The shared `SystemSpatialIndex` supplies viewport node queries and hit testing, avoiding a full-system scan for every pointer move. Edge rendering uses scene-bound intersection culling. Label density increases with zoom; selected, hovered, route, and Waypoint systems have priority.

Supported input:

- mouse wheel: zoom around cursor;
- mouse, pen, or single-finger drag: pan after a movement threshold;
- click or tap: select;
- two-finger pinch: pan and zoom around the moving gesture center;
- touch/pen long press or mouse right-click: open the existing system shortcut actions;
- mouse hover: hover highlight;
- **Fit Map**: restore Official 2D connected-map bounds.

The map alone uses `touch-action: none`, preventing browser-page zoom/scroll conflicts without disabling normal scrolling in drawers and dialogs. A second pointer cancels tap and long-press state; crossing the drag threshold cancels selection; long press cancels when movement crosses the touch threshold. Pointer capture preserves gestures that leave the node hit area. Touch picking uses a larger radius than mouse picking.

## Tablet layout and virtual keyboard

Desktop retains the three-column Tools / Map / System Info layout. At tablet width or with a coarse primary pointer, the map takes the full content area and Tools and System Info become mutually exclusive overlay drawers. Tools expose Search, Route, Capital, Coverage, Ansiblex, and Shared tabs so inactive forms do not permanently consume map width. In portrait, either drawer becomes a scrollable bottom sheet. The scrim, top-bar toggles, close buttons, and Escape key all dismiss panels.

Major tablet controls use at least 44 px targets. Icon-only controls have accessible names, keyboard focus remains visible, destructive marker deletion keeps explicit confirmation, and route/marker state is not expressed only by color. Shared Marker forms remain scrollable inside the current visual viewport. `visualViewport` resize/scroll updates a CSS viewport-height variable, and focused inputs are scrolled into view after the virtual keyboard changes the viewport.

The Canvas backing store is resized from its CSS bounds and current `devicePixelRatio`, capped at 3. A `ResizeObserver`, window resize, orientation change, and visual-viewport change all converge on the same resize path. Map state is preserved across ordinary resizes; Fit remains explicit.

Operation feedback uses the existing top banner as a transient notification. Informational and success messages remain visible for 3 seconds, errors for 6 seconds, and both finish with a 200 ms fade-out. A newer message replaces the current one and receives a fresh full lifetime; cancelled older timers cannot hide it. Persistent conditions such as Shared Marker connection state remain in their dedicated panel/status UI rather than occupying the banner.

## PWA, caching, and offline boundary

`manifest.webmanifest` provides standalone launch metadata and generated 192/512 px icons derived from the existing Desktop application icon. `service-worker.js` pre-caches a revisioned, internally consistent app shell and serves controlled navigation and only the enumerated shell assets cache-first; unrelated same-origin requests, including Shared Marker API traffic, pass through untouched. The worker's update check is what discovers the next shell revision, preventing a new `index.html` from being combined with stale JavaScript modules. The stable `/data/manifest.json` remains network-first, while immutable versioned Web Packs are cached by URL. After a successful integrity-checked load, the page asks the active service worker to warm the exact verified Pack and matching manifest for a later offline launch.

The stable manifest is never treated as immutable. On every online page load it is revalidated; if Desktop publishes a new versioned Pack first and the new manifest last, the next load discovers the new filename without a Web-side Update action. App-shell updates use a new shell cache revision, install a waiting service worker, and show **A new version is available — Reload**. Reload occurs only after the user chooses it, so marker-editor input is not discarded unexpectedly. Any future shell release must change `APP_CACHE` in `service-worker.js`; shell files are fetched with `cache: reload` during installation.

After one successful cached load, offline reopen supports the static map, Search, Normal/Ansiblex routes, Waypoints, Capital Route, Jump Range, Coverage, persisted label preferences, Keepstar Saved Markers, and persisted Personal Ansiblex. Shared Marker and discovery of new Route Handoffs stay online-only: the UI says Offline, write actions are disabled, and no mutation queue exists. A route already loaded into planner state remains usable. A first-ever offline launch with no usable cache shows a recoverable error and Retry action.

## Search and System Info

Search accepts system IDs and case-insensitive partial names. Exact and prefix matches rank before other substring matches. Selecting a result selects the system and centers it when Official 2D coordinates exist. An unpositioned result is still selected and produces an explicit location message.

System Info displays name, ID, Region, Constellation, security, effective wormhole class when available, Stargate count, active Ansiblex count, Jump Coverage count, universe XYZ, and Official 2D availability.

## Normal Route, Ansiblex, and Waypoints

The Web client builds one Core `RouteGraph` from Pack Stargates, enabled Pack Ansiblex, and enabled browser-local Personal Ansiblex. `NormalNavigationPlanner` calculates ordered segments over `NormalRouteEngine`:

- Ansiblex defaults off and is controlled by **Use active Ansiblex**.
- Directionality is enforced in the shared `RouteLinkEdgeBuilder`.
- Multiple Waypoints can be added, removed, and reordered.
- Segment results are composed into one route without duplicating boundary systems.
- An unreachable segment names its one-based segment and endpoint systems.
- Route summary and Canvas overlay distinguish Stargate and Ansiblex jumps.

Enabled Pack Ansiblex are the deployment owner's default network and are always visible as the base connection layer. The Ansiblex tab imports Desktop-compatible CSV or `format_version: 1` JSON through Select → Preview → Apply. It reports valid, invalid, and duplicate rows, then supports enable/disable, delete, and Clear Personal. Personal records are capped at 500 small records and stored atomically in `localStorage`, which is sufficient for the bounded dataset and keeps them usable after reload, PWA reopen, and offline reopen. They are never uploaded to the Server or written into Web Pack. One normalized unordered endpoint pair is allowed; Pack data wins deterministically over a Personal duplicate. The route graph is rebuilt only when Personal Ansiblex changes, not every frame.

## Capital Route

The effective jump range is entered in LY, matching the current Desktop manual-range workflow. `UniformGridSystemPositionIndex`, `CapitalJumpCandidateProvider`, and `CapitalRouteEngine` are shared with Desktop. The calculation therefore preserves the existing EVE LY constant, three-dimensional XYZ distance, high-security destination rule, New Eden/wormhole/Pochven/Jove/Abyssal classification, endpoint eligibility, and deterministic breadth-first result.

The result includes jump count, total LY, route systems, ordered Capital Waypoints, and a purple solid Capital overlay matching Desktop. Ship-specific presets are not invented because the current Core/Desktop profile exposed here is the user-effective manual range.

## Jump Range and Capital Coverage

**Add Range** calculates direct candidates from one chosen source with Coverage's own **Range (LY)** input and shared eligibility rules. Capital Route and Coverage keep separate UI values while sharing domain validation. Each action snapshots source system, effective range and overlay identity, so changing the input later never mutates an existing overlay. Multiple sources and mixed 4/5/6/10 LY ranges can coexist and can be removed individually or cleared together.

Capital Coverage follows the current Desktop meaning: the per-system count of enabled Jump Range overlays. A count greater than one is overlapping coverage. `JumpCoverageCalculator` is shared by JVM and JS, and the Canvas uses a stronger ring for overlap. The browser does not introduce a separate fleet or server-side meaning.

## Region, Constellation, and System labels

Semantic labels follow Desktop's absolute zoom direction and defaults. Region is the primary zoomed-out layer; Constellation begins at `2.0`; System begins at `6.0`. The **Map Display / Labels** settings accept custom positive finite thresholds only when Constellation is lower than System and System is at most 250. A return ratio of `0.83` adds hysteresis around threshold crossings. Save persists the pair to browser `localStorage`; **Reset to Defaults** removes the override. Focused fields are not overwritten by redraws while the user types.

## Keepstar Saved Markers

The stable Saved Marker child type is `keepstar`; marker names and Shared Marker text/tags are not used for classification. A Keepstar replaces the ordinary system node on both Desktop and Web. Search, identity, hit testing, routing, Capital, coverage, selected/hovered state, and Waypoints continue to target the underlying Solar System. Removing the Saved Marker restores the normal node immediately.

## Shared Marker connection and permissions

Open **Shared Markers**, enter the Shared Map Server origin (for example `https://marker.example.com`), paste an existing single-use Invite Code, choose a device name, choose whether to **Remember this device** (enabled by default), and select **Connect / Join**. The browser calls the existing invite-exchange endpoint, validates Protocol v1 and the Shared Markers feature, loads `/me`, the assigned Workspace, and its authoritative marker snapshot. The Invite Code field is cleared after the attempt. **Disconnect** stops polling and removes the browser credential and in-memory Shared Marker snapshot without changing routes, Waypoints, coverage, local data, or revoking the server-side device.

When Remember is enabled, the browser stores only the Server origin, issued 90-day Device Access Token, device name, and expected Workspace ID in a dedicated IndexedDB record. Reload or PWA reopen restores that record, then validates the credential through `/me` and the actual `/workspaces` result before entering Connected state; it never trusts a locally cached role or Workspace snapshot. Invalid, expired, revoked, forbidden, corrupt, or Workspace-mismatched records are removed. Transient offline/network failure retains the record so a later retry can recover. When Remember is disabled, the token is session-only and reload requires a new Server-issued invite.

The Invite Code and Device Access Token are never put in Web Pack, URLs, `localStorage`, console output, diagnostics, or visible state. IndexedDB is origin-local browser storage, not OS-backed secret storage; users of a shared or untrusted browser should disable Remember or Disconnect after use. Desktop continues to protect its token with Windows DPAPI and is unchanged.

`VIEWER` can load and locate markers. `EDITOR` and `ADMIN` can create a marker for the selected system and edit or delete existing markers. The UI reflects the resolved server role, but every request is still authorized by the server. Create/edit supports only the existing fields: name, one of the frozen colors, tags, and notes. Delete requires confirmation and affects only the selected Shared Marker.

Shared Markers are online dynamic state and remain completely outside Web Pack. Snapshot updates replace only the marker map; they do not rebuild the universe, projected static scene, route graph, or Web Pack data. Markers render as colored outer rings with a small badge, remain distinct from node selection, Waypoints, Normal/Ansiblex/Capital routes, Jump Range, and coverage, and receive a stronger selected treatment. A marker whose system has no Official 2D point remains in the list with an explicit location message and is never placed at `(0, 0)`. An unknown future system ID is also retained in the list as an incompatible Web Pack reference.

## Desktop Route Handoff

Route Handoff is the optional Protocol v1 feature `route-handoffs`; it is separate from Shared Markers and Web Pack. Desktop publishes either the current Normal route or current Capital route through the already-connected Workspace. Each record contains editable intent plus the exact resolved snapshot and map metadata. `VIEWER` may read; `EDITOR` and `ADMIN` may publish. A publisher may delete their own handoff; `ADMIN` may delete any handoff; `VIEWER` and a non-owning `EDITOR` cannot delete it. An older Server without the feature remains compatible and produces an explicit unsupported state instead of a failing request.

Web checks recent handoffs on the existing refresh/poll lifecycle and displays publisher, type, endpoints, Waypoint count, and timestamp. It never automatically overwrites current work. **Load** restores the matching controls and renders the published snapshot. An authorized row also has a small delete control; successful deletion removes it immediately, while failure leaves the row and shows a transient error. Matching SDE metadata is acknowledged; a mismatch keeps and displays the snapshot with `This route was published from a different map data version.` rather than silently recomputing a different route. New handoffs and deletes are unavailable offline, and there is no offline mutation queue.

## Refresh, reconnect, and page lifecycle

The server exposes polling rather than WebSocket or SSE, so Web deliberately follows Desktop's 30-second refresh contract. The same authenticated refresh loads the complete marker snapshot and, only when advertised, the recent bounded Route Handoff list. Successful Web marker mutations reconcile immediately from the server response; another client's changes appear after the next poll (normally within 30 seconds). Optimistic update/delete sends the current marker version. A `409` conflict adopts the server's current marker when supplied and reports the conflict instead of overwriting it.

Network loss leaves the last in-memory snapshot visible and marks the connection **Reconnecting**. Retries use 5, 10, 20, then 30-second delays and stay capped at 30 seconds. Restoring a hidden tab triggers an immediate refresh; browser timer throttling may lengthen background-tab intervals. A `401` changes to **Auth failed** and clears both memory and any remembered credential, requiring a new invite. A membership-revocation `403` changes to **Forbidden**, clears inaccessible state, and removes the remembered credential. Page close cancels polling and clears process memory but keeps a valid remembered credential for the next authenticated restore; there is no background sync or offline mutation queue.

## Browser origin, CORS, and HTTPS

The Shared Map Server must configure the exact Web application origin in `SHARED_MAP_ALLOWED_ORIGINS`, for example:

```text
SHARED_MAP_ALLOWED_ORIGINS=https://map.example.com
```

Multiple origins are comma-separated. The server does not accept `*`; credentials/cookies are disabled; only the existing REST methods and required headers (`Authorization`, `Content-Type`, `X-Request-Id`, and `Idempotency-Key`) are allowed. `OPTIONS` preflight is handled by the server. Requests without an `Origin` header continue to work for Desktop. Local development may use `http://localhost:<port>` or `http://127.0.0.1:<port>`; non-loopback origins must be HTTPS.

Production must serve both Web and Shared Map Server over HTTPS. An HTTPS page cannot connect to a plain-HTTP remote server because browsers block mixed content, and the Web client does not offer an unsafe bypass. Protocol v1 currently has no WebSocket/SSE transport. See [web-deployment.md](web-deployment.md) for deployable files, required headers, Caddy/nginx examples, safe Pack rollout, PWA update behavior, and rollback.

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
|-- manifest.webmanifest
|-- pwa-runtime.mjs
|-- service-worker.js
|-- icons/
|   |-- app-icon-192.png
|   `-- app-icon-512.png
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

The common/JVM/JS suites cover Protocol v1 DTO round trips, Route Handoff transport/deletion and feature gating, exact REST paths/headers/bodies, remembered IndexedDB device-session restore/clear behavior, marker behavior, permissions, malformed responses, Personal Ansiblex import/persistence/routing, Coverage range independence, LOD threshold persistence, shared visual semantics, Keepstar replacement, route snapshot loading, map-version mismatch handling, projection, culling, and picking. An opt-in integration test uses the real Desktop Ktor client, a real containerized Server/PostgreSQL, and the exact common DTO consumed by Web. Loader tests cover integrity and stage timing; PWA lifecycle tests cover explicit user-applied service-worker updates and verified-Pack cache warming.

`qa/web-client-browser-smoke.mjs` is a dependency-free Chrome DevTools Protocol smoke client for a local headless Chromium browser. With the real Pack it verifies readiness, Canvas creation, Shared Marker disconnected controls, Search/System Info, a simple route, a long route, Waypoint composition, an Ansiblex route, Capital Route, multiple Jump Range/Coverage overlays, Fit, and wheel zoom. It reports loader, domain/scene, UI, route, and render observations without presenting the CDP harness wall clock as a formal benchmark.

`qa/web-client-tablet-smoke.mjs` applies 1280×800 landscape and 800×1280 portrait mobile metrics and checks compact one-row folded tool sections, independent nested Map LOD and Desktop Routes sections, drawers/tabs, touch targets, single-finger pan, tap suppression after drag, two-finger focal zoom, long press, context actions, DPR backing size, manifest/icons, production cache headers, offline static reopen, and the explicit offline Shared Marker state. CDP may keep `navigator.onLine` true while its network is disabled; the script therefore treats successful offline reload as the network assertion and separately dispatches the standard browser offline event for UI lifecycle coverage.

`qa/web-pack-update-smoke.mjs` operates on a disposable copy of the real production directory. It derives a checksum-valid second version from Pack A, writes the versioned Pack B first and `manifest.json` last, reloads without any Web Update action, and asserts that a service-worker-controlled page reaches Ready with Pack B.

`qa/web-app-update-smoke.mjs` operates on another disposable production copy. It installs the current shell, publishes a next worker/cache revision with a changed runtime, verifies that an ordinary reload remains on the complete old shell, then accepts the visible update and verifies that the new shell becomes active. This guards against mixing a new HTML entry point with stale modules.

For a manual local browser smoke, serve the production directory with `node qa/static-web-server.mjs`, start Chrome
or Edge headless with a local remote-debugging port, and run:

```powershell
node qa/web-client-browser-smoke.mjs <port> http://127.0.0.1:8765/
node qa/web-client-tablet-smoke.mjs <port> http://127.0.0.1:8765/
```

The helper serves only local static artifacts and is not a production server.

## Samsung tablet manual acceptance

Automated Chromium mobile/touch emulation is required before this checklist, but it is not a physical Samsung result. On the intended Samsung model and current Android Chrome, record model, Android version, Chrome version, orientation, whether the browser or installed PWA was used, and the result of each item:

1. Open the HTTPS site and install it with **Add to Home Screen / Install App**.
2. Launch standalone and confirm the current Pack reaches Ready.
3. Confirm the landscape map keeps the primary area and drawers overlay rather than shrink it.
4. Rotate to portrait and confirm Tools/System Info become usable scrollable bottom sheets.
5. Pan with one finger; releasing must not select a system.
6. Tap a system; it must select once.
7. Pinch in and out; the point between the fingers should stay visually stable.
8. Move both fingers during pinch and confirm combined pan remains stable.
9. Long press a system, open its action sheet, and dismiss it without a phantom tap.
10. Use an S Pen for tap and drag; if available, also verify Bluetooth mouse hover, drag, right-click, and wheel.
11. Open Search with the virtual keyboard, select a result, dismiss the keyboard, and confirm the map viewport recovers.
12. Enter Normal Route endpoints and calculate a route.
13. Toggle active Ansiblex and verify the route legend/summary distinguishes it.
14. Add, reorder, and remove Waypoints using touch targets.
15. Calculate a Capital Route and inspect its summary/overlay.
16. Add one Jump Range and multiple Coverage origins, then remove one and Clear All.
17. Open and close System Info while retaining map state.
18. Connect Shared Marker; distinguish Connected, Reconnecting, Disconnected, and Auth failed states.
19. Locate a marker from the list and tap its map overlay.
20. Create and edit a marker with the keyboard open; confirm the form scrolls and Save/Cancel stay reachable.
21. Delete a marker and verify the destructive confirmation is clear and difficult to hit accidentally.
22. Disable networking and reopen the installed PWA; verify cached static planning and explicit Shared Marker Offline state.
23. Restore networking and verify Shared Marker can reconnect; no offline mutation should appear.
24. Publish a new versioned Pack and manifest, then reopen without a Web Update action and verify the new Pack version.
25. Deploy a new app shell, verify the update notice, choose Reload when no form data is at risk, use Fit Map, and inspect high-DPI sharpness.

## Known limitations and phase boundaries

- Official 2D is the only Web projection; Desktop Real 3D remains Desktop-only.
- 3,005 systems in the current tested SDE Pack have no Official 2D coordinates. They remain searchable/routable and are reported as unpositioned.
- Capital route uses the current effective manual LY profile; no unverified ship/rule presets were added.
- The renderer remains Canvas 2D with indexed culling and event-coalesced redraws; it does not claim WebGL or a formal cross-device benchmark.
- Offline support is intentionally limited to the cached app shell, current published Web Pack, and bounded browser-local preferences/Personal Ansiblex/Keepstar state. Shared Marker and new Route Handoffs are unavailable offline and have no mutation queue.
- Remembered browser Device Access Tokens use origin-local IndexedDB rather than OS-backed secret storage. Shared or untrusted browser users should disable Remember or Disconnect after use.
- Shared Marker and Route Handoff discovery use the server's existing 30-second polling, not push delivery. Background tab throttling can increase observed latency.
- Phase 3 does not expose Shared Marker member, invite, role, or device administration in Web. Those existing administrative workflows remain Desktop/server responsibilities.
- Phone layout is best-effort; the product target remains tablet landscape, Desktop browser, then tablet portrait.
- Samsung device acceptance still requires a physical device; CDP touch/mobile emulation is not a substitute for that final manual check.
