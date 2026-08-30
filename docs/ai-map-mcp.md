# AI Map MCP transports

EVE Static Map Planner exposes the same fixed 28-tool MCP server through two local transports:

```text
Streamable HTTP client -> http://127.0.0.1:27892/mcp -> MCP server in the Map JVM
STDIO client          -> eve-map-mcp.exe             -> standalone MCP bridge
                                                        |
                                                        v
                              secure discovery -> authenticated Map Control API
```

Streamable HTTP is the recommended generic integration and the preferred transport for the EVE Map Assistant Codex
Plugin. The HTTP listener is owned by the running Map process; it does not launch a second JVM and remains independent
of the Plugin. STDIO remains supported for existing integrations and locator-aware clients. Both transports construct
the canonical server through `createMcpServer`, use `LocalMcpMapClient`, and therefore keep Control discovery,
authentication, idempotency, permissions, and error mapping unchanged.

The current HTTP compatibility target is Kotlin MCP SDK 0.14.0 and its 2025-series Streamable HTTP implementation.
This is not a claim of full MCP 2026-07-28 transport compliance. A future migration is gated on upgrading to an
official Kotlin SDK release that supports that protocol version; no private compatibility protocol is implemented.

## Localhost Streamable HTTP

The production endpoint is fixed:

```text
http://127.0.0.1:27892/mcp
```

The listener binds only IPv4 loopback. It starts after the map scene and Control lifecycle have initialized, but a
Control preference disabled state does not prevent MCP initialize or `tools/list`; tool calls return
`APP_DISCONNECTED`. A port conflict is nonfatal to the GUI: the first Map instance retains the endpoint and a later
instance records it as unavailable without choosing a fallback port.

The host is stateless at the Streamable HTTP layer and accepts at most eight concurrent requests. It limits request
bodies to 64 KiB, preserves the Control transport's 1 MiB response bound, applies a 60-second request/tool timeout,
and configures CIO idle connection cleanup for ten minutes. Ktor CIO 3.4.3 does not expose an independent server
header/read timeout setting, so the requested ten-second header/read target is not represented by a fabricated API.
CIO uses its own connection parsing defaults; after routing begins, MCP request and tool handling is bounded to 60
seconds, while idle connections are cleaned up after ten minutes.

Requests must use the exact `Host: 127.0.0.1:27892` authority. An absent `Origin` is accepted; every present `Origin`
is rejected with 403. The endpoint does not emit CORS allow headers, rejects `OPTIONS`, and has no bearer token because
it exposes only the existing MCP-to-Control adapter on loopback. Control credentials never appear in HTTP responses
or logs.

On shutdown the Map stops HTTP first, then Control, then repositories and UI resources. This cancels active requests,
releases port 27892, and prevents the HTTP adapter from outliving its authenticated Control hop.

## STDIO launcher (still supported)

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

## Windows Portable distribution

The Windows x64 Portable ZIP contains the bridge as two native jpackage launchers in the same application image as
the desktop app:

```text
<extracted Portable directory>\
├─ EVE Static Map Planner.exe
├─ EVE Map MCP Bridge.exe
├─ eve-map-mcp.exe
├─ app\
└─ runtime\
```

All launchers use the one bundled jlink runtime. The GUI uses the Windows GUI subsystem; both MCP launchers use the
console subsystem, start `dev.evestaticmapplanner.mcp.MainKt`, and have identical production-only classpaths under
`app\mcp`.

The ZIP does not modify PATH or external MCP-client configuration. The packaged map publishes the STDIO
locator `%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json`; a locator-aware plugin can read it at session
initialization or reconnect and start the reported `eve-map-mcp.exe` directly. See `mcp-discovery.md`.

Codex users should prefer the HTTP Plugin. The manual STDIO registration below remains supported and stores the absolute `eve-map-mcp.exe` path (or compatibility
launcher) from the chosen extracted directory. Moving or replacing that directory requires updating the manual
external registration.

The program image contains no MCP locator, control discovery descriptor, session key, lock, database, preferences, or
other user state. `mcp.json` is created only beneath `%LOCALAPPDATA%\EVE Static Map Planner\integration` after GUI
startup. `active-instance.json`, `session.key`, and `active.lock` are created only beneath
`%LOCALAPPDATA%\EVE Static Map Planner\control` after the running app enables AI Control. The bridge remains
STDIO-only and its dedicated classpath excludes Ktor HTTP/SSE/WebSocket server runtimes; Ktor CIO is carried only by
the GUI classpath for the in-process localhost host.

## Register an extracted STDIO launcher

Register either a development image or extracted Portable image with the official Codex CLI:

```powershell
codex mcp add eve-static-map -- "C:\absolute\path\to\EVE Map MCP Bridge.exe"
codex mcp get eve-static-map
```

This registration is tied to the current absolute path. It contains no session key, bearer token, port, discovery
descriptor, environment override, or shell command. Restart the Codex client or open a new task after registration.

To remove only this registration:

```powershell
codex mcp remove eve-static-map
```

## Portable STDIO registration

Register the stable launcher from the directory you actually extracted:

```powershell
codex mcp remove eve-static-map
codex mcp add eve-static-map -- "D:\Portable Apps\EVE Static Map Planner\eve-map-mcp.exe"
codex mcp get eve-static-map
```

Restart Codex after changing the registration. With the map closed, initialization and `tools/list` should still
succeed while a map tool returns `APP_DISCONNECTED`. With the map running and AI Control enabled, perform a light
end-to-end check such as search, focus, begin mission, show route, add a temporary marker, query markers, and clear
the mission.

The manual registration stores an absolute external path. If the Portable directory is moved or a new release is extracted
elsewhere, repeat the registration with the new launcher path.

## Fixed tool surface

The server exposes exactly these 28 tools:

```text
search_system
get_system_info
get_system_markers
calculate_normal_route
calculate_capital_route
list_views
get_current_view
create_view
rename_view
switch_view
delete_view
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

`get_system_markers` aggregates the persistent Saved Marker and current View's AI Mission Markers for one canonical `systemId`. `create_saved_marker` accepts `systemId`, a supported `color`, optional `name`/`notes`, and optional supported initial `tags`. Marker and initial tags are committed atomically; application code always records AI provenance. The tool cannot modify tags on an existing Saved Marker. Both operations use `LocalControlClient` and Control API v2. They never read storage directly, and Saved Marker access must be enabled in Preferences.

Permission denial remains `CAPABILITY_DENIED`, an existing marker remains `MARKER_ALREADY_EXISTS`, and retries reuse the Control client's session-scoped idempotency key. MCP exposes no Saved Marker update, delete, clear, replace, existing-tag mutation, or child mutation operation.

When the app is not running or AI Control is disabled, MCP initialization and `tools/list` still work, while a map tool returns `APP_DISCONNECTED`. Session credentials remain internal to `LocalControlClient`; compatibility is determined by the discovery and handshake `protocolVersion`, `controlApiVersion`, and `instanceId`, not by exact equality of the release `appVersion` string.
