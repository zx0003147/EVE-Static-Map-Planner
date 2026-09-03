# EVE Static Map Planner 1.3.0

This release completes the accepted Phase 5 navigation workflow across the Planner, EVE ESI Pack, and EVE Map
Assistant.

## Navigation and map workflows

- Added ordered Waypoints to both Normal and Capital navigation, including drag-and-drop ordering and numbered map
  presentation.
- Added independent per-View navigation sessions with clear stale and **Needs recalculation** state.
- Added compact stacking for same-system AI Mission markers.
- Added AI/Mission Normal and Capital waypoint workflows without changing manual route ownership.

## Explicit EVE navigation sending

- Added the optional **Send Navigation to EVE** action for Normal navigation through EVE ESI Pack 1.1.0.
- Sends only the explicit ordered Waypoints and Destination; calculated transit systems are never sent.
- Supports waypoint-plus-destination, destination-only, and waypoint-only navigation drafts.
- Uses the current Normal navigation draft even when the displayed calculated route is stale.
- AI may send only one unambiguously identified AI/Mission Normal route to one explicitly identified connected EVE
  character, and only after an explicit user request to send.
- There is no default or fallback character, no AI sending of manual routes, no automatic send after create,
  calculate, or show, and no Capital sending.

## Compatibility

- Feature API runtime compatibility remains family `2`; its current Maven artifact is `2.1.0`.
- The new navigation action contracts are optional and additive. Older family-2 Packs do not need to implement them.
- Control API remains `3`, and the MCP catalog now contains exactly 32 tools, including
  `list_eve_navigation_targets` and `send_mission_navigation_to_eve`.
- EVE ESI Pack 1.1.0 consumes Feature API 2.1.0. EVE Map Assistant 0.7.0 supplies the matching explicit authorization
  guidance.
- Sovereignty Pack 0.2.0 remains compatible unchanged while compiled against Feature API 2.0.0, proving coexistence
  between older and newer runtime-family-2 Packs.
