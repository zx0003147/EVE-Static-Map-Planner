# EVE Map Assistant Integration

EVE Map Assistant is a separately distributed Codex Plugin and Skill for EVE Static Map Planner. This repository provides the map application and `eve-static-map` MCP bridge; it no longer contains the Plugin implementation or Plugin-specific QA.

The current standalone source repository is a sibling local repository named `EVE-Map-Assistant-Plugin`.

Repository URL: pending publication.

## Prerequisites

- EVE Static Map Planner 0.2.0 is installed.
- The installed bridge is at `%LOCALAPPDATA%\EVE Static Map Planner\EVE Map MCP Bridge.exe`.
- Start the map and enable **Preferences > AI Control** before performing map operations.
- The `eve-static-map` MCP server is registered and enabled in Codex.
- **EVE Map Assistant** is installed from the standalone repository's `personal` marketplace.
- Use a Codex client that supports local plugins and local STDIO MCP servers.

## Gate B: why MCP registration is separate

The current official plugin format can bundle a local STDIO MCP command, but it does not document expansion of `%LOCALAPPDATA%` inside that command. Under Gate B, the plugin itself does not embed or launch an MCP executable path, does not contain `.mcp.json`, and does not hard-code a Windows username. Register the installed bridge once with the official Codex CLI using its actual absolute path:

```text
codex mcp add eve-static-map -- "<actual install root>\EVE Map MCP Bridge.exe"
codex mcp get eve-static-map
```

The executable path is one argument even though it contains spaces. Start the bridge directly; do not place a command interpreter or script wrapper in front of it. The registration contains no control port or credential.

To remove this MCP registration:

```text
codex mcp remove eve-static-map
```

## Standalone Plugin repository

Plugin source, marketplace metadata, Skill instructions, and Plugin-specific contract validation are maintained only in `EVE-Map-Assistant-Plugin`. The Plugin remains independently versioned and distributed from the map application.

1. Open the standalone Plugin repository, not this map repository.
2. Run `codex plugin marketplace add "."` from the standalone repository root, then confirm it with `codex plugin marketplace list`.
3. Open the Plugins Directory and install **EVE Map Assistant**, or run `codex plugin add eve-map-assistant@personal`.
4. Start a new Codex task so the installed plugin copy, bundled skill, and MCP dependency are loaded.

To remove it, use the plugin's menu in the Plugins Directory, or use the official CLI after confirming the marketplace name:

```text
codex plugin remove eve-map-assistant@personal
```

If you explicitly added the standalone repository as the `personal` marketplace and no longer need that source, remove it after uninstalling the Plugin with `codex plugin marketplace remove personal`.

## Quick checks

Query-only check:

```text
Calculate the normal route from Jita to Amarr and tell me the jump count. Do not display it on the map.
```

Visual check:

```text
Create a temporary map mission named Delve Move, show the requested normal route, add RALLY and DESTINATION markers, show a 5 LY jump range, and fit the mission.
```

The first prompt must not create a Mission or display a route. The second should use only Mission-owned temporary objects. Capital requests must use the capital tools and require an explicit effective jump range.

## Disconnected and safety behavior

`APP_DISCONNECTED` means the plugin and MCP bridge can be available while no map AI Control session is active. Start EVE Static Map Planner and enable AI Control; the skill must not use another path to modify the map.

The assistant can control only temporary Mission state. It cannot alter user routes, saved markers, Ansiblex connections, preferences, databases, or other user-owned state. Session credentials and local discovery details remain internal to the bridge.

## Responsibility boundary

This map repository continues to validate the application, Control API, MCP bridge, and fixed 20-tool server contract through its own Kotlin tests. Run Plugin Creator validation, Skill lint, and `qa/validate-eve-map-assistant.py` from the standalone Plugin repository.

The standalone Plugin does not contain route algorithms, Control API implementations, databases, or the MCP server implementation. It declares only the existing `eve-static-map` dependency and teaches Codex how to use that fixed tool contract safely.

Official references: [Package your plugin](https://developers.openai.com/plugins/build/plugins), [Build skills](https://developers.openai.com/plugins/build/skills), and [Model Context Protocol](https://learn.chatgpt.com/docs/extend/mcp).
