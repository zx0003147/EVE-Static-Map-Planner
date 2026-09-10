# EVE Static Map Planner 1.10.0

This release unifies structure-shaped system nodes across Saved and Shared Markers on the Desktop and Web maps.

## Highlights

- Adds Fortizar system-node support for Saved and Shared Markers.
- Resolves Saved and Shared Marker tags through one `KEEPSTAR > FORTIZAR > SYSTEM` priority rule, so each system is
  drawn exactly once with its final node shape.
- Applies the same Keepstar and Fortizar semantics to Desktop Official 2D, Desktop Real 3D, and Web Canvas rendering.
- Preserves each structure node's Marker color while hover and selection brighten that color without increasing the
  normal structure outline width.
- Keeps ordinary system hover yellow and selection green while outlining the final structure shape directly.
- Fixes structure nodes drifting away from their systems in Real 3D after camera or viewport movement.

## Compatibility

- Shared Map protocol remains version `1`; `fortizar` is an ordinary protocol tag and requires no Server schema
  migration.
- Web Pack schema remains version `1`.
- Compatible Shared Map Server target remains `0.3.1` with Flyway schema `4`.
- Self-hosted distribution target is `1.2.0` and continues to pin immutable Server and Ops image digests.
- Feature API compatibility remains artifact `2.2.0`, runtime family `2`, and EVE ESI Pack `1.2.0`.
- Desktop remains usable without the optional Shared Map Server.

## Acceptance scope

- Desktop Official 2D, Real 3D, and Web structure-node behavior passed product UI acceptance.
- JVM and JavaScript tests cover Saved/Shared priority, Fortizar and Keepstar presentation, interaction color
  emphasis, and unchanged normal structure outline width.
- The Windows x64 Portable ZIP and self-hosted Web distribution are built through the existing audited release tasks.
