# EVE Static Map Planner 0.6.0

This release candidate finalizes Feature API 2.0.0 and the external Feature Pack product experience.

## Highlights

- Feature API runtime contract 2 with isolated Dynamic Overlay, Route Action, and Pack Controls capabilities.
- External Sovereignty Pack 0.2.0 compatibility with static overlay and System Info contributions.
- Optional ESI Pack 0.5.0 support without any ESI-specific Core dependency.
- EVE SSO Connect/Disconnect and current-character status under Preferences → Feature Packs.
- Live character-location Dynamic Overlay with last-known and refresh state presentation.
- `Refresh Location`, `Set EVE Destination`, and explicit `Send Draft to EVE` product actions.
- Map movement, selection, and route recalculation never auto-sync to EVE.
- Encrypted Windows current-user DPAPI refresh-token persistence and automatic session restoration.
- JDK 25 native-access configuration for Gradle, manual QA, and packaged Windows launchers.
- Installer-free Windows x64 Portable ZIP with a bundled JVM, complete MCP launchers, and reproducible archive order.
- Client-neutral MCP discovery locator that follows the real Portable app-image after first run or directory moves.
- In-process localhost Streamable HTTP MCP at `http://127.0.0.1:27892/mcp`, bound only to IPv4 loopback.
- Dual HTTP and STDIO transports backed by the same canonical 22-tool catalog and Control transport.
- EVE Map Assistant 0.5.0 integration with no PATH, absolute launcher path, or Portable move re-registration.
- Existing static-universe map, search, normal and capital routing, manual Ansiblex, multiple jump-range overlays,
  route visualization, marker workflows, zoom/pan/selection, and System Info improvements from the completed V1 work.

## Windows installation

Download `EVE-Static-Map-Planner-0.6.0-Windows-x64.zip`, extract the complete archive, and open
`EVE Static Map Planner.exe` inside the extracted `EVE Static Map Planner` directory. Do not copy only the EXE.
No installer or system Java is required.

User data and Feature Packs remain under `%LOCALAPPDATA%\EVE Static Map Planner`; they are not stored in the
Portable program directory. Moving to a future ZIP release therefore does not reset settings, databases, Pack state,
or ESI credential storage.

## Install optional Packs

After running the Portable application once, place each optional artifact at its canonical per-user path:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\esi.pack\pack.jar
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

New Packs are disabled by default. Enable them under Preferences → Feature Packs. Core remains fully usable when no
Pack is installed.

## Route-action limits

- `Send Draft to EVE` supports only pure-Stargate `NORMAL` routes.
- At most 10 ordered post-source anchors are sent.
- Routes longer than 10 hops are deterministically compressed; compressed anchors are not an exact intermediate path.
- Exact Ansiblex route sending is unsupported; use `Set EVE Destination` for the final destination instead.
- Capital and mission routes are unsupported by these ESI actions.

## Compatibility and limitations

- Requires Feature API runtime contract 2; external Pack artifact compatibility is Feature API 2.0.0.
- Compatible optional Packs are ESI Pack 0.5.0 and Sovereignty Pack 0.2.0.
- Compatible Codex integration is EVE Map Assistant Plugin 0.5.0 using localhost HTTP MCP.
- The Map must be running with AI Control enabled for Plugin tool calls.
- HTTP transport targets the supported 2025-series Streamable HTTP contract in Kotlin MCP SDK 0.14.0; migration to
  MCP 2026-07-28 remains deferred until supported by the official Kotlin SDK.
- Windows x64 Portable ZIP is the only release distribution; MSI is retired.

The localhost HTTP MCP, Portable move behavior, Map restarts, EVE SSO, Location, explicit route sending, session
restoration, Disconnect, and Plugin natural-language workflows have completed human acceptance. This release
candidate is not remotely published or tagged.
