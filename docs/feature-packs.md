# Feature Pack platform contract

Feature Packs are trusted, first-party, in-process JVM extensions for EVE Static Map Planner. Core owns the frozen
Feature API, the Host/runtime, installation and management infrastructure, generic presentation, and platform test
fixtures. Pack repositories own their data acquisition, domain behavior, and Pack-specific metadata.

The external `EVE-Sovereignty-Pack` and `EVE-ESI-Pack` repositories are first-party implementations. Core contains no
Pack-specific production module and a normal Core build does not require either repository.

## Contract identities and lifecycle

Feature API runtime compatibility and build-time artifact identity are deliberately separate:

```text
Runtime compatibility contract: EVE-Feature-API-Version: 2
Frozen: true
Maven artifact: dev.evestaticmapplanner:feature-api:2.0.0
Desktop application version: independent
```

`FeatureApiVersions.current()` is the sole runtime compatibility authority and returns
`FeatureApiVersion("2", true)`. A Pack should consume the Maven artifact as `compileOnly` and use the same coordinate
for tests. The Host remains the only runtime owner of Feature API classes.

Feature API v2 is restart-only. The Host discovers one `FeaturePackEntrypoint`, calls `start(context)`, and closes the
returned `FeaturePackSession` when the Pack is disabled or the application shuts down. Hot installation, hot reload,
and background-worker lifecycle contracts are not part of v2.

## Build artifact verification

Core's normal verification publishes Feature API `2.0.0` only to the generated, ignored
`feature-api/build/test-maven-repository`. The verification tasks inspect the artifact and compile an independent thin
fixture Pack by Maven coordinate:

```powershell
.\gradlew.bat :feature-api:test `
    :feature-api:verifyFeatureApiPublication `
    :feature-api:verifyFeatureApiCoordinateConsumer
```

This local verification does not use Maven Local, GitHub credentials, a sibling checkout, or a Gradle project
dependency. Remote Feature API publication is a separate, explicit future release operation and is disabled by
default.

## Manifest and compatibility gate

Every `pack.jar` must contain these standard manifest attributes:

- `EVE-Feature-Pack-Id`
- `EVE-Feature-Pack-Name`
- `EVE-Feature-Pack-Version`
- `EVE-Feature-Pack-Publisher`
- `EVE-Feature-API-Version`

The containing directory name must equal the Pack ID. `EVE-Feature-API-Version` must be the canonical positive decimal
integer `2`. Missing, empty, zero, negative, non-integer, non-canonical, overflowing, or mismatched values are rejected.

Core reads and validates the manifest with Host/JDK code before creating a Pack ClassLoader. Only a compatible Pack
continues to isolated ClassLoader creation and `ServiceLoader` discovery. Invalid or incompatible Packs cannot load,
initialize, construct, or start Pack implementation code.

## Installation and discovery

