# EVE Static Map Planner

**EVE Static Map Planner is an unofficial Windows desktop map and route-planning companion for EVE Online. It lets you explore the EVE universe, calculate Stargate, Ansiblex and capital routes, visualize jump ranges, manage Planning Views and markers, and create temporary Wormhole connections directly on the map.**

**The core map works independently from local static universe data and does not require AI, ESI, or any Feature Pack. Optional integrations add AI map control through the EVE Map Assistant plugin and account-aware or live-data features through external ESI and Sovereignty Feature Packs.**

Version 1.2.0 adds the optional Shared Map client and Shared Markers: Workspace-based collaboration, role-aware
management, invite-to-device-token connection, multi-client polling, conflict protection, and clear degraded/stale
snapshot behavior. The map remains local-first and all existing features work without a Shared Map Server.

Core remains offline-capable. Optional ESI functionality is supplied only by the external ESI Pack; V1 continues to
exclude intel, killboards, application auto-update, signing, Microsoft Store/MSIX, and non-Windows distributions.

## Modules

- `app`: Compose Desktop UI, startup coordination, diagnostics, and Windows native distribution configuration.
- `control`: Application control operations shared by UI and automation callers.
- `control-transport`: Transport-neutral control request and response contracts.
- `core`: Pure Kotlin static-universe domain models and repository contracts. It does not depend on Compose, JSONL, or SQLite.
- `data`: SQLite schemas, repositories, strict Ansiblex CSV/JSON import, Preview/Diff, and transactional Apply.
- `feature-api`: Frozen Feature API v2 contracts and generic external Pack test fixture.
- `mcp`: Core-owned MCP server and its fixed 30-tool catalog, including View control, session Wormhole read/create, and permission-gated Saved Marker read/create.
- `sde`: Streaming JSONL parsing, validation, importer, managed download/update pipeline, and verification CLI.
- `shared-client`: Compose-independent Shared Map protocol, HTTPS client, Workspace session, polling, and secure credential contracts.

## External Feature Packs

Sovereignty and ESI are first-party external Feature Packs maintained in separate repositories. Core does not contain
or bundle either production implementation.

- Core owns the frozen Feature API v2 contract and the Feature Pack Host/runtime.
- On Windows, Packs are installed below
  `%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\<pack-id>\pack.jar`.
- Core validates manifest compatibility before creating a Pack ClassLoader.
- A normal Core build and no-Pack application startup require no Sovereignty checkout or artifact.

Install the optional Pack JARs at these exact paths, then enable them under Preferences → Feature Packs:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\esi.pack\pack.jar
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

Without any Pack, the Core map, routing, databases, and updater continue to work normally. The ESI Pack adds EVE SSO,
multi-character location portraits, `Set EVE Destination`, and bounded explicit `Send Draft to EVE` actions. Each
View stores one selected route-action target; both ESI actions share that selection and never auto-fallback after a
disconnect. Map and route edits remain local until one of those actions is clicked. Add Character, Refresh Locations,
and per-character Disconnect controls appear generically in the Feature Packs preferences page.

See `docs/feature-packs.md` for the platform contract, installation layout, lifecycle, and testing model.

## Development requirements

- JDK 25

## Build and test on Windows

```powershell
.\gradlew.bat build
```

## Run on Windows during development

Supply an existing database explicitly during development:

```powershell
.\gradlew.bat :app:run --args="--database C:\path\to\static.db"
```

For manual acceptance, an exact system name and isolated user database can be supplied:

```powershell
.\gradlew.bat :app:run --args="--database C:\path\to\static.db --user-database C:\path\to\user.db --focus-system Jita"
```

Database resolution order is `--database`, the `eve.static.database` JVM property, `EVE_STATIC_DB`, then the platform application-data path. Without an explicit override, a missing managed database opens Static Data Setup so the application can download and build the latest official SDE.

The separate user database resolves through `--user-database`, `eve.user.database`, `EVE_USER_DB`, then `%LOCALAPPDATA%\EVE Static Map Planner\data\user.db` on Windows. A missing `user.db` is created with schema version 4. Planning Views, route drafts, AI Missions, Wormhole topology, and per-View Route Action targets are session-only and are deliberately absent from this database. An existing damaged or newer database is never deleted or silently rebuilt; the application keeps the static map and Stargate-only routing available while disabling user-data features.

## Temporary Wormholes

Wormholes are bidirectional connections shared by every Planning View for the current application session. Create or
remove them through the global Manager or a system's right-click menu; exiting the app clears the network. Each View
independently controls whether its Normal Route planner may use Wormholes. Removing or clearing a connection removes
affected current and Mission Normal Routes without recalculating them, while unrelated routes, Missions, markers,
jump ranges, Capital Routes, and Jump Range calculations remain unchanged.

AI/MCP can list and create Wormholes, but cannot update, remove, delete, clear, replace, import, export, or persist
them. Feature API v2 remains frozen: Route Actions are unavailable for user routes containing Wormholes because the
API has no Wormhole route-segment kind.

## Shared Map and Shared Markers

Shared Map is optional collaboration support. Without a server connection, EVE Static Map Planner keeps its complete
local map, routing, local Saved Markers, AI Missions, Wormholes, Feature Packs, and static-data workflow. Connecting
does not move those local domains to the server.

A Shared Map Server hosts Workspaces with `ADMIN`, `EDITOR`, and `VIEWER` roles. Users join with a short-lived invite;
the client exchanges it for a Workspace-scoped Device Token and protects that token with Windows DPAPI. Remote
servers must use HTTPS; plain HTTP is accepted only for `localhost` and `127.0.0.1` development endpoints.

