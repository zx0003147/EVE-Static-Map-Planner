# EVE Static Map Planner 1.4.0

This release applies a cohesive EVE-inspired visual restyle to the desktop application without changing its map
rendering or navigation behavior.

## Highlights

- Applied a restrained EVE Online-inspired desktop UI theme.
- Added dark blue-gray panels, compact controls, thin borders, and near-square geometry.
- Restyled the main window chrome, menus, tabs, route tools, dialogs, preferences, and manager windows.
- Preserved the existing map layout, rendering style, routes, selection visuals, and map interactions.

## UI fixes

- Fixed clipped text in shared input fields.
- Fixed compact search and numeric field vertical layout.
- Added proper spacing between the Preferences navigation pane and content area.
- Improved consistency across marker and shared-marker editors.

## Under the hood

- Centralized UI colors, typography, dimensions, shapes, and reusable components.
- Kept the map rendering implementation visually unchanged.

## Validation

- 914 tests executed with 0 failures, 0 errors, and 7 skipped.
- Clean build passed.
- Manual UI acceptance passed.

## Known limitations

- Native `JFileChooser` dialogs continue to follow the operating system and Swing appearance.
- Map rendering visuals were intentionally preserved.
