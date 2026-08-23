# AI Map MCP development launcher

The `mcp` module is a local STDIO-only bridge between Codex and a running EVE Static Map Planner instance:

```text
Codex -> MCP STDIO -> EVE Map MCP Bridge
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

The image contains a bundled Java runtime. Starting the `.exe` does not require Gradle, a system JDK, a shell wrapper, or command-line arguments. It is a console launcher so stdin/stdout pipes remain available to the MCP client. A future MSI should add this as a second jpackage launcher beside the desktop launcher so both executables can share one installed runtime.

## Development registration

Use the official Codex CLI with the launcher's absolute path:

```powershell
codex mcp add eve-static-map -- "C:\absolute\path\to\EVE Map MCP Bridge.exe"
codex mcp get eve-static-map
```

This is a development registration tied to the current build path. It contains no session key, bearer token, port, discovery descriptor, environment override, or shell command. Restart the Codex client or open a new task after registration.

To remove only this development registration:

```powershell
codex mcp remove eve-static-map
```

## Fixed tool surface

The server exposes exactly these 20 tools:

```text
search_system
get_system_info
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
```

When the app is not running or AI Control is disabled, MCP initialization and `tools/list` still work, while a map tool returns `APP_DISCONNECTED`. Session credentials remain internal to `LocalControlClient`; compatibility is determined by the discovery and handshake `protocolVersion`, `controlApiVersion`, and `instanceId`, not by exact equality of the release `appVersion` string.
