# EVE Static Map Planner v2.0.0

This major release consolidates the completed Embedded AI, identity, Ansiblex access-control, Sovereignty, and
character-tracking work into the Windows Desktop product while retiring the separate Web and Mini Map surfaces.

## Highlights

### Embedded AI and marker reliability

- Makes agent action execution deterministic across tool results, protected actions, and follow-up turns.
- Adds explicit color selection for temporary markers and AI Mission markers.
- Corrects chat composer keyboard behavior: Enter sends and Shift+Enter inserts a newline.

### EVE Identity and Ansiblex access control

- Adds Core-owned EVE Identity selection backed by external providers, including stable multi-character restoration.
- Adds the Alliance Directory capability and public metadata fallback for stable Alliance IDs, names, and tickers.
- Restricts Ansiblex visibility and routing to the selected character's permitted alliances while keeping unavailable
  links inspectable when requested.
- Adds Webway paste import, reverse-row reconciliation, and stable numeric Ansiblex owner Alliance IDs.
- Keeps the Ansiblex manager paste dialog above its owner window and fixes hidden-link filtering for permitted links.

### Typed Sovereignty in Core

- Adds the typed Sovereignty Feature API capability and a Core-owned immutable ownership context.
- Moves current map, System Info, Preferences, AI, and MCP Sovereignty presentation into Core.
- Keeps legacy Pack Overlay/System Info behavior only as an old-Host compatibility path.

### Character tracking on the main map

- Renders character tracking directly on both the 2D and REAL_3D main maps.
- Adds foreground-character selection and priority coordination without coupling tracking to a secondary window.
- Retains per-character tracking controls and safe multi-character restoration through the ESI Pack.

## Fixes

- Stabilizes EVE Identity selection while providers restore, refresh, disconnect, or reorder characters.
- Preserves permitted Ansiblex links when unavailable links are hidden.
- Reconciles duplicated reverse-direction Webway rows into the normalized logical connection model.
- Restores a clean root `build`/`check` path after removing obsolete Kotlin/JS tasks and lock state.

## Removed features

- Removes the Web product, browser client, Web Pack/export pipeline, loader, Web QA scripts, and Web documentation.
- Removes the Mini Map window, HUD mode, global recovery hotkey, and Mini Map preferences.
- Removes Kotlin/JS and Node/Yarn build support, including the committed `package-lock.json`.

## Compatibility and migration

- Product version: `2.0.0`.
- Feature API artifact remains `2.5.0`; runtime compatibility family remains `2`.
- Recommended external releases are ESI Pack `2.0.0`, Sovereignty Pack `1.0.0`, and EVE Map Assistant Plugin `1.0.0`.
- Older family-2 Packs remain discoverable and loadable, but they do not provide the new EVE Identity or typed
  Sovereignty capabilities. ESI Pack 2.0.0 requires this Planner release.
- `user.db` is schema 6. Supported older schemas migrate transactionally. A schema-5 textual Ansiblex owner is
  retained as a display-only owner name; a stable numeric Alliance ID must be imported before it can grant access.
- Managed `static.db` remains schema 2 and settings remain version 8; this release does not change either format.
- Feature Packs remain external and are not bundled in the Planner ZIP.

## Known issues

- EVE Identity, live Character Tracking, and typed Sovereignty require the corresponding external Pack to be installed
  and enabled. Live ESI behavior still depends on network availability, valid authorization, and CCP service health.
- The retired Web and Mini Map products have no migration target; their former preferences and build artifacts are not
  used by the Desktop application.

## Download

- Windows x64 Portable ZIP, including the Desktop application and MCP launchers.
