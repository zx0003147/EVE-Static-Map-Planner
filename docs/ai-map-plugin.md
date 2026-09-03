# EVE Map Assistant Integration

EVE Map Assistant 0.7.0 is a separately distributed Codex Plugin and Skill for EVE Static Map Planner. Its source,
marketplace metadata, Skill instructions, fixed localhost HTTP MCP definition, and Plugin-specific QA live in the
sibling `EVE-Map-Assistant-Plugin` repository.

Repository: [zx0003147/EVE-Map-Assistant-Plugin](https://github.com/zx0003147/EVE-Map-Assistant-Plugin)

## Requirements

- Extract EVE Static Map Planner 1.0.0 or later as a complete Portable application directory.
- Start the map and enable **Preferences > AI Control** before map operations.
- Install or enable EVE Map Assistant Plugin 0.7.0.
- Open a new Codex task after installing or updating the Plugin.

The Plugin connects to the Streamable HTTP endpoint hosted inside the running Map JVM:

```text
http://127.0.0.1:27892/mcp
```

It does not bundle an MCP executable and does not require PATH, PowerShell, Java, an absolute launcher path, or a
manual Codex MCP registration. The server binds only IPv4 loopback and exposes the same canonical 32 tools as the
supported STDIO bridge.

```text
EVE Static Map Planner -> localhost HTTP MCP (127.0.0.1:27892/mcp)
EVE Map Assistant     -> Skill, safe operating contract, and HTTP MCP definition
Codex                  -> natural-language tool workflows
```

## Install the standalone Plugin

1. Start EVE Static Map Planner and enable **Preferences > AI Control**.
2. Install or enable EVE Map Assistant from its Plugin distribution.
3. Open a new Codex task so the Plugin is loaded.
4. Ask naturally, for example `Jita 在哪？` or `Jita 到 Amarr 怎么走？`.

For local marketplace development, open the standalone Plugin repository, run `codex plugin marketplace add "."`,
and install `eve-map-assistant@personal`.

## Moving or updating the Portable directory

The Plugin configuration is independent of the Portable directory path. Close the Map, move or replace the complete
Portable directory, restart the Map, and open a new Codex task. No MCP re-registration is required.

The separate STDIO launchers and schema-1 discovery locator remain available for non-Plugin integrations; see
`ai-map-mcp.md` and `mcp-discovery.md`. They are not part of the normal EVE Map Assistant installation path.

## Quick checks

Query only:

```text
Calculate the normal route from Jita to Amarr and tell me the jump count. Do not display it on the map.
```

Mission display:

```text
Create a temporary map mission named Delve Move, show the requested normal route, add RALLY and DESTINATION markers, show a 5 LY jump range, and fit the mission.
```

The first prompt must not create a Mission or display a route. The second must use only Mission-owned temporary
objects. Capital requests require an explicit effective jump range.

Plain marker requests are temporary Mission Markers. Only an explicit permanent or Saved Marker request may call
`create_saved_marker`; supported initial tags may be included in that one atomic create, but AI cannot change tags on
an existing Saved Marker. Query requests use `get_system_markers` and distinguish persistent Saved Marker data from
session-only Mission Markers. Saved Marker read/create requires the separate Saved Marker Access permission in
Preferences; denial never silently falls back to a Mission Marker.

## Disconnected and safety behavior

If the Plugin cannot connect, start EVE Static Map Planner, enable **Preferences > AI Control**, and open a new Codex
task. Do not add PATH entries, register a launcher, or fall back to a shell command.

The assistant may control temporary Mission state and permission-gated Saved Marker read/create only, including
supported initial tags in a create request. It cannot update, overwrite, delete, clear, or change tags or children on
an existing Saved Marker, or alter user routes, Ansiblex connections, preferences, databases, or other user-owned
state.

The map repository validates the application, Control API, localhost HTTP MCP, Portable launchers/ZIP, and fixed
32-tool server contract. The Plugin repository validates the manifest, Skill, HTTP `.mcp.json`, marketplace metadata,
and 54 natural-language behavior contracts.

Official references: [Package your plugin](https://developers.openai.com/plugins/build/plugins), [Build skills](https://developers.openai.com/plugins/build/skills), and [Model Context Protocol](https://learn.chatgpt.com/docs/extend/mcp).
