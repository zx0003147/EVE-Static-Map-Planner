# EVE Map Assistant Plugin

EVE Map Assistant is a repo-local Codex plugin for EVE Static Map Planner. Its bundled skill teaches Codex how to select and sequence the existing 20 map-control tools. It does not implement route algorithms or add map-control capabilities.

## Prerequisites

- EVE Static Map Planner 0.2.0 is installed.
- The installed bridge is at `%LOCALAPPDATA%\EVE Static Map Planner\EVE Map MCP Bridge.exe`.
- Start the map and enable **Preferences > AI Control** before performing map operations.
- The `eve-static-map` MCP server is registered and enabled in Codex.
- **EVE Map Assistant** is installed from this repository's `personal` marketplace.
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

## Install the development plugin

The repository marketplace is `.agents/plugins/marketplace.json`, and the plugin source is `plugins/eve-map-assistant`.

1. Restart the ChatGPT desktop app with this repository open so it can discover the repo marketplace.
2. If the **Personal** local source does not appear, run `codex plugin marketplace add "."` from the repository root, then confirm it with `codex plugin marketplace list`.
3. Open the Plugins Directory and install **EVE Map Assistant**, or run `codex plugin add eve-map-assistant@personal`.
4. Start a new Codex task so the installed plugin copy, bundled skill, and MCP dependency are loaded.

To remove it, use the plugin's menu in the Plugins Directory, or use the official CLI after confirming the marketplace name:

```text
codex plugin remove eve-map-assistant@personal
```

If you explicitly added this repository as the `personal` marketplace and no longer need that source, remove it after uninstalling the plugin with `codex plugin marketplace remove personal`.

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

## Validation

From the repository root, run the Plugin Creator validator, Skill Creator validator, and the project contract check:

```text
<python> <plugin-creator>/scripts/validate_plugin.py plugins/eve-map-assistant
<python> <skill-creator>/scripts/quick_validate.py plugins/eve-map-assistant/skills/eve-map-assistant
<python> qa/validate-eve-map-assistant.py
```

Official references: [Package your plugin](https://developers.openai.com/plugins/build/plugins), [Build skills](https://developers.openai.com/plugins/build/skills), and [Model Context Protocol](https://learn.chatgpt.com/docs/extend/mcp).
