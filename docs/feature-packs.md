# Feature Pack developer contract

The Feature Pack API is a first-party extension boundary for EVE Static Map Planner. FP-1 establishes only the
small lifecycle, identity, host-information, storage, logging, and compatibility contracts in `:feature-api`.
Pack discovery and loading do not exist yet.

## Status and lifecycle

- The API remains pre-release and unfrozen through FP-1, OV-1, and OV-3. `FeatureApiVersions.current()` therefore
  returns an explicitly non-frozen development identifier; it is not Feature API version 1.
- The planned v1 lifecycle is restart-only. Core will eventually discover exactly one `FeaturePackEntrypoint` per
  Pack, call `start(context)`, and close the returned `FeaturePackSession` during application shutdown.
- FP-2 owns isolated class loaders, `ServiceLoader`, discovery, failure isolation, and packaged-runtime validation.

## Trust boundary

Feature Packs run in-process and are **not** a JVM security sandbox. The API limits accidental coupling; it does not
make hostile code safe. v1 is first-party-only.

Packs receive only Pack-scoped `data`, `config`, and `cache` path resolution. Core application roots, `static.db`,
`user.db`, database connections, repositories, dependency-injection containers, and arbitrary services are not
available. Relative paths use a strict portable syntax. The future host implementation must also defend against
symlink and Windows reparse-point escapes.

## Deliberate exclusions

The API does not expose Compose UI, Canvas/rendering types, coroutines or scopes, Core domain models, arbitrary
registration, HTTP, native/JNI dependencies, Character ESI, or MCP. AI/MCP remains entirely Core-owned. Overlay and
structured System Info contracts will be designed only when their real consumers are implemented in OV-1 and OV-3.
