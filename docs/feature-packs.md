# Feature Pack developer contract

The Feature Pack API is a first-party extension boundary for EVE Static Map Planner. `:feature-api` contains only the
small lifecycle, identity, host-information, storage, logging, and compatibility contracts. FP-2A adds the isolated
local host in `:app`; FP-2B validates that the application-owned host works from the packaged production runtime.

## Status and lifecycle

- The API remains pre-release and unfrozen through FP-1, OV-1, and OV-3. `FeatureApiVersions.current()` therefore
  returns an explicitly non-frozen development identifier; it is not Feature API version 1.
- The planned v1 lifecycle is restart-only. Core will eventually discover exactly one `FeaturePackEntrypoint` per
  Pack, call `start(context)`, and close the returned `FeaturePackSession` during application shutdown.
- FP-2A owns one isolated class loader per Pack, `ServiceLoader`, and lifecycle failure isolation. FP-2B adds the
  production directory resolver and installed-image lifecycle validation without changing those strategies.

## Production discovery location

On Windows, external Packs are discovered below:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\<pack-id>\pack.jar
```

This location is user-writable for a normal per-user installation, remains outside the jpackage/MSI application
image, and therefore does not require administrator rights or mutate signed installation files. A future Feature Pack
installer can create one child directory and place its `pack.jar` there after showing the user an installation review.
FP-2B deliberately does not define that public install/container format or add a manager UI.

The application treats an absent `feature-packs` directory as the normal no-Pack state. Discovery does not create the
directory, Pack storage, worker threads, databases, or network activity. Newly discovered Packs are disabled by
default. A bad Pack is reported and skipped while Core continues; successfully started Packs are closed when disabled
or during application shutdown.

FP-3 reads lightweight management metadata from the standard JAR manifest without loading Pack classes. `pack.jar`
must contain `EVE-Feature-Pack-Id`, `EVE-Feature-Pack-Name`, `EVE-Feature-Pack-Version`, and
`EVE-Feature-Pack-Publisher`. The containing directory name must equal the Pack ID. When an enabled Pack is loaded, its
runtime `FeaturePackDescriptor` must match these values. Enable state and the latest lifecycle error are stored in
`%LOCALAPPDATA%\EVE Static Map Planner\feature-pack-manager.properties`; no Pack database is created.

The production image contains the host and one shared `feature-api` identity. It does not contain Pack JARs, a Pack
copy of Kotlin stdlib, or another JVM/JRE.

## Trust boundary

Feature Packs run in-process and are **not** a JVM security sandbox. The API limits accidental coupling; it does not
make hostile code safe. v1 is first-party-only.

Packs receive only Pack-scoped `data`, `config`, and `cache` path resolution. Core application roots, `static.db`,
`user.db`, database connections, repositories, dependency-injection containers, and arbitrary services are not
available. Relative paths use a strict portable syntax. The future host implementation must also defend against
symlink and Windows reparse-point escapes.

The production Sovereignty Pack stores its versioned, canonical PUBLIC_ESI Last Known Good (LKG) snapshot at the
Pack-relative cache path `public-esi-lkg.json`. During each Pack startup it selects exactly one snapshot before
registering Overlay and System Info providers. A valid cache whose successful file modification time is no more than
one hour old is considered fresh and avoids ESI entirely. This one-hour threshold is a local Sovereignty Pack v1
product policy, not an official CCP freshness guarantee. The exact one-hour boundary remains fresh, and a future file
timestamp caused by a local clock adjustment is also treated as fresh.

A stale valid LKG remains the fallback while the Pack makes exactly one synchronous Public ESI refresh attempt. A
fully valid remote snapshot atomically replaces the LKG and becomes the session snapshot. If ESI is unavailable, the
remote result is invalid, or cache persistence fails, the old LKG is not deleted or touched; a valid remote snapshot
whose cache write fails is still used in memory for that Pack session. Missing or unusable caches also trigger exactly
one startup attempt, but malformed data is never used as a fallback and production never substitutes the embedded
fixture.

After startup registration, sovereignty data is fixed until the Pack or application restarts: there is no runtime
polling, live snapshot replacement, Overlay invalidation, background worker, scheduler, retry loop, or timer. Live
Pack refresh, an Overlay invalidation API, and background-worker lifecycle support remain deferred platform work and
are not Sovereignty Pack v1 requirements. The synchronous network attempt for stale, missing, or unusable caches is
an accepted v1 startup tradeoff.

## Deliberate exclusions

The API does not expose Compose UI, Canvas/rendering types, coroutines or scopes, Core domain models, arbitrary
registration, HTTP, native/JNI dependencies, Character ESI, or MCP. AI/MCP remains entirely Core-owned. Overlay and
structured System Info contracts will be designed only when their real consumers are implemented in OV-1 and OV-3.
