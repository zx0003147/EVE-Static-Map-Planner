# Feature API 2.0.0 final pre-release audit

Feature API runtime `2` / artifact `2.0.0` remains the final contract identity. Before its first formal publication,
the View and multi-session ESI design exposed two generic gaps in the otherwise frozen surface:

- Route Actions could not describe or receive a user-selected execution target.
- Overlay entries could not carry Pack-owned image content for a Host-rendered system marker.

The final contract completion adds generic target snapshots and opaque selected target IDs to Route Actions, plus bounded
encoded images and non-interactive system marker metadata to Overlay entries. The API contains no EVE, ESI, OAuth,
character, token, Compose, Core, HTTP, database, or MCP types. Packs continue to own data acquisition and caches; the Host
continues to own selection persistence, rendering, scheduling, and UI.

Runtime compatibility stays `2`, the Maven artifact stays `2.0.0`, and this completed surface is frozen. Any later breaking
change requires a new runtime and artifact major version.
