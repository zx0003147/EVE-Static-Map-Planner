# Feature API family 2 release and compatibility audit

Feature API artifact `2.0.0` was treated as a released, frozen baseline by release notes, distribution checks, and
external Pack guidance. It must therefore remain an immutable artifact identity. Runtime compatibility and Maven
artifact release identity are separate: `EVE-Feature-API-Version: 2` is the compatibility family, while `2.0.0` and
`2.1.0` are artifact releases within that family.

Before the `2.0.0` baseline, the View and multi-session ESI design exposed two generic gaps in the surface:

- Route Actions could not describe or receive a user-selected execution target.
- Overlay entries could not carry Pack-owned image content for a Host-rendered system marker.

The final contract completion adds generic target snapshots and opaque selected target IDs to Route Actions, plus bounded
encoded images and non-interactive system marker metadata to Overlay entries. The API contains no EVE, ESI, OAuth,
character, token, Compose, Core, HTTP, database, or MCP types. Packs continue to own data acquisition and caches; the Host
continues to own selection persistence, rendering, scheduling, and UI.

Those additions formed the `2.0.0` baseline. Phase 5C later adds `NavigationSnapshot`, `NavigationActionContext`, and
the optional `NavigationRouteActionProvider` subinterface. It does not add a member to `RouteActionProvider`, change
an existing constructor or method descriptor, or require an existing provider to implement a new interface. The
change is therefore source- and binary-additive and is released as Maven artifact `2.1.0`.

Runtime compatibility remains family `2`; the Host still compares only the manifest compatibility family before Pack
class loading. A Pack compiled against artifact `2.0.0` and a Pack compiled against `2.1.0` both declare
`EVE-Feature-API-Version: 2` and may load in the same Host. The `frozen=true` runtime value means family `2` is a stable
compatibility identity and that breaking changes require a new runtime family. It does not prohibit backward-compatible
minor artifact releases.
