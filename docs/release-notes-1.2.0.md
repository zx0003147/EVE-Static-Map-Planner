# EVE Static Map Planner 1.2.0

## Shared Map and Shared Markers

- Add the optional `shared-client` module and Shared Map Protocol v1 client without coupling it to Compose, local data,
  Feature API, or MCP.
- Connect to a Shared Map Workspace through an invite-to-Device-Token flow. Remote servers require HTTPS; HTTP is
  limited to localhost development endpoints, and Windows DPAPI protects stored Device Tokens.
- Render Shared Markers alongside local Saved Markers and AI Mission Markers with independent visuals and state.
- Add role-aware Shared Marker create, edit, delete, and Manager workflows for Admin, Editor, and Viewer access.
- Add Admin member creation, role changes, membership revocation, invite management, and device revocation.
- Protect marker and membership edits with optimistic versions and surface conflicts without silently overwriting
  newer server state.
- Poll the selected Workspace every 30 seconds and atomically replace its in-memory snapshot after a successful
  refresh.
- Preserve the last in-memory snapshot as visibly stale during transient offline/degraded operation. Shared snapshots
  are not persisted to disk and are cleared on disconnect.
- Support self-hosted production Shared Map Servers over HTTPS while keeping the complete Map local-first and usable
  without any server.

## Compatibility

- Core application: `1.2.0`
- Shared Map protocol: `1`
- Feature API: unchanged at `2.0.0`
- MCP catalog: unchanged at exactly `30` tools
- ESI and Sovereignty Feature Packs: no breaking change and no new release required
- EVE Map Assistant: existing AI Mission, route, jump-range, Wormhole, and permission-gated local Saved Marker behavior
  is unchanged
- AI/MCP has no Shared Marker read, create, update, delete, membership, invite, or device-management capability
