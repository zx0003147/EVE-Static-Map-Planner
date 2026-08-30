# Planning Views

`View` is the user-facing planning container. Each View has a stable opaque ID and a unique editable label. It owns one user normal-route draft, one user capital-route draft, generic route-action target selections, and zero or more AI Missions. Projection, static data, Saved Markers, Feature Packs, sovereignty, and connected ESI identities remain global.

View state is stored in `user.db` schema 5. The migration adds `planning_views` and `ai_missions`. Routes and current View selection are restored on startup; AI Mission routes, jump ranges, markers, and View ownership are serialized transactionally. Deleting a View removes its AI Missions but does not affect external character sessions.

The Control API and fixed HTTP MCP catalog expose `list_views`, `get_current_view`, `create_view`, `rename_view`, `switch_view`, and `delete_view`. A View label is resolved by the AI client, while map commands carry only the stable `viewId`. `begin_mission` and `get_active_missions` accept an optional `viewId`; omission deliberately means the View currently displayed in the map. Existing Mission object operations follow the Mission's stored ownership.

The localhost HTTP MCP endpoint remains `http://127.0.0.1:27892/mcp`; View support does not change the plugin transport.

Route Action target selection is generic and keyed by Pack plus selector ID. The ESI Pack contributes one
`EVE Character` selector shared by `Send Draft to EVE` and `Set EVE Destination`. Core persists only the opaque target
ID in the current View. If the selected target disappears, Core keeps that ID visible as unavailable and disables the
actions; it never substitutes another available target.
