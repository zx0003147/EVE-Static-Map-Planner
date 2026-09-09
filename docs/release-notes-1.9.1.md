# EVE Static Map Planner 1.9.1

This hotfix release improves the browser Shared Map workflow and compact Web controls without adding a new protocol,
Web Pack schema, or product feature family.

## Fixed

- Makes the Web tool panels genuinely compact when collapsed, including the independent Map LOD and Desktop Routes
  sections, while preserving Search, Normal Route, Capital Route, Coverage, Personal Ansiblex, and Shared Marker
  workflows on desktop and tablet layouts.
- Adds authorized deletion of Desktop Route Handoffs from Web, with immediate removal after success and a transient
  error when deletion fails.
- Adds persistent Shared Map browser login behind **Remember this device**. The issued Device Access Token is stored
  in IndexedDB, validated against the Server on reload or PWA reopen, and removed on Disconnect or invalid,
  expired, revoked, forbidden, or Workspace-mismatched credentials.
- Keeps session-only behavior when Remember is disabled and improves browser/PWA reconnect behavior after transient
  offline startup.
- Prevents revoked or removed members from reappearing in active member management, role, invite, and member-picker
  UI after refresh.

## Compatibility

- Shared Map protocol remains version `1`.
- Web Pack schema remains version `1`.
- Compatible Shared Map Server target is `0.3.1`; existing Protocol v1 clients remain compatible.
- Self-hosted distribution target is `1.1.1` with Flyway schema `4`.
- Desktop remains usable without the optional Shared Map Server.

## Acceptance scope

- Full JVM, JS Node, and Chrome browser suites pass with the real 103-link enabled Ansiblex Web Pack.
- Chrome desktop and 1280×800 / 800×1280 tablet automation covers compact accordion behavior, routes, PWA metadata,
  touch interaction, and offline reopen.
- Remembered-session tests cover IndexedDB restore and cleanup without placing Device Access Tokens in URLs,
  diagnostics, or logs.
