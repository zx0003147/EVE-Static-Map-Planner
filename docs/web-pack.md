# Web Pack Phase 1

## Purpose and ownership

The Web Pack is the publication boundary between the Desktop application and a future browser client. Desktop remains the only data-maintenance application:

```text
CCP SDE -> Desktop SDE pipeline -> static.db -> Desktop runtime
user.db -> Desktop Ansiblex manager -> currently enabled Ansiblex
Desktop runtime data + enabled Ansiblex -> WebPackExporter -> deployable /data files
browser -> manifest.json -> versioned Web Pack
```

The exporter reads the same validated `StaticMapRepository` used by the running map and the same `AnsiblexRepository` used by route planning. It does not parse a CCP SDE archive and it has no database write operation. A failed export leaves `static.db` and `user.db` unchanged.

Phase 1 provides the publication mechanism and a minimal loader only. It does not implement a Web map, route UI, Capital/Jump Range/Coverage UI, Waypoint UI, PWA, server API, or account system.

## Format

An export creates this directory inside the parent selected in Preferences:

```text
EVE-Web-Pack/
|-- manifest.json
`-- web-pack-<packVersion>.json.gz
```

`manifest.json` is UTF-8 JSON. The pack is UTF-8 JSON compressed with standard gzip. JSON keeps the schema inspectable and straightforward to consume in Kotlin and JavaScript; gzip materially reduces transfer size without introducing a custom binary protocol. Modern browser code can decode it with `DecompressionStream("gzip")`.

The server must serve the `.json.gz` file as `application/gzip` or `application/octet-stream` without a `Content-Encoding` header. The loader verifies the compressed size and SHA-256 itself before decompressing it. Setting `Content-Encoding: gzip` would make the browser transparently decompress the response and invalidate those checks.

## Version and compatibility model

Phase 1 uses `schemaVersion = 1`. The Kotlin codec and minimal JavaScript loader accept exactly the schema version they support and reject any other value. A future incompatible shape requires a new schema version and an explicit loader implementation for it.

Each export creates a `packVersion` with this shape:

```text
<sdeBuild>-<UTC timestamp>-<12-character content digest prefix>
```

The SDE build identifies the static source generation. The timestamp makes successive exports distinguishable when Ansiblex changes without an SDE update. The digest prefix is derived from schema version, SDE build, Desktop version, generation time, and canonical payload JSON. The full SHA-256 in the manifest covers the final compressed file.

The pack document contains:

- `schemaVersion`
- `packVersion`
- `generatedAt` as an ISO-8601 UTC instant
- `desktopAppVersion`
- `sdeBuild`
- `counts`
- `payload`

`counts` contains `systems`, `stargateLinks`, `regions`, `constellations`, and `ansiblexLinks`. Counts must exactly match the payload collections.

## Payload schema

### Solar systems

Each `payload.systems` item contains:

- `id`
- `name`
- `regionId`
- `constellationId`
- `securityStatus`
- `universePosition`: `x`, `y`, and `z` in the current SDE universe coordinate unit
- `officialPosition`: nullable `x` and `y` from the current Official 2D layout
- `effectiveWormholeClassId`: nullable effective classification inherited from system, constellation, or region

These fields are the union consumed by the current Desktop core features that Phase 2 may port:

- Official 2D rendering uses `officialPosition`.
- Real 3D and the former REAL_XZ-compatible geometry use `universePosition`.
- system search and System Info use ID, name, hierarchy, and security.
- Jump Range, Capital Route, and Capital Coverage use XYZ, security, system ID, and `effectiveWormholeClassId` for the current eligibility rules.

Official positions remain nullable because the current validated Desktop model allows systems without an Official 2D position and records them as omitted from that projection.

### Stargates

Each `payload.stargateLinks` item contains the canonical, undirected pair:

- `firstSystemId`
- `secondSystemId`

This is the exact deduplicated graph shape loaded by `SqliteStaticMapRepository` and consumed by the current renderer and normal route engine. Raw reciprocal gate IDs, type IDs, destination gate IDs, and gate coordinates are not needed by those consumers and are not exported. System Info can derive a system's Stargate count from these links.

### Regions

Each `payload.regions` item contains `id` and `name`.

### Constellations

Each `payload.constellations` item contains `id`, `regionId`, and `name`.

The current map derives Region and Constellation label anchors and bounds from projected member systems, so no separate render cache or precomputed anchor is published.

### Ansiblex

Each `payload.ansiblexLinks` item contains:

- `id`, used as stable route/render edge identity within the published pack
- `firstSystemId`
- `secondSystemId`
- `direction`: `BIDIRECTIONAL`, `FIRST_TO_SECOND`, or `SECOND_TO_FIRST`
- nullable `displayName`
- `enabled`, always `true` in schema version 1

The Desktop route graph calls `AnsiblexRepository.getAll()` and filters `enabled == true` before creating directed route edges. The exporter applies the same active-link rule and exports no disabled record. Imported/manual provenance, batch IDs, notes, and timestamps are management data and are intentionally omitted.

## Integrity validation

Before a file can be published, the exporter verifies:

- non-empty system, Stargate-link, Region, and Constellation collections;
- unique system, Region, and Constellation IDs;
- valid Region -> Constellation -> system references;
- finite security and required XYZ coordinates;
- finite Official 2D coordinates when present;
- canonical, unique Stargate pairs whose endpoints exist;
- unique enabled Ansiblex IDs and pairs whose endpoints exist;
- payload counts exactly matching the serialized counts;
- supported schema and valid version metadata.

Errors name the failing data or filesystem condition. Examples include `Static data unavailable`, `Ansiblex data unavailable`, a missing endpoint ID, serialization failure, and the output path for filesystem failures.

## Manifest schema and discovery

`manifest.json` contains:

- `schemaVersion`
- `packVersion`
- `generatedAt`
- `desktopAppVersion`
- `sdeBuild`
- `fileName`
- `sizeBytes`
- `sha256`
- `counts`

The filename must be exactly `web-pack-<packVersion>.json.gz`. The Desktop exporter writes the pack first and replaces `manifest.json` last. A Web client therefore starts every load by requesting the stable manifest URL, validates it, then requests the versioned pack named by the manifest. There is no Import, Apply, or Update action on the Web side.

## Export from Desktop

1. Start Desktop with a valid static database and user database.
2. Open **Preferences -> Web Pack**.
3. Click **Export Web Pack**.
4. Select the parent directory for the export.
5. Confirm the success panel's SDE build, data counts, schema, pack version, and output path.

The selected parent receives an `EVE-Web-Pack` child directory. Re-exporting to the same parent replaces `manifest.json` and writes the current versioned pack; older versioned packs are not deleted automatically.

## Deploy to a Web server

Publish the contents of `EVE-Web-Pack` under the Web application's `/data/` directory:

```text
/data/manifest.json
/data/web-pack-<packVersion>.json.gz
```

For a safe update, upload the new versioned pack first and upload `manifest.json` last. Keep the previous pack available during the rollout so a client that fetched the old manifest immediately before deployment can still complete its request. Once the manifest points at the new filename, the next page load discovers it automatically.

Recommended HTTP caching:

- `/data/manifest.json`: `Cache-Control: no-cache, must-revalidate` (or a short `max-age` with revalidation).
- `/data/web-pack-<packVersion>.json.gz`: `Cache-Control: public, max-age=31536000, immutable`.

If the data directory is on a different origin, configure CORS for the Web application origin. HTTPS (or localhost during development) is required for reliable Web Crypto checksum verification.

## Minimal loader

`web-loader/index.html` is deliberately not a Web application shell. It uses `web-loader/web-pack-loader.mjs` to:

1. fetch `manifest.json` with `cache: "no-cache"`;
2. reject unsupported schemas or invalid metadata;
3. fetch the versioned gzip pack;
4. verify compressed byte size and SHA-256;
5. decompress and parse the document;
6. verify manifest/pack version, SDE build, and counts;
7. display systems, Stargates, Ansiblex, SDE build, and pack version.

Run its dependency-free contract tests with:

```powershell
.\gradlew.bat webLoaderTest
```

To try an exported directory locally, copy or link its files into `web-loader/data/`, serve `web-loader` over HTTP (for example with a JDK `jwebserver`), and open `index.html`. Do not open the page directly with a `file:` URL because browser fetch policies vary.

## Data explicitly not exported

The Web Pack does not contain:

- Shared Marker data, server URL, invite code, credentials, protocol state, or authentication;
- saved/local/temporary markers;
- AI, MCP, local-control, Codex, or Feature Pack state;
- Desktop preferences, window state, debug data, or dynamic JARs;
- Ansiblex import source, batch history, notes, or timestamps;
- SDE download/import audit rows that the Web client does not consume;
- raw `static.db` or `user.db` tables.

Shared Marker remains an online dynamic concern for Phase 3 and is not part of this publication format.

## Phase 2 consumption boundary

Phase 2 can depend on the JavaScript loader's returned `document.payload` and reconstruct Web-side indexes, graph edges, projection scenes, and jump candidates from the explicit DTOs above. It should continue to discover data exclusively through `manifest.json`; it should not add SDE or Ansiblex management, an Apply button, or a backend database. Phase 1 stops at the verified data-publication chain.
