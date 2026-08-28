# AI Map MCP launcher

The `mcp` module is a local STDIO-only bridge between Codex and a running EVE Static Map Planner instance:

```text
Codex -> Plugin bundled MCP -> eve-map-mcp.exe
      -> secure per-session discovery -> authenticated 127.0.0.1 HTTP
      -> Map Control API -> temporary Mission state
```

The bridge does not start the desktop app, enable AI Control, accept a port or secret, or expose shell, file, SQL, generic HTTP, preferences, saved-marker, or Ansiblex mutation capabilities.

## Build the Windows launcher

Run from the repository root on Windows with JDK 25:

```powershell
.\gradlew.bat :mcp:createLauncher
```

The development app-image is generated below the ignored `mcp/build/launcher` directory. Its direct STDIO command is:

```text
mcp/build/launcher/EVE Map MCP Bridge/EVE Map MCP Bridge.exe
```

The development image contains a bundled Java runtime. Starting the `.exe` does not require Gradle, a system JDK, a shell wrapper, or command-line arguments. It is a console launcher so stdin/stdout pipes remain available to the MCP client.

## Windows 0.2.1 distribution

The Windows MSI installs the bridge as a second native jpackage launcher in the same product image as the desktop app:

```text
<actual install root>\
├─ EVE Static Map Planner.exe
├─ EVE Map MCP Bridge.exe       # 0.2.0 compatibility entry
├─ eve-map-mcp.exe              # stable Plugin command
├─ app\
└─ runtime\
```

All three launchers use the one installed jlink runtime. The GUI launcher keeps its Compose classpath and Windows GUI subsystem. Both MCP launchers use the console subsystem, start `dev.evestaticmapplanner.mcp.MainKt`, and have identical production-only classpaths under `app\mcp`. Neither MCP launcher receives a Start Menu or desktop shortcut or creates another Installed Apps entry.

The per-user MSI appends the product install directory to the user's `PATH` through the Windows Installer `Environment` table. Install and repair are idempotent, and uninstall removes only the product-owned entry. A newly started Codex process can therefore resolve `eve-map-mcp.exe` without an absolute path or shell. Already-running processes retain their inherited environment and must be restarted after install or upgrade.

The MSI contains no control discovery descriptor, session key, lock, database, preferences, or other user state. `active-instance.json`, `session.key`, and `active.lock` are created only beneath `%LOCALAPPDATA%\EVE Static Map Planner\control` after the running app enables AI Control. The bridge remains STDIO-only and its installed classpath excludes Ktor HTTP/SSE/WebSocket server runtimes.

## Development-only registration

An unpackaged developer may temporarily register the standalone build with the official Codex CLI:

```powershell
codex mcp add eve-static-map -- "C:\absolute\path\to\EVE Map MCP Bridge.exe"
codex mcp get eve-static-map
```

This is a development registration tied to the current build path. It contains no session key, bearer token, port, discovery descriptor, environment override, or shell command. Restart the Codex client or open a new task after registration.

To remove only this development registration:

```powershell
codex mcp remove eve-static-map
```

## Migrate from the 0.2.0 Gate B registration

After upgrading the map to 0.2.1 and the EVE Map Assistant Plugin to 0.2.0, remove only the legacy global registration once:

```powershell
codex mcp remove eve-static-map
```

Do not add it again: Plugin 0.3.0 bundles `eve-static-map` with command `eve-map-mcp.exe`. Fully restart Codex so it inherits the updated PATH and loads the Plugin MCP. With the map closed, initialization and `tools/list` should still succeed while a map tool returns `APP_DISCONNECTED`. With the map running and AI Control enabled, perform a light end-to-end check such as search, focus, begin mission, show route, add a temporary marker, query markers, and clear the mission.

## Fixed tool surface

The server exposes exactly these 22 tools:

```text
search_system
get_system_info
get_system_markers
calculate_normal_route
calculate_capital_route
get_active_missions
get_mission
begin_mission
focus_system
show_normal_route
show_capital_route
remove_mission_route
clear_mission_routes
show_jump_range
remove_jump_range
clear_mission_jump_ranges
add_mission_marker
remove_mission_marker
clear_mission_markers
fit_mission
clear_mission
create_saved_marker
```

`get_system_markers` aggregates the persistent Saved Marker and current session-only Mission Markers for one canonical `systemId`. `create_saved_marker` accepts only `systemId`, a supported `color`, and optional `name`/`notes`; application code always records AI provenance. Both operations use `LocalControlClient` and Control API v2. They never read storage directly, and Saved Marker access must be enabled in Preferences.

Permission denial remains `CAPABILITY_DENIED`, an existing marker remains `MARKER_ALREADY_EXISTS`, and retries reuse the Control client's session-scoped idempotency key. MCP exposes no Saved Marker update, delete, clear, replace, tag, or child operation.

When the app is not running or AI Control is disabled, MCP initialization and `tools/list` still work, while a map tool returns `APP_DISCONNECTED`. Session credentials remain internal to `LocalControlClient`; compatibility is determined by the discovery and handshake `protocolVersion`, `controlApiVersion`, and `instanceId`, not by exact equality of the release `appVersion` string.
