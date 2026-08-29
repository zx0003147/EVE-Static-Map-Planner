# MCP discovery locator

EVE Static Map Planner publishes one authoritative MCP discovery locator after each packaged application startup:

```text
%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json
```

The v1 contract contains only public local integration data:

```json
{
  "schemaVersion": 1,
  "appVersion": "0.6.0",
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

An external AI plugin should perform this sequence at session initialization:

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

The locator is not an AI-client configuration file, a launcher, a relay, a control-session descriptor, or a place
for credentials. It contains no token, session secret, user identity, database path, port, PID, or client-specific
configuration. Locator-aware plugins must not assume that legacy/manual MCP registrations are rewritten by the map.
