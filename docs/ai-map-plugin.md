# EVE Map Assistant Integration

EVE Map Assistant is a separately distributed Codex Plugin and Skill for EVE Static Map Planner. Its source, marketplace metadata, Skill instructions, bundled MCP definition, and Plugin-specific QA live in the sibling `EVE-Map-Assistant-Plugin` repository.

Repository: [zx0003147/EVE-Map-Assistant-Plugin](https://github.com/zx0003147/EVE-Map-Assistant-Plugin)

## Gate A prerequisites

- EVE Static Map Planner 0.6.0 or later is installed.
- The MSI has registered its install directory on the per-user PATH.
- Start the map and enable **Preferences > AI Control** before map operations.
- EVE Map Assistant Plugin 0.3.0 or later is installed.
- Fully restart Codex after installing or upgrading the map so the new process inherits PATH.

Plugin 0.3.0 bundles an `eve-static-map` local STDIO definition whose command is `eve-map-mcp.exe`. The command resolves through Windows PATH and starts `dev.evestaticmapplanner.mcp.MainKt` directly. It does not use a shell, absolute path, Windows username, control port, or credential. Normal installation no longer uses `codex mcp add`.

```text
Map MSI -> stable eve-map-mcp.exe + per-user PATH
Plugin  -> bundled eve-static-map definition + Skill
Codex   -> direct STDIO process
```

## Install the standalone Plugin

1. Install or upgrade the map to 0.6.0 or later.
2. Open the standalone Plugin repository.
3. Run `codex plugin marketplace add "."`, then install with `codex plugin add eve-map-assistant@personal` or the Plugins Directory.
4. Fully restart Codex and open a new task.
5. Use `@EVE Map Assistant`.

## Legacy Gate B migration

Codex 0.149.0 gives a same-named global MCP configuration precedence over a discovered Plugin MCP. This does not create a fatal conflict, but the old registration masks the bundled command. After the 0.6.0 Map and 0.3.0 Plugin are ready, remove only the old global registration:

```text
codex mcp remove eve-static-map
```

Do not run another `codex mcp add`. Neither the Map installer nor Plugin changes Codex configuration automatically.

## Quick checks

Query only:

```text
Calculate the normal route from Jita to Amarr and tell me the jump count. Do not display it on the map.
```

Mission display:

```text
Create a temporary map mission named Delve Move, show the requested normal route, add RALLY and DESTINATION markers, show a 5 LY jump range, and fit the mission.
```

The first prompt must not create a Mission or display a route. The second must use only Mission-owned temporary objects. Capital requests require an explicit effective jump range.

Plain marker requests are temporary Mission Markers. Only an explicit permanent or Saved Marker request may call `create_saved_marker`; supported initial tags may be included in that one atomic create, but AI cannot change tags on an existing Saved Marker. Query requests use `get_system_markers` and distinguish persistent Saved Marker data from session-only Mission Markers. Saved Marker read/create requires the separate Saved Marker Access permission in Preferences; denial never silently falls back to a Mission Marker.

## Disconnected and safety behavior

If the Plugin is installed but `eve-map-mcp.exe` cannot start, install EVE Static Map Planner 0.6.0 or later and fully restart Codex. Do not search for a development launcher or fall back to a shell.

`APP_DISCONNECTED` is different: the MCP server is running, but the map is closed or AI Control is disabled. Start the map and enable AI Control. The assistant may control temporary Mission state and permission-gated Saved Marker read/create only, including supported initial tags in a create request. It cannot update, overwrite, delete, clear, or change tags or children on an existing Saved Marker, or alter user routes, Ansiblex connections, preferences, databases, or other user-owned state.

The map repository continues to validate the application, Control API, launcher, MSI, and fixed 22-tool server contract. The Plugin repository validates the manifest, Skill, bundled `.mcp.json`, marketplace, and safe behavior contract.

Official references: [Package your plugin](https://developers.openai.com/plugins/build/plugins), [Build skills](https://developers.openai.com/plugins/build/skills), and [Model Context Protocol](https://learn.chatgpt.com/docs/extend/mcp).
