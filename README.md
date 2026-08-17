# EVE Static Map Planner

A small Kotlin/JVM desktop application for offline EVE Online static-map and route-planning workflows.

The project is currently in **Phase 2**. It imports the four static-universe JSONL files from an already downloaded official EVE SDE into a validated SQLite database and exposes repository and command-line queries. Map rendering, routes, Ansiblex, jump ranges, capital routing, SDE downloading, and ESI are not implemented yet.

## Modules

- `app`: Compose Desktop application entry point and future UI.
- `core`: Pure Kotlin static-universe domain models and repository contracts. It does not depend on Compose, JSONL, or SQLite.
- `data`: SQLite schema, validated database builder, and repository implementations.
- `sde`: Streaming JSONL parsing, cross-file reference validation, importer, and verification CLI. It does not download the SDE.

## Requirements

- JDK 25

## Build and test on Windows

```powershell
.\gradlew.bat build
```

## Run on Windows

```powershell
.\gradlew.bat :app:run
```

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
