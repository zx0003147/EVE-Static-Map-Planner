# Feature Pack developer contract

The Feature Pack API is a first-party extension boundary for EVE Static Map Planner. `:feature-api` contains only the
small lifecycle, identity, host-information, storage, logging, and compatibility contracts. FP-2A adds the isolated
local host in `:app`; FP-2B validates that the application-owned host works from the packaged production runtime.

## Status and lifecycle

- The compatibility authority is `FeatureApiVersions.current()`, which returns `FeatureApiVersion("1", true)`.
  Feature API contract version `1` is frozen after its final public-surface audit and production compatibility
  enforcement. The canonical manifest representation remains the positive decimal integer `1`.
- The v1 lifecycle is restart-only. Core discovers exactly one `FeaturePackEntrypoint` per
  Pack, calls `start(context)`, and closes the returned `FeaturePackSession` during application shutdown.
- FP-2A owns one isolated class loader per Pack, `ServiceLoader`, and lifecycle failure isolation. FP-2B adds the
  production directory resolver and installed-image lifecycle validation without changing those strategies.

## Feature API build artifact

Feature API runtime compatibility and Feature API artifact publication use separate version identities:

```text
Runtime compatibility contract: EVE-Feature-API-Version: 1
Maven artifact: dev.evestaticmapplanner:feature-api:1.0.0
Desktop application version: independent
```

Contract version `1` controls whether the Host may load a Pack. Artifact version `1.0.0` identifies the build-time
library revision used by a Pack compiler. The desktop application's release version has its own lifecycle and does not
set either Feature API identity. A Pack should consume the artifact as `compileOnly` and use the same coordinate for
its tests, so the Host remains the only runtime owner of Feature API classes.

The Core build can publish the artifact to the generated, ignored
`feature-api/build/test-maven-repository` and then compile an independent fixture by Maven coordinate. This verification
does not use Maven Local, GitHub credentials, a sibling checkout, or a Gradle project dependency. Normal `publish`
targets only this generated repository and has no remote side effect.

The Sovereignty Pack implementation is maintained in an external Sovereignty Pack repository. Core owns the frozen
Feature API, Host, manager, generic Overlay/System Info presentation, and generic fixture Pack; it neither contains nor
builds a `sovereignty-pack` Gradle module. A normal Core build needs no Sovereignty checkout or artifact. For explicit
cross-repository verification, first build the external repository's canonical `sovereignty.pack/pack.jar`, then run:

```powershell
.\gradlew.bat :app:test -PsovereigntyPackJar="C:\path\to\sovereignty.pack\pack.jar"
```

Without that property, `SovereigntyPackIntegrationTest` is excluded while all generic Host and fixture tests continue
to run. The absolute path is invocation-only and must not be committed. Sovereignty implementation and data-source
documentation belong to the external repository; this document records only Host-visible integration behavior.

## Cross-repository acceptance

Before a Core, Sovereignty Pack, or Feature API artifact release, run the Core-owned acceptance runner from a clean
`main` checkout of each repository and pass the standalone Sovereignty repository explicitly:

```powershell
.\scripts\acceptance-feature-pack.ps1 `
    -SovereigntyRepo "C:\path\to\EVE-Sovereignty-Pack"
