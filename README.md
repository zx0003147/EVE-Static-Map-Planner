# EVE Static Map Planner

A small Kotlin/JVM desktop application for offline EVE Online static-map and route-planning workflows.

The project has completed the static map, normal routing, manual Ansiblex, jump-range overlay, capital-routing, and managed SDE-update phases. Phase 8 adds a self-contained Windows x64 application image and an unsigned per-user MSI installer.

V1 intentionally excludes ESI, intel, killboards, application auto-update, signing, Microsoft Store/MSIX, and non-Windows distributions.

## Modules

- `app`: Compose Desktop UI, startup coordination, diagnostics, and Windows native distribution configuration.
- `control`: Application control operations shared by UI and automation callers.
- `control-transport`: Transport-neutral control request and response contracts.
- `core`: Pure Kotlin static-universe domain models and repository contracts. It does not depend on Compose, JSONL, or SQLite.
- `data`: SQLite schemas, repositories, strict Ansiblex CSV/JSON import, Preview/Diff, and transactional Apply.
- `feature-api`: Frozen Feature API v1 contracts and generic external Pack test fixture.
- `mcp`: Core-owned MCP server and its 20-tool catalog.
- `sde`: Streaming JSONL parsing, validation, importer, managed download/update pipeline, and verification CLI.

## External Feature Packs

Sovereignty is a first-party external Feature Pack maintained in the external Sovereignty Pack repository
(`EVE-Sovereignty-Pack`). Core does not contain or bundle its production implementation.

- Core owns the frozen Feature API v1 contract and the Feature Pack Host/runtime.
- On Windows, Packs are installed below
  `%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\<pack-id>\pack.jar`.
- Core validates manifest compatibility before creating a Pack ClassLoader.
- A normal Core build and no-Pack application startup require no Sovereignty checkout or artifact.

See `docs/feature-packs.md` for the platform contract, installation layout, lifecycle, and testing model.

## Requirements

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

The self-contained application image includes its own Java runtime:

```powershell
.\gradlew.bat :app:createDistributable
.\gradlew.bat :app:runDistributable
```

Building the unsigned MSI additionally requires WiX Toolset 4.0.6 on the build machine:

```powershell
.\gradlew.bat :app:packageMsi
```

Generated native-distribution files remain under `app/build/compose/binaries`. The installer never packages or replaces `static.db`, `user.db`, or update state under `%LOCALAPPDATA%\EVE Static Map Planner` during installation or upgrade. Uninstall intentionally removes the application together with that local data root, including managed static data, user/Ansiblex data, updater state, and logs.

See `docs/windows-distribution.md` for the stable installer identity, data boundary, build environment, and acceptance checklist.

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
