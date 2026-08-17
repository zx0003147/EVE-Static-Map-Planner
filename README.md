# EVE Static Map Planner

A small Kotlin/JVM desktop application for offline EVE Online static-map and route-planning workflows.

The project is currently in **Phase 1**. It contains only a compilable four-module skeleton and a minimal Compose Desktop window; no map, route, SDE, database, Ansiblex, jump-range, capital-route, or ESI functionality is implemented yet.

## Modules

- `app`: Compose Desktop application entry point and future UI.
- `core`: Pure Kotlin domain and algorithm module. It does not depend on Compose or SQLite.
- `data`: Future SQLite, DAO, and repository implementations. It is intentionally empty in Phase 1.
- `sde`: Future SDE download, parsing, build, and validation pipeline. It is intentionally empty in Phase 1.

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