```

The runner publishes Feature API `1.0.0` only to Core's generated test Maven repository, builds and verifies the
standalone thin Pack by Maven coordinates, clean-builds Core without the Pack, runs no-Pack/compatibility/Host and
generic presentation regressions, supplies the canonical external `pack.jar` only to the explicit integration test,
and enforces the 20-tool MCP catalog. It also checks both repositories for tracked source changes before and after the
run. The workflow uses fixture/LKG data rather than live ESI, performs no remote publication, and writes only ignored
Gradle build outputs.

GitHub Packages publication is prepared for a future authorized release, but no package has been published. Its
repository is registered only when `-PenableFeatureApiGitHubPackagesPublication=true` is supplied explicitly; only
that remote operation reads `GITHUB_ACTOR` and `GITHUB_TOKEN`. A future external Pack can optionally use a Gradle
composite build for local co-development by substituting
`dev.evestaticmapplanner:feature-api:1.0.0` with Core's `:feature-api` project. The consumer must opt into that composite
and supply its Core checkout path; Core does not require or hardcode a sibling repository.

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
must contain `EVE-Feature-Pack-Id`, `EVE-Feature-Pack-Name`, `EVE-Feature-Pack-Version`,
`EVE-Feature-Pack-Publisher`, and `EVE-Feature-API-Version`. The API value must be a canonical positive decimal integer
and must exactly match `FeatureApiVersions.current()`. Missing, empty, zero, negative, non-integer, non-canonical, or
overflowing values make the Pack metadata invalid. A different valid version makes the Pack incompatible. Both cases
are rejected before a Pack ClassLoader or `ServiceLoader` exists. The containing directory name must equal the Pack ID.
When an enabled compatible Pack is loaded, its runtime `FeaturePackDescriptor` must match the manifest identity values.
Enable state and the latest lifecycle error are stored in
`%LOCALAPPDATA%\EVE Static Map Planner\feature-pack-manager.properties`; no Pack database is created.

The production image contains the host and one shared `feature-api` identity. It does not contain Pack JARs, a Pack
copy of Kotlin stdlib, or another JVM/JRE.

## Class loading

Each external Pack has its own JAR class loader. Standard JDK classes are delegated through the normal bootstrap and
platform loader hierarchy, so a Pack can use JDK modules included in the packaged runtime. The application loader
remains the owner of the Feature API and shared Kotlin runtime, preserving one common contract identity. Pack-private
classes and dependencies stay in that Pack's loader, while application implementation packages are not exposed by the
shared parent boundary. A JDK API is available only when its module is present in the custom jlink runtime.

## Trust boundary

Feature Packs run in-process and are **not** a JVM security sandbox. The API limits accidental coupling; it does not
make hostile code safe. v1 is first-party-only.

Packs receive only Pack-scoped `data`, `config`, and `cache` path resolution. Core application roots, `static.db`,
`user.db`, database connections, repositories, dependency-injection containers, and arbitrary services are not
available. Relative paths use a strict portable syntax. The future host implementation must also defend against
symlink and Windows reparse-point escapes.

The production Sovereignty Pack uses the `PUBLIC_ESI` data-source mode and only anonymous public ESI operations:
`GET /sovereignty/systems` followed by `POST /universe/names` for owner-name resolution. It does not use OAuth, SSO,
a character token, or Character ESI. Each canonical sovereignty record contains a positive `allianceId`, resolved
`allianceName`, optional `corporationName`, and sovereignty status; `allianceId` is the stable owner identity.

The Pack stores its versioned, validated PUBLIC_ESI Last Known Good (LKG) snapshot through `PackStorage` at
`cache/public-esi-lkg.json`. This is a canonical snapshot cache, not a database or raw HTTP-response cache. During each
Pack startup it selects exactly one snapshot before registering Overlay and System Info providers. A valid cache whose
successful file modification time is no more than one hour old is considered fresh and avoids ESI entirely. This
one-hour threshold is a local Sovereignty Pack v1 product policy, not an official CCP freshness guarantee. The exact
one-hour boundary remains fresh, and a future file timestamp caused by a local clock adjustment is also treated as
fresh.

The current LKG format is v2 and retains the positive Public ESI `allianceId` beside the resolved display name. The
decoder still accepts structurally and semantically valid v1 files, whose historical format did not contain alliance
IDs. A v1 hit is logged explicitly and uses a deterministic legacy-name visual key until a later successful startup
refresh writes v2; missing identity is never invented or mistaken for a real ESI ID. New/fresh v2 caches recover the
same alliance-ID identity without contacting ESI.

A stale valid LKG remains the fallback while the Pack makes exactly one synchronous Public ESI refresh attempt. A
fully valid remote snapshot atomically replaces the LKG and becomes the session snapshot. If ESI is unavailable, the
remote result is invalid, or cache persistence fails, the old LKG is not deleted or touched; a valid remote snapshot
whose cache write fails is still used in memory for that Pack session. Missing or unusable caches also trigger exactly
one startup attempt, but malformed data is never used as a fallback and production never substitutes the `EMBEDDED`
fixture.

After startup registration, sovereignty data is fixed until the Pack or application restarts: there is no runtime
polling, live snapshot replacement, Overlay invalidation, background worker, scheduler, retry loop, or timer. Live
Pack refresh, an Overlay invalidation API, and background-worker lifecycle support remain deferred platform work and
are not Sovereignty Pack v1 requirements. The synchronous network attempt for stale, missing, or unusable caches is
an accepted v1 startup tradeoff.

## Sovereignty visual presentation

Sovereignty v1 has one presentation: a low-priority territory background. Visible generic owner seeds first form one
conservative supported-domain mask from `localSceneScale * 1.35` discs. After one-cell local closing, an exterior flood
classifies background connected to the mask's outside; only enclosed holes of at most 96 cells are repaired. Every
retained domain component must contain a real ownership seed. Cells outside that mask remain transparent true void
even when ordinary base-map systems exist there. All owners then compete in
one deterministic shared assignment field strictly inside the mask, so each supported cell has at most one owner and
interacting A/B support shares one border without a synthetic seam. Owner-density reinforcement and label smoothing
can change the winner but cannot expand the supported domain. Explicit Unknown/Unclaimed entries are ordinary seeds;
unsupported space is never synthesized as Unknown. Territory fills, enclave holes, and one neutral boundary graph are
then extracted from that same field. The shared boundary graph classifies assignment-to-background edges separately
from owner-to-owner edges: outer contours receive six constrained low-pass passes at neighbor weight 0.24, while
political seams retain three passes at 0.21. Both fills still reuse exactly the same shared vertex positions, so the
extra outer smoothing cannot create overlap, cracks, or double borders. Each seed first reserves
a protected core whose nominal radius is `localSceneScale * 0.28`. Rival cores that are too close shrink symmetrically
to half their separation minus grid clearance and epsilon; protected cells constrain label cleanup, and candidate
boundary-smoothing moves that enter a core are rejected. Boundary vertices are smoothed once and reused by every
adjacent owner, preventing independently processed contours from producing cracks, overlaps, or double-stroked color
mixing. Per-system Sovereignty rings are not rendered.

Territory metadata carries a Pack-owned stable owner key based on `allianceId` plus a deterministic base color. Generic
ID colors use HSL saturation 0.68–0.78 and lightness 0.60–0.66; explicit Unknown/Unclaimed ownership remains neutral
gray, while the small Goonswarm and Fraternity ID overrides remain recognizable. During cached presentation
preparation, a bounded deterministic neighbor pass adjusts only owners whose normalized RGB distance is below 0.24.
Core does not understand alliances, sovereignty ownership, or Pack-specific layer IDs, and owner grouping never
depends on presentation color. For owners with an alliance ID, the Pack also projects a generic emblem key and the
official `https://images.evetech.net/alliances/{allianceId}/logo?size=256` reference through the existing text metadata
channel. The host creates at most one candidate per connected component only when it contains at least four owner
systems, 80 assignment cells, and a usable anchor at least two cell layers inside the boundary. Anchor selection is
performed once with cached presentation geometry: an owned, sufficiently clear centroid cell wins; otherwise a
deterministic 0.65 centrality / 0.35 boundary-clearance score selects a safe interior cell. Projection-stable canonical
region anchors exclude a conservative label rectangle of 4.5–8.0 cells horizontally (scaled by region-name length)
and 3.25 cells vertically. A component with no boundary-safe, label-safe cell is suppressed. Pan and zoom therefore
never select or rebuild an anchor.