On Windows, external Packs are discovered below:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\<pack-id>\pack.jar
```

The Feature Pack root is outside the Portable application image. Installing a Pack therefore
does not mutate application binaries. Core discovers prebuilt JARs; it does not build Pack source as part of the
desktop application.

An absent `feature-packs` directory is the normal no-Pack state. Discovery does not create that directory, Pack
storage, databases, ClassLoaders, workers, or network activity. Newly discovered Packs are disabled by default. A bad
Pack is reported and skipped without preventing other compatible Packs or Core from running.

## ClassLoader and trust boundary

Each enabled compatible Pack receives an isolated JAR ClassLoader. The application ClassLoader owns the shared Feature
API and Kotlin runtime; Pack-private implementation classes and dependencies remain inside the Pack loader. The
production image does not bundle external Pack JARs, duplicate Feature API classes, a Pack copy of Kotlin stdlib, or a
second JVM.

Feature Packs are not a JVM security sandbox. Feature API v2 is a trusted first-party boundary that limits accidental
coupling; it does not make hostile code safe. Core internals, repositories, database connections, dependency-injection
containers, and arbitrary application services are not exposed through the API.

## PackStorage

The Host supplies Pack-scoped `data`, `config`, and `cache` path resolution through `PackStorage`. Relative paths use
the strict `PackRelativePath` contract. Packs must keep changing data in their own storage and must not access Core's
`static.db`, `user.db`, or application-data roots through hidden source or project dependencies.

## Overlay and System Info contributions

Feature API v2 preserves the V1 display-neutral Overlay and structured System Info provider contracts. The Pack owns its
provider IDs, layer IDs, records, labels, values, stable owner identity, and optional generic presentation metadata.
Core owns aggregation, visibility state, rendering, territory/emblem/legend presentation, and the application UI.

Core's generic presentation path may interpret optional metadata such as a stable owner key, a color hint, and an
emblem key/reference. These conventions do not teach Core about alliances or any Pack-specific domain. Expensive
presentation geometry, image loading, drawing order, and fallback behavior remain Host responsibilities.

When no Pack contributes data, Overlay and System Info aggregation remain empty and Core behaves normally.

## Optional capabilities and Route Actions

Feature API v2 adds narrow capability discovery through `FeaturePackContext.capabilities()`. Its safe default is an
empty lookup, so existing context implementations and Packs do not need mechanical lifecycle changes. Capability
matching uses both a canonical ID and the expected Java type; it is not a general service locator.

The frozen standard keys are `dynamic-overlay`, `route-action`, and `pack-controls`. Dynamic Overlay providers continue to return
immutable display-neutral snapshots; `requestRefresh()` only signals that the Host should re-read one provider.
Overlay entries may optionally carry a bounded generic image marker anchored to a system; Packs own image acquisition and
caching while the Host owns decoding, sector composition, pin rendering, and hover presentation. Route Actions receive a
defensive, immutable `RouteSnapshot`, may expose a shared generic target selector, and return a synchronous display-neutral result.
Selected target IDs are opaque Pack-owned values and remain Host-persisted planning state. Neither
contract exposes Compose, coroutines, coordinates, ViewModels, executors, Core route objects, database models, Control
DTOs, ESI, OAuth, or HTTP client types. Pack Controls expose only a cheap synchronous status snapshot, generic action
descriptors, safe action results, and provider-targeted `requestRefresh()`; they cannot contribute arbitrary Compose UI.

The production runtime-contract-2 Host supplies all standard capabilities as Pack-scoped objects. A Dynamic Overlay
registration stores one validated last-good contribution; `requestRefresh()` schedules only that provider on a
bounded Host executor, coalesces repeated requests, retains last-good data after failures, and never polls. Static
Overlay providers use the same cached aggregation path and are not re-snapshotted when a dynamic provider refreshes.

Route Actions are registered under Pack ID plus action ID, filtered by the active route kind, and rendered as generic
buttons in the normal or capital active-route panel. The Host captures an immutable route snapshot, prevents duplicate
invocation while an action is busy, and executes Pack callbacks on a bounded background executor. Success, rejection,
and failure messages remain generic Host presentation data. Mission route snapshots are supported by the Host adapter,
but Mission overlays currently have no interactive route panel, so Mission Route Actions are not displayed.

Pack Controls are rendered under Preferences → Feature Packs. The Host executes actions on a bounded background
executor, disables a provider's actions while one is busy, isolates snapshot/action failures, and suppresses late
results after Pack close. A Pack without this capability retains the previous management UI unchanged.

Disabling a Pack closes capability registrations before its existing System Info/Overlay registrations and before its
ClassLoader. Pending work is cancelled where possible, late results are ignored, and shutdown uses bounded waits. An
empty Host starts no worker, performs no polling, and adds no UI controls. Feature API v2 contains no ESI, OAuth, token,
character-location, or waypoint implementation.

## Pack Manager

The Pack Manager reads lightweight manifest metadata without loading Pack classes, reports installation and
compatibility state, persists enablement plus the latest lifecycle error, and isolates failures per Pack. Enablement
state is stored in `%LOCALAPPDATA%\EVE Static Map Planner\feature-pack-manager.properties`.

The manager does not download, publish, or silently enable Packs. Canonical first-party Pack locations are:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\esi.pack\pack.jar
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\sovereignty.pack\pack.jar
```

## First-party interoperability example

Sovereignty is maintained in the external Sovereignty Pack repository and demonstrates the preserved Overlay, System Info,
PackStorage, manifest, and lifecycle contracts. Its PUBLIC_ESI acquisition, Last Known Good cache, ownership model,
territory metadata, visual identity, emblem metadata, and startup freshness behavior are documented and tested in
that repository rather than duplicated here.

For an explicit integration test, build the external repository's canonical
`build/external-feature-pack/sovereignty.pack/pack.jar`, then run:

```powershell
.\gradlew.bat :app:test -PsovereigntyPackJar="C:\path\to\sovereignty.pack\pack.jar"
```

Without the property, the external Sovereignty integration test is excluded while generic Host, compatibility,
ClassLoader, manager, no-Pack, Overlay, System Info, and fixture tests continue to run.

## Cross-repository acceptance

The authoritative local cross-repository acceptance mechanism is Core's existing runner:

```powershell
.\scripts\acceptance-feature-pack.ps1 `
    -SovereigntyRepo "C:\path\to\EVE-Sovereignty-Pack"
```

It requires clean `main` worktrees. The runner publishes Feature API `2.0.0` only to Core's generated test repository,
builds the standalone Sovereignty Pack by coordinate, clean-builds Core without the Pack, runs focused Host and
generic presentation regressions, supplies the canonical external JAR only to the explicit integration test, and
enforces the exactly 22-tool MCP catalog. It uses fixtures/LKG data instead of live ESI and performs no remote
publication. The external Pack must first update only its Feature API coordinate, manifest, and version expectations
to runtime contract 2; Feature API v2 deliberately preserves its existing business Kotlin contracts.

Future GitHub cross-repository CI can reproduce those stages only after the Sovereignty remote exists, Feature API is
published, and package/repository permissions are configured. Normal Core CI intentionally has no Sovereignty
repository or artifact dependency.

## Deferred release work

No Feature API package, Pack, installer, tag, or release is published by this document or by normal CI. Final remote
links, package coordinates, credentials/permissions, and release workflows belong to a later authorized release phase.
The source license is still undecided, so public release remains blocked.