The Shared Marker Manager provides role-aware create, edit, delete, member, role, invite, and device administration.
Connected clients poll every 30 seconds and atomically replace the selected Workspace snapshot. If a refresh fails,
the last in-memory snapshot remains visible as stale in degraded mode; it is never persisted as a shared snapshot
disk cache. Disconnecting clears Shared Marker state without affecting local markers or AI Missions.

Local Saved Markers, AI Mission Markers, and Shared Markers remain three independent domains with distinct map
visuals. AI/MCP can neither read nor create, edit, or delete Shared Markers in 1.2.0.

## Windows x64 distribution

The official Windows distribution is the installer-free Portable ZIP:

```text
EVE-Static-Map-Planner-1.2.0-Windows-x64.zip
```

Download and extract the complete ZIP, then open:

```text
EVE Static Map Planner\EVE Static Map Planner.exe
```

Do not move or copy only the EXE; the adjacent `app` and `runtime` directories are required. No Java installation,
MSI, Windows Installer, Start Menu entry, desktop shortcut, or uninstall entry is required or created.

To build and audit the same ZIP from source:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:verifyPortableZip
```

The final artifact, checksum, audit, and release manifest are written under `build\release`. Packaging reuses the
verified Compose/jpackage application image and includes its own Java runtime; WiX is not required.

This is an installer-free application distribution, not a mode that stores user data beside the EXE. Mutable data
always remains under:

```text
%LOCALAPPDATA%\EVE Static Map Planner
```

That includes managed `static.db`, `user.db`, settings, logs, DPAPI-protected Shared Map and ESI credentials, and
external Feature Packs.
Moving, copying, replacing, or deleting the extracted program directory does not move or delete this data.

To update, close the app and any MCP bridge processes, extract the new ZIP to a new directory, run it, and remove the
old program directory after validation. Avoid overwriting files in a running program directory. To remove the app,
delete the extracted directory. To remove all user data too, first Disconnect the ESI Pack if desired, then manually
delete the LocalAppData directory.

## AI / MCP integration

The recommended generic integration is Streamable HTTP at `http://127.0.0.1:27892/mcp`. The server runs inside the
Map JVM, binds only IPv4 loopback, and exposes the same fixed 30 tools as the existing bridge. The EVE Map Assistant
Codex Plugin prefers this HTTP endpoint, so moving the Portable directory does not change Plugin configuration: stop
the Map, move the directory, restart the Map, and open a new Codex task.

Current HTTP compatibility is Kotlin MCP SDK 0.14.0 / 2025-series Streamable HTTP, not full MCP 2026-07-28. That
protocol migration is deferred until it is supported by an official Kotlin SDK release.

STDIO remains supported. The Portable image includes `EVE Map MCP Bridge.exe` and `eve-map-mcp.exe`, and the packaged
Map maintains `%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json` for locator-aware clients. Ordinary users
do not need to edit this file.

Legacy/manual STDIO registrations still store an absolute executable path. Those registrations must be updated when
the extracted directory moves; the map does not modify Codex, DSH, Claude, or any other AI-client configuration.
Plugin developers should use the authoritative contract in `docs/mcp-discovery.md`.

See `docs/windows-distribution.md` for the Portable layout, data boundary, build process, MCP path behavior, and
acceptance checklist.

### Public distribution notices

The public-distribution branding review retains the descriptive `EVE Static Map Planner` name, original abstract
node-and-route icon, unofficial/not-endorsed wording, and CCP proprietary notice in `NOTICE.md`. The icon does not use
CCP/EVE, RIFT, or SMT artwork. Distributors remain responsible for accepting and complying with the then-current CCP
Developer License Agreement.

## Manual Ansiblex import

CSV accepts only these columns (unknown columns are errors):

```csv
from_system_id,from_system_name,to_system_id,to_system_name,connection_name,note,enabled,direction
30004759,1DQ1-A,30004712,NOL-M9,Example,User-maintained,true,BIDIRECTIONAL
```

Each endpoint needs an ID, an exact case-insensitive name, or both matching values. `direction` is `BIDIRECTIONAL` (default) or `FORWARD`. JSON uses `format_version: 1` and the equivalent nested `from`/`to` endpoint objects.

The database stores one normalized **logical route connection** per unordered system pair. This is a V1 routing-data constraint; it is not a claim about how many physical EVE structures can exist for that pair. Future data sources may preserve multiple physical structures while folding them into one RouteGraph connection.

Import always follows Parse → Validate → Preview → user confirmation → transactional Apply. `REPLACE` replaces only `source=IMPORT`; it never deletes or overwrites `source=MANUAL`.

Files under `qa/` are explicitly synthetic acceptance fixtures and are not a real alliance Jump Bridge network.

## Import an extracted official JSONL SDE

Download and extract an official JSON Lines SDE separately, then supply its actual build number explicitly:

```powershell
.\gradlew.bat :sde:run --args="import --input C:\path\to\extracted-sde --output C:\path\to\static.db --build 1234567"
```

The importer requires exactly one copy of each of these files beneath the input directory:

- `mapRegions.jsonl`
- `mapConstellations.jsonl`
- `mapSolarSystems.jsonl`
- `mapStargates.jsonl`

It never overwrites an existing output database.

## Query and verify

```powershell
.\gradlew.bat :sde:run --args="query --database C:\path\to\static.db --system-name Jita"
.\gradlew.bat :sde:run --args="verify --database C:\path\to\static.db --systems 30000142,30004759,30004735"
```

The verification command reports the imported build, table counts, SQLite integrity/foreign-key checks, coordinates, security, hierarchy, and stargate destinations for the requested systems.
