# EVE Static Map Planner

A small Kotlin/JVM desktop application for offline EVE Online static-map and route-planning workflows.

Version 0.6.0 completes the static map, normal routing, manual Ansiblex, jump-range overlay, capital-routing,
managed SDE updates, external Feature Packs, and the self-contained Windows x64 Portable ZIP release.

Core remains offline-capable. Optional ESI functionality is supplied only by the external ESI Pack; V1 continues to
exclude intel, killboards, application auto-update, signing, Microsoft Store/MSIX, and non-Windows distributions.

## Modules

- `app`: Compose Desktop UI, startup coordination, diagnostics, and Windows native distribution configuration.
- `control`: Application control operations shared by UI and automation callers.
- `control-transport`: Transport-neutral control request and response contracts.
- `core`: Pure Kotlin static-universe domain models and repository contracts. It does not depend on Compose, JSONL, or SQLite.
- `data`: SQLite schemas, repositories, strict Ansiblex CSV/JSON import, Preview/Diff, and transactional Apply.
- `feature-api`: Frozen Feature API v2 contracts and generic external Pack test fixture.
- `mcp`: Core-owned MCP server and its fixed 22-tool catalog, including permission-gated Saved Marker read/create.
- `sde`: Streaming JSONL parsing, validation, importer, managed download/update pipeline, and verification CLI.

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
character location, `Set EVE Destination`, and bounded explicit `Send Draft to EVE` actions. Map and route edits
remain local until one of those actions is clicked. Its Connect, Refresh Location, and
Disconnect controls appear generically in the Feature Packs preferences page.

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

The separate user database resolves through `--user-database`, `eve.user.database`, `EVE_USER_DB`, then `%LOCALAPPDATA%\EVE Static Map Planner\data\user.db` on Windows. A missing `user.db` is created with schema version 4. An existing damaged or newer database is never deleted or silently rebuilt; the application keeps the static map and Stargate-only routing available while disabling Ansiblex and saved markers.

## Windows x64 distribution

The official Windows distribution is the installer-free Portable ZIP:

```text
EVE-Static-Map-Planner-0.6.0-Windows-x64.zip
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

That includes managed `static.db`, `user.db`, settings, logs, ESI credential storage, and external Feature Packs.
Moving, copying, replacing, or deleting the extracted program directory does not move or delete this data.

To update, close the app and any MCP bridge processes, extract the new ZIP to a new directory, run it, and remove the
old program directory after validation. Avoid overwriting files in a running program directory. To remove the app,
delete the extracted directory. To remove all user data too, first Disconnect the ESI Pack if desired, then manually
delete the LocalAppData directory.

## AI / MCP integration

The recommended generic integration is Streamable HTTP at `http://127.0.0.1:27892/mcp`. The server runs inside the
Map JVM, binds only IPv4 loopback, and exposes the same fixed 22 tools as the existing bridge. The EVE Map Assistant
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

### Public distribution branding review

`EVE Static Map Planner` is approved only as the current local Phase 8 QA name. Before any public release, review the product name, icon, description, CCP proprietary notice, and unofficial/not-endorsed wording against the then-current CCP developer agreement and trademark requirements. The placeholder icon is an original abstract node-and-route mark and does not use CCP/EVE, RIFT, or SMT artwork.

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
