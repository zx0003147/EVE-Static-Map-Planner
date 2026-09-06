# EVE Static Map Planner 1.6.0

This release adds a compact, multi-character EVE position mini-map with safe foreground following and an optional
always-on-top HUD, backed by the typed Character Tracking capability in EVE ESI Pack 1.2.0.

## Highlights

### Character Mini-map

- Adds an always-on-top local mini-map for the followed EVE character.
- Shows the current system and the surrounding Stargate neighborhood at a configurable range of 1–5 hops.
- Can display Ansiblex connections without changing the Stargate-hop neighborhood.
- Uses character portraits for the followed character and other tracked characters, including same-system grouping.

### Multi-character ESI

- Consumes structured snapshots for multiple independently authorized EVE characters.
- Keeps one character's location or ESI failure isolated from the others.
- Uses the typed Character Tracking capability supplied by EVE ESI Pack 1.2.0.

### AUTO and PINNED follow

- AUTO follows the character belonging to the foreground EVE client.
- Normal character switches update immediately, while rapid A/B switching is protected by anti-flapping behavior.
- Switching to another application retains the last followed EVE character.
- PINNED locks the mini-map to an explicitly selected character.

### HUD mode

- Adds a transparent, borderless HUD presentation with configurable opacity.
- HUD Locked is mouse click-through and does not steal EVE focus.
- `Ctrl+Shift+M` returns the mini-map to Interactive mode through the global recovery hotkey.
- Adds screen-edge snapping and recovery for an off-screen mini-map window.

### UI and settings

- Aligns the mini-map and portrait markers with the Planner's compact EVE-inspired presentation.
- Adds dedicated Mini-map Settings and Marker Settings windows while retaining the existing preference storage.
- Hides all mini-map entry points when no runtime Character Tracking capability is available.
- Keeps the mini-map available with a clear empty state when the capability exists but no character is connected.

## Safety and architecture

- Does not inject into the EVE process, read game memory, or load a DLL into the game.
- OAuth tokens and refresh credentials remain private to EVE ESI Pack storage.
- The mini-map reuses the Planner's existing universe scene and does not open a second universe database.
- Loss of the Character Tracking capability closes open mini-map surfaces and safely clears HUD click-through state.

## Requirements

Mini-map Character Tracking requires EVE ESI Pack 1.2.0. If the runtime Character Tracking capability is absent,
including when the Pack is missing, disabled, incompatible, or failed, the Mini-map menu is intentionally hidden.

Compatibility: Feature API artifact `2.2.0`, runtime family `2`, EVE ESI Pack `1.2.0`.

## Known limitations

- Positioning the HUD relative to the EVE client window is not implemented; the HUD is positioned independently.
- EVE online status remains `UNKNOWN` unless a future capability supplies it; location freshness is reported separately.

