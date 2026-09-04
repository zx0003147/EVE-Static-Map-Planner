# EVE Static Map Planner 1.3.1

This patch release preserves the accepted 1.3.0 UI, map, routing, and data behavior while improving interactive
startup.

## Startup

- Moved noncritical startup work off the first-map critical path.
- Avoided duplicate user-database initialization while preserving the existing failure behavior.
- Deferred dynamic Feature Pack refresh work until after the base map is displayed.

## Compatibility

- Feature API runtime compatibility remains family `2`; its current Maven artifact remains `2.1.0`.
- EVE ESI Pack remains `1.1.0` and EVE Map Assistant remains `0.7.0`; neither changed for this startup patch.
- Sovereignty Pack `0.2.1` supplies the matching background-refresh lifecycle fix and continues to require Feature
  API family `2`.
