# Feature API final audit and FP-COMPAT-1

## Scope and result

This audit reviewed the current `:feature-api` surface, the external Pack manifest and loader, the shared parent
ClassLoader, `ServiceLoader` discovery, lifecycle and storage boundaries, Overlay and System Info registration,
Feature Pack manager status, Preferences presentation, and the now-external first-party Sovereignty Pack.

The current public surface is sufficient for the completed first-party Pack architecture and the near-term external
repository split. No public class, method, field, or interface signature was added or changed. The approved
public-contract normalization established version `1`, and the completed audit plus FP-COMPAT-1 enforcement now make
`FeatureApiVersions.current()` return `FeatureApiVersion("1", true)`. Feature API contract version `1` is frozen.

## Current v1 surface

- Identity and compatibility: `PackId`, `PackVersion`, `FeaturePackDescriptor`, `CoreVersion`, `FeatureApiVersion`,
  `FeatureApiVersions`, `FeaturePackCompatibility`, `FeaturePackHostInfo`, and `HostPlatform`.
- Lifecycle: `FeaturePackEntrypoint`, `FeaturePackContext`, `FeaturePackSession`, and
  `FeaturePackStartupException`.
- Host capabilities: `PackStorage`, `PackRelativePath`, `FeaturePackLogger`, and `FeaturePackLogLevel`.
- Overlay contribution: provider, descriptor, layer, entry, snapshot, registry, registration, and aggregate state
  contracts. Pack data remains display-neutral and rendering remains Host-owned.
- System Info contribution: provider, descriptor, field, section, snapshot, registry, registration, and aggregate
  state contracts.

The API boundary test owns the explicit public type set and rejects forbidden application, Core, Compose, coroutine,
database, and MCP signature dependencies.

## Audit classification

### A. Must fix before v1 freeze

The production external Pack path previously did not serialize or enforce the existing Feature API compatibility
identity. An older Host could therefore create a Pack ClassLoader and let `ServiceLoader` instantiate a newer Pack
before discovering a linkage failure. FP-COMPAT-1 fixes this by requiring and validating manifest compatibility
metadata before implementation loading.

The approved authority normalization from the development token to positive decimal `1` was also required so the
existing `FeatureApiVersions` / `FeatureApiVersion` model remains the single compatibility authority. No Host-only
version constant or competing compatibility system was added.

### B. Can defer without breaking v1

- `OverlayRegistration.refresh()` and live Overlay invalidation.
- Sovereignty polling, background refresh, schedulers, and Pack lifecycle workers.
- Hot install, uninstall, or reload without restart.
- Third-party Pack ecosystem and remote publication of the prepared Feature API artifact.
- Security sandboxing. Feature Packs remain trusted first-party in-process JVM extensions.

### C. Not a public API issue

- Manifest parsing and invalid/incompatible status are Host implementation concerns.
- Manager and Preferences error presentation can remain application-private.
- ClassLoader creation order, ServiceLoader isolation, and per-Pack fault containment are Host responsibilities.
- Sovereignty presentation metadata conventions remain Pack/Host implementation details; they do not require a new
  rendering or domain API.

## Compatibility policy

```text
Feature API contract version: 1
Frozen: true
Compatibility manifest field: EVE-Feature-API-Version: 1
Validation: before Pack ClassLoader creation
```

`FeatureApiVersions.current()` is the sole Host authority. The current value has identifier `1` and `frozen=true`.
Every external Pack JAR must declare:

```text
EVE-Feature-API-Version: 1
```

The value is parsed as a canonical positive base-10 integer. Leading zeroes, an empty value, zero, negative values,
non-integers, and values outside the JVM `Int` range are invalid metadata. A valid value that differs from the Host
authority is incompatible. There is no implicit legacy default and no semantic-version range solver.

| Host | Pack declaration | Result |
| --- | --- | --- |
| 1 | 1 | Compatible; continue loading |
| 1 | 2 | Incompatible; reject before ClassLoader creation |
| 1 | Missing | Invalid metadata; reject |
| 1 | Empty, zero, negative, non-integer, non-canonical, or overflow | Invalid metadata; reject |
| 1 | Malformed JAR or manifest | Invalid Pack; isolate the failure |

## Loader validation order

The production order is:

```text
discover pack.jar
  -> open JAR and read manifest with Host/JDK code
  -> validate Pack identity and descriptive metadata
  -> parse and validate EVE-Feature-API-Version
  -> compare it with FeatureApiVersions.current()
  -> only when compatible, create the Pack URLClassLoader
  -> invoke ServiceLoader
  -> instantiate the single FeaturePackEntrypoint
  -> read its runtime descriptor, create context, and start
```

An incompatible or metadata-invalid Pack creates no Pack ClassLoader, invokes no `ServiceLoader`, and does not load,
initialize, construct, or start Pack implementation code. Automated coverage uses both a ClassLoader factory counter
and a fixture entrypoint with static-initialization and constructor probes.

## Failure and isolation semantics

Valid but mismatched API metadata produces the internal `INCOMPATIBLE_FEATURE_API` failure and an `Incompatible`
manager status with both required and provided versions. Missing or malformed compatibility metadata remains an
invalid Pack with a specific manifest diagnostic. Raw stack traces are not the primary manager message.

Discovery and startup continue after each failure. Compatible Packs still load when another Pack is incompatible or
malformed. The no-Pack startup fast path remains unchanged: it creates no Pack directory or storage, creates no Pack
ClassLoader, invokes no external `ServiceLoader`, starts no Pack worker, and performs no Pack network operation.

Compatibility validation does not alter the shared parent ClassLoader. JDK platform modules and shared Feature API /
Kotlin identities remain available, application internals remain hidden, and Pack-private classes remain isolated per
Pack ClassLoader.

## Sovereignty Pack and future evolution

The canonical Sovereignty Pack build writes `EVE-Feature-API-Version: 1`; developers do not patch the artifact by
hand. Its PUBLIC_ESI/LKG selection, data schema, territory geometry, emblem presentation, conditional Preferences UI,
and restart-only lifecycle are unchanged.

Feature API v1 is now a stable binary/source contract and should not be broken casually. A breaking public API change
requires a new major contract identity such as version `2`. Backward-compatible additions still require deliberate
review so they do not silently invalidate existing v1 Packs. The frozen flag does not prevent the wider Feature Pack
platform from evolving. Core can produce the build-time artifact
`dev.evestaticmapplanner:feature-api:1.0.0` in an ignored test Maven repository without changing the runtime contract
`EVE-Feature-API-Version: 1`. Remote GitHub Packages publication remains an explicit future release operation; no
package has been uploaded. The Sovereignty implementation now belongs to an external repository, while Core retains
the frozen API and generic Host infrastructure. This does not define third-party compatibility promises.
