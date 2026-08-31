# Planning Views

`View` is the user-facing planning container. Each View has a stable opaque ID and a unique editable label. It owns one user normal-route draft, one user capital-route draft, its own `Use Wormholes` option, generic route-action target selections, and zero or more AI Missions. Projection, static data, Saved Markers, the temporary Wormhole topology, Feature Packs, sovereignty, and connected ESI identities remain global.

View state is session-only. While the application is running, switching Views restores each View's routes, AI Mission overlays, and selected Route Action targets. Exiting discards every View and planning draft; the next startup is exactly one blank `View 1` with no routes, Missions, or selected target. Deleting a View removes its in-session AI Missions but does not affect external character sessions.

`user.db` remains schema 4 and contains no View or AI Mission tables. Saved Markers, Ansiblex data, settings, Feature Packs, and external character pools keep their existing persistence behavior.

All Views observe the same application-session Wormhole network; switching Views never restores an older topology.
If UI removal or Clear All deletes a Wormhole used by a Mission Normal Route, that route is removed in every View,
including inactive Views. The Mission and its unrelated routes, markers, jump ranges, and Capital Routes remain.

The Control API and fixed HTTP MCP catalog expose `list_views`, `get_current_view`, `create_view`, `rename_view`, `switch_view`, and `delete_view`. A View label is resolved by the AI client, while map commands carry only the stable `viewId`. `begin_mission` and `get_active_missions` accept an optional `viewId`; omission deliberately means the View currently displayed in the map. Existing Mission object operations follow the Mission's stored ownership.

The localhost HTTP MCP endpoint remains `http://127.0.0.1:27892/mcp`; View support does not change the plugin transport.

Route Action target selection is generic and keyed by Pack plus selector ID. The ESI Pack contributes one
`EVE Character` selector shared by `Send Draft to EVE` and `Set EVE Destination`. Core keeps only the opaque target
ID in the current in-memory View. If the selected target disappears, Core keeps that ID visible as unavailable and disables the
actions; it never substitutes another available target.
