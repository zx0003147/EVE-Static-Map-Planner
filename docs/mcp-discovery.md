# MCP STDIO discovery locator

The recommended generic integration is the fixed Streamable HTTP endpoint
`http://127.0.0.1:27892/mcp`. Codex should normally use the EVE Map Assistant HTTP Plugin. The locator documented here
remains schema version 1 for existing STDIO and locator-aware clients; the fixed HTTP endpoint does not need to be
written into per-profile discovery state.

EVE Static Map Planner publishes one authoritative MCP discovery locator after each packaged application startup:

```text
%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json
```

The v1 contract contains only public local integration data:

```json
{
  "schemaVersion": 1,
  "appVersion": "1.3.0",
  "transport": "stdio",
  "command": "D:\\Tools\\EVE Static Map Planner\\eve-map-mcp.exe"
}
```

`command` is the absolute, normalized path to the real `eve-map-mcp.exe` beside the currently running packaged GUI
launcher. The application derives it from the jpackage application path, never from the current working directory.
The file is created or atomically replaced only when its contract content changes. Moving the complete Portable
directory and starting the map updates this same file. A missing MCP executable removes a stale supported locator;
locator failure never blocks the GUI. An older application preserves a locator whose `schemaVersion` is greater than
the version it understands.

A locator-aware STDIO client should perform this sequence at session initialization:

```text
read locator
→ require schemaVersion == 1
→ require transport == stdio
→ require command is an existing file
→ start command directly as an MCP STDIO process
→ reuse that MCP connection for the session
```

Read the locator again for a new session, after the MCP process exits, when `command` is not found, or during an
explicit reconnect. Do not reread it before every MCP tool call.

The locator is not an HTTP endpoint advertisement, AI-client configuration file, launcher, relay, control-session descriptor, or a place
for credentials. It contains no token, session secret, user identity, database path, port, PID, or client-specific
configuration. Locator-aware clients must not assume that legacy/manual MCP registrations are rewritten by the map.

The HTTP host and STDIO bridge share the same MCP server factory and 32 tools. The HTTP side currently targets Kotlin
MCP SDK 0.14.0 / 2025-series Streamable HTTP; migration to MCP 2026-07-28 waits for corresponding official Kotlin SDK
support.
