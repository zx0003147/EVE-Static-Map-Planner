# Feature Pack platform contract

Feature Packs are trusted, first-party, in-process JVM extensions for EVE Static Map Planner. Core owns the frozen
Feature API, the Host/runtime, installation and management infrastructure, generic presentation, and platform test
fixtures. Pack repositories own their data acquisition, domain behavior, and Pack-specific metadata.

The external `EVE-Sovereignty-Pack` repository is the first-party reference implementation. Core contains no
Sovereignty production module and a normal Core build does not require that repository.

## Contract identities and lifecycle

Feature API runtime compatibility and build-time artifact identity are deliberately separate:

```text
Runtime compatibility contract: EVE-Feature-API-Version: 1
Frozen: true
Maven artifact: dev.evestaticmapplanner:feature-api:1.0.0
Desktop application version: independent
```

`FeatureApiVersions.current()` is the sole runtime compatibility authority and returns
`FeatureApiVersion("1", true)`. A Pack should consume the Maven artifact as `compileOnly` and use the same coordinate
for tests. The Host remains the only runtime owner of Feature API classes.

Feature API v1 is restart-only. The Host discovers one `FeaturePackEntrypoint`, calls `start(context)`, and closes the
returned `FeaturePackSession` when the Pack is disabled or the application shuts down. Hot installation, hot reload,
and background-worker lifecycle contracts are not part of v1.

## Build artifact verification

Core's normal verification publishes Feature API `1.0.0` only to the generated, ignored
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
integer `1`. Missing, empty, zero, negative, non-integer, non-canonical, overflowing, or mismatched values are rejected.

Core reads and validates the manifest with Host/JDK code before creating a Pack ClassLoader. Only a compatible Pack
continues to isolated ClassLoader creation and `ServiceLoader` discovery. Invalid or incompatible Packs cannot load,
initialize, construct, or start Pack implementation code.

## Installation and discovery

On Windows, external Packs are discovered below:

```text
%LOCALAPPDATA%\EVE Static Map Planner\feature-packs\<pack-id>\pack.jar
```

The Feature Pack root is outside the application image and MSI installation directory. Installing a Pack therefore
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

Feature Packs are not a JVM security sandbox. Feature API v1 is a trusted first-party boundary that limits accidental
coupling; it does not make hostile code safe. Core internals, repositories, database connections, dependency-injection
containers, and arbitrary application services are not exposed through the API.

## PackStorage

The Host supplies Pack-scoped `data`, `config`, and `cache` path resolution through `PackStorage`. Relative paths use
the strict `PackRelativePath` contract. Packs must keep changing data in their own storage and must not access Core's
`static.db`, `user.db`, or application-data roots through hidden source or project dependencies.

## Overlay and System Info contributions

Feature API v1 lets Packs register display-neutral Overlay and structured System Info providers. The Pack owns its
provider IDs, layer IDs, records, labels, values, stable owner identity, and optional generic presentation metadata.
Core owns aggregation, visibility state, rendering, territory/emblem/legend presentation, and the application UI.

Core's generic presentation path may interpret optional metadata such as a stable owner key, a color hint, and an
emblem key/reference. These conventions do not teach Core about alliances or any Pack-specific domain. Expensive
presentation geometry, image loading, drawing order, and fallback behavior remain Host responsibilities.

When no Pack contributes data, Overlay and System Info aggregation remain empty and Core behaves normally.

## Pack Manager

The Pack Manager reads lightweight manifest metadata without loading Pack classes, reports installation and
compatibility state, persists enablement plus the latest lifecycle error, and isolates failures per Pack. Enablement
state is stored in `%LOCALAPPDATA%\EVE Static Map Planner\feature-pack-manager.properties`.

The manager does not download, publish, or silently enable Packs. Public installation/container UX and third-party
ecosystem policy remain deferred.

## First-party interoperability example

Sovereignty is maintained in the external Sovereignty Pack repository and demonstrates the v1 Overlay, System Info,
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

It requires clean `main` worktrees. The runner publishes Feature API `1.0.0` only to Core's generated test repository,
builds the standalone Sovereignty Pack by coordinate, clean-builds Core without the Pack, runs focused Host and
generic presentation regressions, supplies the canonical external JAR only to the explicit integration test, and
enforces the exactly 20-tool MCP catalog. It uses fixtures/LKG data instead of live ESI and performs no remote
publication.

Future GitHub cross-repository CI can reproduce those stages only after the Sovereignty remote exists, Feature API is
published, and package/repository permissions are configured. Normal Core CI intentionally has no Sovereignty
repository or artifact dependency.

## Deferred release work

No Feature API package, Pack, installer, tag, or release is published by this document or by normal CI. Final remote
links, package coordinates, credentials/permissions, and release workflows belong to a later authorized release phase.
The source license is still undecided, so public release remains blocked.
