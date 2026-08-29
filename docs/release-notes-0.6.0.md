# EVE Static Map Planner 0.6.0

This release candidate finalizes Feature API 2.0.0 and the external Feature Pack product experience.

## Highlights

- Feature API runtime contract 2 with isolated Dynamic Overlay, Route Action, and Pack Controls capabilities.
- External Sovereignty Pack 0.2.0 compatibility with static overlay and System Info contributions.
- Optional ESI Pack 0.5.0 support without any ESI-specific Core dependency.
- EVE SSO Connect/Disconnect and current-character status under Preferences → Feature Packs.
- Live character-location Dynamic Overlay with last-known and refresh state presentation.
- `Refresh Location`, `Set EVE Destination`, and `Send to EVE` product actions.
- Encrypted Windows current-user DPAPI refresh-token persistence and automatic session restoration.
- JDK 25 native-access configuration for Gradle, manual QA, and packaged Windows launchers.
- Same-version replacement of discarded 0.6.0 pre-release MSIs, preventing stale Windows Installer component state from omitting the packaged JVM module image.
- Major upgrades close the GUI and installed MCP bridge before full component replacement, avoiding locked-runtime reboot repairs.

## Install optional Packs

Install the 0.6.0 MSI, then place each optional artifact at its canonical per-user path:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\esi.pack\pack.jar
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

New Packs are disabled by default. Enable them under Preferences → Feature Packs. Core remains fully usable when no
Pack is installed.

## Route-action limits

- `Send to EVE` supports only pure-Stargate `NORMAL` routes.
- At most 10 ordered post-source anchors are sent.
- Routes longer than 10 hops are deterministically compressed; compressed anchors are not an exact intermediate path.
- Exact Ansiblex route sending is unsupported; use `Set EVE Destination` for the final destination instead.
- Capital and mission routes are unsupported by these ESI actions.

This release candidate is not remotely published or tagged. Final publication remains gated on packaged-app manual QA.