The host loads qualifying alliance emblems asynchronously from `images.evetech.net`. Its bounded, session-only memory
cache deduplicates in-flight requests and remembers failures for the session; there is no disk image cache. Watermark
sizing follows projected component area and bounds, uses a 72 px preferred minimum and 272 px maximum, and remains
capped by the fixed anchor's shared-field boundary clearance. Image completion changes only image presentation state;
it does not invalidate or rebuild political territory geometry. Ownership colors never depend on logo availability.

`Sovereignty Logo Emphasis Zoom` is persisted in the existing `settings.properties` preferences. The Overlays page
shows this Sovereignty subsection only while an available Overlay/provider exposes the Sovereignty capability. The
user-selected threshold `T` accepts the full map zoom range from 0.01x through 250x and defaults to 0.75x. At zooms at
or below `T`, emblems use the high-visibility overview treatment, reaching a maximum alpha of 0.94 and receiving a
small size boost. Above `T`, they transition into a lower-alpha background watermark and become fully hidden at about
`8 / 3 × T`. Choosing 250x therefore keeps logos in the emphasized treatment across the application's practical zoom
range; there is no fixed 2.0x hide cutoff. Zoom changes only recompute lightweight emblem placement, alpha, and size.

Territory presentation geometry and stable emblem anchors are cached outside the hot draw path. Pan, zoom, emblem
image completion, and changes to the emblem threshold preference do not rebuild territory geometry. The same cached
presentation path supports both `OFFICIAL_2D` and `REAL_XZ` projections.

On Windows/Skia, emblem clipping intersects the outer territory contour first, then applies every enclave or territory
hole as an individual `ClipOp.Difference`. A combined EvenOdd `clipPath` is not used because it can produce an empty
effective clip on that backend. Territory polygon filling may still use the EvenOdd fill rule; fill construction and
the nested emblem clip operations are deliberately separate.

The generic Feature Overlay legend is collapsed by default and expands or collapses when its header is clicked. A
single section uses that section's title; multiple sections use `Map overlays` and retain their individual section
titles. This behavior is host-owned and is not specific to the Sovereignty Pack.

Saved Marker outer rings and the Selected System ring remain separate Core-owned marker and interaction visuals. They,
along with routes, hover state, system nodes, and labels, render above territory and are not recolored by it. There is
no Rings/Territory mode selector.

## Deliberate exclusions

The API does not expose Compose UI, Canvas/rendering types, coroutines or scopes, Core domain models, arbitrary
registration, HTTP, native/JNI dependencies, Character ESI, or MCP. AI/MCP remains entirely Core-owned. Overlay and
structured System Info contracts will be designed only when their real consumers are implemented in OV-1 and OV-3.
