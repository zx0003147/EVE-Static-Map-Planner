# EVE Static Map Planner

A small Kotlin/JVM desktop application for offline EVE Online static-map and route-planning workflows.

The project is currently in **Phase 4**. It imports the four static-universe JSONL files from an already downloaded official EVE SDE, renders the static universe, plans minimum-jump Stargate routes, and optionally includes user-maintained Ansiblex connections. Manual Ansiblex data supports CSV/JSON Preview and transactional MERGE/REPLACE Apply, enable/disable, manual add, delete, and guarded clear operations.

Capital routes, jump ranges, SDE downloading, ESI, security-weighted routes, and dynamic Thera/Turnur/Zarzakh connections are not implemented.

## Modules

- `app`: Compose Desktop application entry point and future UI.
- `core`: Pure Kotlin static-universe domain models and repository contracts. It does not depend on Compose, JSONL, or SQLite.
- `data`: SQLite schemas, repositories, strict Ansiblex CSV/JSON import, Preview/Diff, and transactional Apply.
- `sde`: Streaming JSONL parsing, cross-file reference validation, importer, and verification CLI. It does not download the SDE.

## Requirements

- JDK 25

## Build and test on Windows

```powershell
.\gradlew.bat build
```

## Run on Windows

Supply an existing Phase 2 database explicitly during development:

```powershell
.\gradlew.bat :app:run --args="--database C:\path\to\static.db"
```

For manual acceptance, an exact system name and isolated user database can be supplied:

```powershell
.\gradlew.bat :app:run --args="--database C:\path\to\static.db --user-database C:\path\to\user.db --focus-system Jita"
```

Database resolution order is `--database`, the `eve.static.database` JVM property, `EVE_STATIC_DB`, then the platform application-data path. A missing database is reported and is never created automatically.

The separate user database resolves through `--user-database`, `eve.user.database`, `EVE_USER_DB`, then `%LOCALAPPDATA%\EVE Static Map Planner\data\user.db` on Windows. A missing `user.db` is created with schema version 1. An existing damaged or newer database is never deleted or silently rebuilt; the application keeps the static map and Stargate-only routing available while disabling Ansiblex.

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
