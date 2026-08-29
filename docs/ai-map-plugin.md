# EVE Map Assistant Integration

EVE Map Assistant is a separately distributed Codex Plugin and Skill for EVE Static Map Planner. Its source, marketplace metadata, Skill instructions, bundled MCP definition, and Plugin-specific QA live in the sibling `EVE-Map-Assistant-Plugin` repository.

Repository: [zx0003147/EVE-Map-Assistant-Plugin](https://github.com/zx0003147/EVE-Map-Assistant-Plugin)

## Portable prerequisites

- EVE Static Map Planner 0.6.0 or later is extracted as a complete Portable application directory.
- The external MCP client registration points to that directory's absolute `eve-map-mcp.exe` path.
- Start the map and enable **Preferences > AI Control** before map operations.
- EVE Map Assistant Plugin 0.3.0 or later is installed.
- Restart Codex after installing the Plugin or changing MCP registration.

The current Plugin 0.3.0 workflow below is a legacy/manual absolute-path registration. The map now also publishes the
client-neutral locator documented in `mcp-discovery.md`, but this repository does not claim that Plugin 0.3.0 consumes
it. Locator-aware Plugin work is a separate release task.

Plugin 0.3.0 bundles an `eve-static-map` STDIO definition whose command is the bare `eve-map-mcp.exe` name.
The Portable ZIP intentionally does not add its directory to PATH, so that bare command is not automatically
discoverable. The reliable ZIP workflow is an explicit absolute-path registration:

```powershell
codex mcp remove eve-static-map
codex mcp add eve-static-map -- "D:\Portable Apps\EVE Static Map Planner\eve-map-mcp.exe"
```

Codex gives a same-named explicit MCP configuration precedence over the Plugin definition. The registration contains
only the launcher path; the per-session control secret and port remain discovered securely through LocalAppData.

```text
Portable app image -> bundled eve-map-mcp.exe
Explicit MCP config -> absolute launcher path
Plugin             -> Skill and safe operating contract
Codex              -> direct STDIO process
```

## Install the standalone Plugin

1. Extract the complete EVE Static Map Planner 0.6.0-or-later ZIP.
2. Register the absolute `eve-map-mcp.exe` path as shown above.
3. Open the standalone Plugin repository.
4. Run `codex plugin marketplace add "."`, then install with `codex plugin add eve-map-assistant@personal` or the Plugins Directory.
5. Fully restart Codex and open a new task.
6. Use `@EVE Map Assistant`.

## Moving or updating the Portable directory

The external MCP client stores the absolute launcher path. After moving the application directory or extracting a new
release elsewhere, remove and recreate only `eve-static-map` with the new path, then restart Codex. Neither the
Portable application nor Plugin changes Codex configuration automatically.

A future locator-aware Plugin may instead reread `%LOCALAPPDATA%\EVE Static Map Planner\integration\mcp.json` when
initializing or reconnecting. Restarting the moved map updates that locator; it does not repair this Plugin's current
manual Codex registration.

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

If the Plugin is installed but `eve-map-mcp.exe` cannot start, verify the explicit MCP registration points to the
current extracted Portable directory and fully restart Codex. Do not fall back to a shell command.

`APP_DISCONNECTED` is different: the MCP server is running, but the map is closed or AI Control is disabled. Start the map and enable AI Control. The assistant may control temporary Mission state and permission-gated Saved Marker read/create only, including supported initial tags in a create request. It cannot update, overwrite, delete, clear, or change tags or children on an existing Saved Marker, or alter user routes, Ansiblex connections, preferences, databases, or other user-owned state.

The map repository continues to validate the application, Control API, Portable launchers/ZIP, and fixed 22-tool
server contract. The Plugin repository validates the manifest, Skill, bundled `.mcp.json`, marketplace, and safe
behavior contract.

Official references: [Package your plugin](https://developers.openai.com/plugins/build/plugins), [Build skills](https://developers.openai.com/plugins/build/skills), and [Model Context Protocol](https://learn.chatgpt.com/docs/extend/mcp).
