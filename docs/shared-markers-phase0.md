# Shared Markers Phase 0 — Architecture, Security, and Implementation Freeze

Status: complete design freeze; docs-only

Audited Map baseline: EVE Static Map Planner `1.1.0`

Audited commit: `75711efbdfaeed59379063335c1f211c96f0ed9c`
Normative wire contract: `shared-map-protocol-v1.md`

## 1. Phase boundary and preflight

Phase 0 permits code reading and documentation only. It does not create EVE Shared Map Server, a Gradle module,
HTTP client, UI, migration, production deployment, or any Shared Marker runtime code.

Preflight on 2026-09-01:

| Item | Observed |
| --- | --- |
| Branch | `main` |
| HEAD | `75711efbdfaeed59379063335c1f211c96f0ed9c` |
| `origin/main` | `75711efbdfaeed59379063335c1f211c96f0ed9c` |
| Working tree | clean |
| `appVersion` | `1.1.0` |
| Feature API artifact | `2.0.0` |
| `user.db` schema | `4` |
| MCP catalog | exactly 30 tools |

No baseline mismatch required a stop. Phase 0 does not change app version, Feature API, `user.db`, MCP, AI Control,
ESI, Sovereignty Pack, or production source.

## 2. Decision summary

| Question | Frozen V1 decision |
| --- | --- |
| Reuse current `Marker` domain? | No. Introduce independent `SharedMarker`; converge only in app presentation/UI |
| Add a Map module? | Yes, add `:shared-client` in the later Map-client phase |
| Existing module dependencies of `shared-client` | None; app adapters map protocol vocabulary to existing Core types |
| Does Core know Shared Markers? | No |
| Does Data or `user.db` know Shared Markers? | No |
| May UI call HTTP directly? | No |
| Shared topology scope | Global application state, independent of Planning View |
| Same Workspace/system cardinality | Exactly zero or one Shared Marker; unique `(workspace_id, system_id)` |
| Sync | Complete authoritative snapshot immediately and every 30 seconds |
| Conflicts | Required expected version; HTTP 409; no last-write-wins |
| Idempotency | Required UUID key on authenticated mutations; stored 24 hours |
| Non-sensitive config | Host `settings.properties` |
| Access token | Host-owned current-user Windows DPAPI credential store; never preferences or `user.db` |
| Server system validation | Compact SDE-derived allowlist packaged with server |
| Member management UI | Minimal Admin UI in Map Preferences; no separate web admin in V1 |
| Marker deletion | Hard delete marker, retain append-only audit event |
| Bootstrap Admin | One-shot server CLI creates Admin membership and single-use invite |
| Production topology | Caddy → server → private PostgreSQL; only 443 public |

## 3. Current Marker architecture audit

### 3.1 Core domain

`core/.../marker/Marker.kt` defines one local `Marker` model with:

- `systemId` as its identity;
- `MarkerPersistence.TEMPORARY` or `SAVED`;
- nullable trimmed `name` and `notes`;
- the fixed seven-value `MarkerColor` palette;
- persistence timestamps only for Saved Markers; and
- `SavedMarkerCreatedBy.USER` or `AI` provenance for a locally persisted Saved Marker.

`MarkerDraft.create` trims blank text to null but currently has no text-length or control-character limit.
`Marker.saved` and `Marker.temporary` deliberately encode different lifecycle metadata in one type.

`core/.../marker/SavedMarkerChild.kt` models Saved Marker tags as child entities. The nine UI-supported semantic keys
are `staging`, `rally`, `danger`, `logistics`, `home`, `backup`, `industrial`, `strategic`, and `keepstar`. The value
class itself is forward-compatible with unknown lowercase semantic keys; the normalizer restricts local editing to
the supported set.

### 3.2 Local persistence and application service

`core/.../repository/SavedMarkerRepository.kt` is a synchronous local repository contract keyed by `systemId`.
`data/.../SqliteSavedMarkerRepository.kt` implements it against `user.db`.

`UserDatabaseSchema.VERSION` is 4. Its relevant constraints are:

- `saved_markers.system_id` is the primary key, so there is one local Saved Marker per system;
- color and creator are closed SQL checks;
- child rows cascade from the local parent;
- child type and order are unique within the parent; and
- no Shared ownership, Workspace, remote version, or server actor exists.

`marker-application/.../SavedMarkerService.kt` owns a `StateFlow<SavedMarkerState>` and serializes repository
mutations with a coroutine mutex. It atomically publishes maps by system ID and treats repository output with any
non-Saved marker as invalid. It closes by cancelling its owned scope.

`AiSavedMarkerApplicationService` is not an AI Mission Marker store. It is a narrow permission-gated adapter that
lets AI Control read/create the same local Saved Marker domain with `createdBy = AI`. It validates systems against
the local `UniverseRepository` and deliberately does not expose update/delete.

### 3.3 App state and UI

`MarkerViewModel` combines two local lifecycles into one `MarkerUiState`:

- Saved Marker state from `SavedMarkerService`; and
- process-only Temporary Markers held by the ViewModel.

The combined map is `Map<Int, Marker>`, so any local marker claims the system and blocks another local marker. The
ViewModel tracks per-system busy state and publishes complete copied maps. `MarkerUiState.canCreateMarkers` is tied
to local database readiness, which is not an appropriate readiness signal for Shared Markers.

`MarkerEditorDialog` edits Local Saved/Temporary fields and local Saved Marker tag children. It has no Workspace,
role, remote actor, version conflict, offline, or remote validation concept.

`MarkerManagerWindow` and `MarkerManagerPresentationBuilder` filter the combined state to
`MarkerPersistence.SAVED`; they display local AI provenance but no remote actor/version/sync status.

The right-click menu is a flat, strictly ordered `SystemContextMenuPresentationBuilder` list. Marker operations are
currently the first section, followed by jump/route/capital actions and a final Wormhole section. Its marker
availability is derived only from local marker state and local database status.

### 3.4 Map presentation and visual priority

`MarkerMapPresentationBuilder` consumes only systems already admitted by map label/viewport culling. It presents:

- Temporary Marker as an offset outline diamond;
- Local Saved Marker as a centered outer ring; and
- local Saved Marker child tags as radial badges only while hovered, selected, or interacted with.

Marker names follow existing Marker preferences and system semantic-label mode. Canvas ordering currently places
Local Markers and AI Mission Markers on the Saved Marker layer, Feature Pack system markers above them, and selected
or hovered system focus above all of those. Selection therefore already has the correct priority.

AI Mission Markers are independent `control.mission.MissionMarker` objects with role, label, notes, and optional
color override. `MissionMapStateStore` filters them to the current Planning View and `drawMissionMarkers` renders an
offset circle/cross. They are not `Marker`, do not use `user.db`, and are cleared with the AI Control session.

### 3.5 Existing tests

The current suite covers:

- Marker construction and fixed color vocabulary;
- SQLite Saved Marker CRUD, uniqueness, migrations, provenance, and child atomicity;
- Saved Marker service concurrency and StateFlow publication;
- Temporary/Saved ViewModel conflict and busy behavior;
- editor tags and dialog behavior;
- context-menu ordering and disabled state;
- ring/diamond projection, viewport culling, labels, radial badges, hover/selection expansion; and
- local AI Saved Marker versus AI Mission Marker concurrency.

These are local-domain regression tests. Shared Marker tests must be added beside them without changing their
meaning.

## 4. Shared Marker domain decision

### 4.1 Rejected: add `SHARED` to `MarkerPersistence`

Extending `MarkerPersistence` would incorrectly imply:

- Shared Markers are stored by `SavedMarkerRepository` in `user.db`;
- local database readiness controls Shared availability;
- one combined `Map<Int, Marker>` can represent local and remote ownership;
- Shared and Local markers conflict solely because they share a system;
- local `createdBy = AI/USER` is equivalent to server actors and Workspace roles; and
- a remote optimistic-lock version can be omitted from the model.

It would also force Shared concerns into Core, Data, AI Control, MCP, and existing exhaustive `when` expressions.

### 4.2 Accepted: independent `SharedMarker`

`SharedMarker` lives in `shared-client/model` and follows the protocol DTO: marker/workspace IDs, system ID, name,
color vocabulary, tag keys, notes, created/updated actors and times, and version. It has no local persistence enum.

Ownership remains explicit:

| Type | Owner | Lifetime | Persistence | Planning View scoped? |
| --- | --- | --- | --- | --- |
| Temporary Marker | Map ViewModel | Process | None | No |
| Local Saved Marker | Map/User | Until local delete | `user.db` | No |
| AI Mission Marker | AI Control Mission | Control session | None | Yes |
| Shared Marker | Shared Map Server/Workspace | Until server delete | PostgreSQL only | No |

The app combines these four inputs only in a presentation model keyed/grouped by system. Existing local models and
repositories remain unchanged.

## 5. Map module boundary

### 5.1 New module

The later Map-client phase should add:

```text
shared-client/
└─ src/main/kotlin/dev/evestaticmapplanner/shared/
   ├─ model/
   ├─ protocol/
   ├─ api/
   ├─ auth/
   ├─ sync/
   └─ conflict/

app/src/main/kotlin/dev/evestaticmapplanner/shared/
├─ SharedMarkerViewModel.kt
├─ SharedMarkerPresentation.kt
├─ SharedMarkerManagerWindow.kt
├─ SharedMarkerEditorDialog.kt
└─ SharedMapPreferencesUi.kt
```

`shared-client` depends on no existing Map project module. It may depend on Ktor Client, Kotlin serialization,
coroutines, SLF4J, and JNA Platform for the Windows credential implementation. Keeping it independent prevents Core
from importing HTTP and prevents protocol DTOs from becoming local database entities.

`app` depends on `shared-client` and supplies adapters:

- protocol color string → existing display color;
- tag key → existing generic/known tag visual;
- Shared connection state → Compose UI;
- system ID → local universe name and Canvas position.

`core`, `data`, `marker-application`, `feature-api`, `control`, `control-transport`, and `mcp` do not depend on
`shared-client`. Feature API 2.0.0 and the 30-tool MCP contract require no change for Shared Markers V1. AI receives
no Shared Marker capability.

### 5.2 HTTP boundary

HTTP DTOs and JSON codecs live in `shared-client/protocol`; the Ktor implementation lives in `shared-client/api`.
Compose code never receives the raw `HttpClient`. UI invokes ViewModel commands; the ViewModel invokes a
`SharedMarkerCoordinator`; the coordinator invokes `SharedMapClient`.

## 6. Preferences and credential storage

### 6.1 Current state

The Host stores non-sensitive `AppPreferences` atomically in:

```text
%LOCALAPPDATA%\EVE Static Map Planner\settings.properties
```

The current Host has no generic secure credential abstraction. The external ESI Pack contains an internal,
Pack-private DPAPI refresh-token store. Because that code and lifecycle belong to an isolated external Pack and its
classes are `internal`, the Host cannot treat it as a reusable service. Its current-user DPAPI and atomic-file design
is useful precedent, not a dependency.

### 6.2 Non-sensitive Shared configuration

Add a `SharedMapPreferences` value to Host preferences in the later Map-client phase:

- normalized HTTPS server URL;
- selected Workspace ID;
- user-visible device name if configured.

The 30-second polling interval is fixed in V1 and is not a preference. Changing settings format/version is a later
implementation migration; Phase 0 does not edit it.

### 6.3 Access token

Add a Host-owned abstraction in `shared-client/auth`:

```kotlin
interface SecureCredentialStore {
    fun load(key: SharedCredentialKey): SecretValue?
    fun save(key: SharedCredentialKey, secret: SecretValue)
    fun delete(key: SharedCredentialKey)
}
```

The V1 implementation is `WindowsDpapiCredentialStore`:

- Windows current-user DPAPI;
- additional entropy bound to application ID, normalized server origin, and Workspace ID;
- ciphertext under
  `%LOCALAPPDATA%\EVE Static Map Planner\credentials\shared-map\<server-hash>\<workspace-id>.dpapi`;
- atomic temporary-file replacement;
- restrictive current-user ACL where supported;
- fail closed on corruption, decrypt failure, or non-Windows execution; and
- explicit byte/character buffer clearing where APIs permit.

Credential values are never properties, ordinary SQLite columns, Git config, command-line arguments, diagnostics,
audit, or log fields. Secret wrapper `toString()` returns a redacted constant. HTTP logging is disabled for headers
and bodies on auth routes. JVM heap dumps are disabled for production OOM diagnostics; application crash reports do
not attach memory dumps. An OS administrator or process-memory compromise remains outside what DPAPI can prevent.

The credential is deleted on explicit Disconnect. Revoking the remote token without local deletion causes the next
request to enter `AUTH_REQUIRED`, after which the user may delete/re-exchange it.

## 7. Application lifecycle

`ReadyApplication` is the current composition root. It constructs global repositories, services, StateFlow owners,
ViewModels, control/MCP hosts, and the shutdown coordinator. Shared Map should follow that pattern.

### 7.1 Startup

```text
ReadyApplication
  -> load SharedMapPreferences
  -> construct WindowsDpapiCredentialStore
  -> construct SharedMapClient and SharedMarkerStore
  -> construct SharedMarkerSyncCoordinator / SharedMapSession
  -> load selected Workspace credential
  -> GET meta and negotiate protocol
  -> authenticate with GET me
  -> fetch Workspace and initial full marker snapshot
  -> atomically publish SharedMarkerStore StateFlow
  -> begin 30-second polling
```

`SharedMapSession` owns the client, polling scope/job, current authenticated context, and global
`SharedMarkerStore`. `SharedMarkerViewModel` owns only UI transient state and commands. The store is created once in
`ReadyApplication`, not inside `StaticMapScreen` or a Planning View.

Switching, creating, renaming, or deleting a Planning View has no effect on Shared topology or its polling. Only AI
Mission state remains Planning View scoped.

### 7.2 Shutdown

Add one close operation to `ApplicationShutdownCoordinator` before diagnostics close:

```text
stop polling
  -> cancel/reject new Shared requests
  -> await bounded cancellation of in-flight requests
  -> close Ktor HttpClient
  -> clear decrypted credential references
  -> atomically clear SharedMarkerStore
```

`close()` is idempotent and bounded. Compose disposal and explicit application shutdown may both call it safely.

## 8. Sync and failure state ownership

`SharedMarkerStore` publishes one immutable snapshot object containing Workspace metadata, role, marker map,
Workspace revision, connection state, stale flag, and `lastSuccessfulSyncAt`. One assignment replaces the snapshot;
the map never sees item-by-item reconciliation.

The full state machine and 401/403 behavior are normative in `shared-map-protocol-v1.md`. Important app boundaries:

- transient failure retains only current-session memory;
- app restart has no Shared snapshot without a successful fetch;
- all Shared writes are disabled outside `ONLINE` and for Viewer;
- Local marker actions remain available regardless of Shared connectivity; and
- confirmed membership revocation clears Shared state.

## 9. Shared Marker UI contract

### 9.1 Context menu

The marker section remains first and becomes:

```text
Add Temporary Marker
Add Saved Marker…
Add Shared Marker…
--------------------
Jump / route / capital actions
--------------------
Wormhole actions
```

Local actions continue to depend only on local state. Shared action rules:

- Viewer: no Add/Edit/Delete; show `View Shared Marker…` when present.
- Editor/Admin online: Add when absent; Edit/Remove when present.
- Disconnected/offline/auth-required: Shared actions disabled with a concise status reason.
- A Local Saved Marker on the system does not block adding a Shared Marker.
- Because Workspace/system is unique, an existing Shared Marker changes Add into Edit/View.

Shared operations use separate request/editor types; they never call `MarkerViewModel`.

### 9.2 Visual presentation

V1 uses one Shared Marker per Workspace/system. Its Canvas identity is a segmented outer ring plus a small static
link/people badge. It does not reuse the continuous Local Saved Marker ring and does not animate.

Presentation hierarchy for one system containing every marker class:

1. normal system node and route/jump visuals;
2. Local Saved Marker continuous inner ring and optional radial child badges;
3. Shared Marker segmented ring approximately 4 dp outside the local ring with one small ownership badge;
4. AI Mission Marker offset circle/cross; multiple mission markers aggregate by system with a count badge;
5. hover/selected-system focus above all markers.

The Shared ring uses the marker color but remains no larger than required to clear the Local ring. It has no glow by
default and never exceeds selection/route visual priority. Shared tags are shown in hover/details/manager rather than
as another radial child orbit; this avoids colliding with Local Saved Marker children.

Canvas culling uses the same visible-system set as existing markers. Unknown-system Shared Markers remain in Manager
but have no Canvas presentation. Shared name labels are off by default; hover and selected-system info expose name,
tags, actor, time, and sync state. If a future preference enables names, collision handling must consider existing
system and Local Marker labels.

### 9.3 Shared Marker Manager

Minimum V1 content:

- active Workspace name and role;
- connection state, stale state, last successful sync, and `Refresh Now`;
- search/filter by system, marker name, tag, actor, and notes;
- list with system, name, tags, updatedBy, updatedAt, and version;
- selection and `Show on Map` when the system is known locally;
- Edit/Remove only for Editor/Admin while online; and
- conflict UI showing local draft and current server object.

No audit history, bulk import/export, comments, or attachments are in V1.

### 9.4 Preferences and Admin UI

Map Planner includes a minimal `Preferences → Shared Map` area rather than requiring a separate admin web app:

- server URL, invite exchange, Workspace selection, device name, status, and Disconnect;
- `Members` tab visible to Admin;
- member list; create identity; change Viewer/Editor/Admin role; revoke membership;
- create/revoke invite, with invite secret shown once; and
- list/revoke membership-scoped devices.

The UI prevents removing/downgrading the final Admin but still handles the server's authoritative
`LAST_ADMIN_REQUIRED` response. A server-operator CLI remains available for bootstrap and emergency token/member
revocation. V1 does not include audit browsing or Workspace creation in the Map.

## 10. Server trust, identity, and authorization model

### 10.1 Identity flow

```text
one-shot bootstrap CLI
  -> first user + Workspace + ADMIN membership + short-lived invite

Admin UI
  -> server creates user identity + membership with selected role
  -> server creates invite bound to that membership
  -> recipient exchanges invite
  -> server creates 90-day membership-scoped Device Access Token
```

There is no public signup, email/password, password reset, EVE SSO, or client-asserted identity. Invite and access
secrets use at least 256 random bits, are HMAC-hashed at rest with a server pepper, and are compared in constant time.
The pepper is a deployment secret outside PostgreSQL and outside the image.

### 10.2 Solar-system authority alternatives

| Candidate | Benefit | Failure mode | V1 decision |
| --- | --- | --- | --- |
| A. Package a compact SDE-derived allowlist | Server is independently authoritative without ESI or full SDE; deterministic and testable | A server release can lag a newly added system until its allowlist is refreshed | **Accepted** |
| B. Check only positive `Int`; trust Map creation validation | Smallest server | Non-Map callers can create dangling markers; old clients can poison all clients | Rejected |
| C. Call ESI or a static-universe service | Potentially freshest external authority | Runtime dependency, availability/latency/privacy coupling, and no need for ESI in this product | Rejected |

The accepted resource contains only canonical solar-system IDs plus generation/build metadata, not the full SDE.
Its build is exposed by `/api/v1/meta`. A newer Map whose system is absent receives a precise validation error and
must wait for a server allowlist update; the server never silently accepts an unverified ID.

### 10.3 Bootstrap

Phase 0 freezes the following operator contract. Phase 1 may include it in README as a future command but must not
implement it:

```text
java -jar eve-shared-map-server.jar bootstrap-admin \
  --display-name Coord_A \
  --workspace-name "Alliance Strategic Map" \
  --invite-ttl 1h
```

In Docker this is invoked through a one-shot `docker compose run --rm` command. It takes database and pepper
configuration from the same secret environment/file provider as the server, runs one transaction, refuses if any
Workspace exists, and emits the invite only to the invoking terminal.

## 11. PostgreSQL schema draft

PostgreSQL 18 UUIDv7 defaults are preferred for sortable opaque IDs. All timestamps are `timestamptz`; the server
serializes them as UTC Instants. Roles/actions may be text columns with explicit checks rather than PostgreSQL enums
so migrations can extend them deliberately.

### 11.1 `users`

| Column | Contract |
| --- | --- |
| `user_id uuid` | PK, default `uuidv7()` |
| `display_name varchar(80)` | trimmed Unicode NFC, nonblank, not globally unique |
| `status text` | `ACTIVE` or `REVOKED` |
| `created_at timestamptz` | required |
| `updated_at timestamptz` | required |
| `revoked_at timestamptz` | nullable |

Index `users(status)`. Users are soft-revoked, never hard deleted while audit/marker actors reference them. An Admin
may rename a member's V1 identity. Shared Marker actor display names then reflect the current name; audit retains the
event-time snapshot.

### 11.2 `workspaces`

| Column | Contract |
| --- | --- |
| `workspace_id uuid` | PK, default `uuidv7()` |
| `name varchar(80)` | required |
| `revision bigint` | required, default 0, nonnegative |
| `created_at timestamptz` | required |
| `updated_at timestamptz` | required |

No Workspace delete endpoint exists in V1.

### 11.3 `workspace_members`

| Column | Contract |
| --- | --- |
| `member_id uuid` | PK, default `uuidv7()` |
| `workspace_id uuid` | FK workspaces, `ON DELETE RESTRICT` |
| `user_id uuid` | FK users, `ON DELETE RESTRICT` |
| `role text` | `VIEWER`, `EDITOR`, or `ADMIN` |
| `version bigint` | required, starts 1 |
| `created_at/updated_at timestamptz` | required |
| `revoked_at timestamptz` | nullable |

Unique `(workspace_id, user_id)`. Index `(workspace_id, revoked_at, role)`. Revocation is soft and atomically revokes
all membership-bound tokens. The final active Admin cannot be revoked or downgraded.

### 11.4 `invites`

| Column | Contract |
| --- | --- |
| `invite_id uuid` | PK |
| `member_id uuid` | FK membership, `ON DELETE RESTRICT` |
| `secret_hash bytea` | unique HMAC digest; never plaintext |
| `secret_prefix varchar(16)` | non-secret operator identifier |
| `created_by_member_id uuid` | FK member |
| `created_at/expires_at timestamptz` | required |
| `used_at/revoked_at timestamptz` | nullable |

Indexes on `(member_id, created_at desc)` and `expires_at`. Exchange locks the invite row and sets `used_at` in the
same transaction that creates the token. Five active unused invites per membership.

### 11.5 `access_tokens`

| Column | Contract |
| --- | --- |
| `token_id uuid` | PK |
| `member_id uuid` | FK membership, `ON DELETE RESTRICT` |
| `token_hash bytea` | unique HMAC digest; never plaintext |
| `token_prefix varchar(16)` | non-secret operator identifier |
| `device_name varchar(80)` | required |
| `created_at/expires_at timestamptz` | required |
| `last_used_at timestamptz` | nullable; update no more often than every 15 minutes |
| `revoked_at timestamptz` | nullable |
| `revoked_by_user_id uuid` | nullable FK user |

Indexes on `(member_id, revoked_at)` and `expires_at`. Tokens are revoked, not hard deleted, during audit retention.
Ten active tokens per membership.

### 11.6 `shared_markers`

| Column | Contract |
| --- | --- |
| `marker_id uuid` | PK |
| `workspace_id uuid` | FK Workspace, `ON DELETE RESTRICT` |
| `system_id integer` | positive; validated against application allowlist |
| `name varchar(80)` | required |
| `color text` | seven protocol values |
| `tags text[]` | required, default empty; server validates unique semantic keys |
| `notes varchar(2000)` | nullable |
| `created_by_user_id uuid` | FK user, restrict |
| `updated_by_user_id uuid` | FK user, restrict |
| `created_at/updated_at timestamptz` | required |
| `version bigint` | required, starts 1 |

Unique `(workspace_id, system_id)`. Index `(workspace_id, updated_at desc)` and optionally GIN on `tags` only after
query evidence. Markers are hard deleted; audit survives. Create/update/delete and Workspace revision increment are
one transaction.

### 11.7 `audit_events`

| Column | Contract |
| --- | --- |
| `event_id uuid` | PK |
| `workspace_id uuid` | required FK Workspace |
| `actor_user_id uuid` | nullable for bootstrap/system event; FK user |
| `actor_display_name varchar(80)` | event-time snapshot |
| `action text` | required action key |
| `target_type text` | `WORKSPACE`, `MEMBER`, `INVITE`, `DEVICE`, or `MARKER` |
| `target_id uuid` | nullable only where no target exists yet |
| `system_id integer` | nullable marker context |
| `timestamp timestamptz` | required |
| `metadata jsonb` | required JSON object, default `{}` |

Indexes `(workspace_id, timestamp desc)`, `(actor_user_id, timestamp desc)`, and `(target_type, target_id)`. Events are
append-only and retained indefinitely in V1; there is no automatic audit purge or audit UI.

Actions are:

`BOOTSTRAP_ADMIN_CREATED`, `USER_CREATED`, `MEMBER_CREATED`, `MEMBER_ROLE_CHANGED`, `MEMBER_RENAMED`,
`MEMBER_REVOKED`, `INVITE_CREATED`, `INVITE_REVOKED`, `INVITE_EXCHANGED`, `DEVICE_TOKEN_CREATED`,
`DEVICE_TOKEN_REVOKED`, `MARKER_CREATED`, `MARKER_UPDATED`, and `MARKER_DELETED`.

Metadata may contain changed field names, versions, role transition, request ID, and token/invite IDs. It never
contains bearer/invite secrets, their hashes, raw credentials, complete notes, or request bodies.

### 11.8 `idempotency_records`

| Column | Contract |
| --- | --- |
| `token_id uuid` | FK access token |
| `idempotency_key uuid` | caller key |
| `request_fingerprint bytea` | method/route/body/version hash |
| `state text` | `IN_PROGRESS` or `COMPLETED` |
| `response_status integer` | nullable until complete |
| `response_body jsonb` | nullable and never secret-bearing |
| `created_at/expires_at timestamptz` | required |

Primary key `(token_id, idempotency_key)` and index `expires_at`. Records expire after 24 hours and are hard deleted
in bounded batches.

## 12. Audit and privacy contract

Shared notes may contain alliance strategy. The server database, backups, logs that contain metadata, and operator
access are sensitive. V1 guarantees:

- no Shared snapshot in Map `user.db`, settings, or local disk cache;
- no credentials, invite secrets, token/invite hashes, or raw authorization headers in logs/audit;
- no complete notes or request bodies in routine logs/audit;
- actor IDs and event-time display names retained for accountability;
- server-side hard marker deletion with a permanent deletion event; and
- no claim of end-to-end encryption from client to client.

## 13. Threat model

| Threat | Risk | Mitigation | Residual risk |
| --- | --- | --- | --- |
| Leaked access token | Attacker reads/writes as membership role | TLS; DPAPI client storage; HMAC hash at rest; 90-day expiry; device/admin revoke; no log/body capture | Attacker can act until revoke/expiry; bearer tokens are not proof-of-possession |
| Malicious Viewer sends DELETE manually | Unauthorized marker destruction | Server resolves current role every request; repository mutation requires Editor/Admin | Compromised server/DB bypasses application checks |
| Forged client role | Privilege escalation | Role absent from trusted token claims; loaded from membership transactionally | DB-admin compromise can change roles |
| Invite replay | Multiple credentials from one invite | Row lock plus atomic `used_at` and token creation; single-use | Lost exchange response requires a new invite |
| Stolen invite | Unauthorized first exchange | 256-bit secret; short default expiry; specific membership binding; revoke; HTTPS | Recipient communication channel can still leak it |
| Brute-force invite/token | Credential discovery | 256-bit entropy; HMAC; constant-time compare; strict per-IP exchange rate | Distributed DoS remains possible at proxy/network layer |
| SQL injection | Data loss or auth bypass | Prepared statements; fixed sort/filter grammar; no user SQL concatenation; tests | A future unsafe query can regress without review/tests |
| Credential log leakage | Persistent takeover | Central redaction; no auth body/header logging; secret-safe wrappers; logging tests | Third-party/JVM debug tooling must remain disabled in production |
| Public PostgreSQL | Direct data theft/destruction | Private Docker network; no host/public DB port; host firewall; least-privilege DB user | VPS/root compromise reaches DB |
| MITM | Read/modify strategic data or credentials | Caddy TLS, valid public certificate, HTTPS-only client config | Compromised CA/host or user-installed trust root remains |
| Stale client overwrites update | Silent lost update | Required marker version; atomic conditional update/delete; 409 conflict UI | User can intentionally reapply after reviewing current version |
| Deleted user uses cached token | Continued access | Membership/user status checked every request; membership revoke revokes devices | Previously viewed in-memory data cannot be remotely erased |
| Compromised Editor deletes markers | Authorized destructive misuse | Optimistic lock, confirmation UI, audit, fast membership/token revoke | V1 has no restore UI; recovery needs backup/manual operator work |
| Server unavailable | Shared topology absent/stale | Current-session last-good memory, explicit stale time, retry, backups | Fresh app session has no Shared data by design |
| Malicious marker text | UI/log injection or memory abuse | NFC/length/control validation; Compose text rendering; JSON escaping; no HTML | Confusable Unicode remains; display name is not identity proof |
| Oversized payload | Memory/CPU exhaustion | Proxy and Ktor body caps; field caps; marker/workspace caps | Connection-level floods need Caddy/host limits |
| Request flooding | Resource exhaustion | Single-instance token buckets, Caddy limits/timeouts, bounded DB pool | Large distributed attacks require upstream/VPS mitigation |
| Idempotency-key abuse | Cache exhaustion or replay confusion | Per-token key, request fingerprint, 24-hour TTL, record cap and cleanup | Active authenticated attacker can consume its own quota |
| Server operator reads notes | Strategic-data disclosure | Least privilege, encrypted off-host backups, access controls, no notes in logs | V1 server-side plaintext is visible to trusted operators/DB admins |
| Malicious server returns unknown system IDs | Misleading or crashing map | Packaged server allowlist; client local validation; unknown rows omitted safely | Compromised server can still alter valid-system marker content |
| Token pepper loss/rotation | All credentials fail | Secret backup with deployment secrets; documented rotation invalidates/reissues tokens | Pepper compromise requires global token/invite revocation |

## 14. Rate limits and payload limits

The exact protocol limits are in `shared-map-protocol-v1.md`. They use an in-memory token bucket because V1 is one
server instance. No Redis or distributed limiter is introduced. PostgreSQL constraints backstop cardinality where
practical, while application validation owns Unicode and allowlist checks.

## 15. Production deployment boundary

Phase 0 does not deploy. The production recommendation is one Docker Compose project:

```text
Internet
  -> TCP 443 (Caddy)
       -> shared-map-server on private network
            -> PostgreSQL on private network and named volume

TCP 80 -> Caddy only, optional ACME redirect/challenge
```

- Only 443 is publicly served; 80 may only redirect/complete ACME.
- PostgreSQL has no public or host port mapping.
- Caddy adds TLS, body limit, connection/request timeouts, and coarse IP rate protection.
- Server runs as non-root with a read-only image filesystem and dedicated writable temp directory if needed.
- Database credentials, token pepper, and backup encryption key come from environment-mounted secret files or an
  equivalent VPS secret facility; they are not image layers, Compose literals, or Git content.
- Development may bind PostgreSQL to `127.0.0.1` on a non-default host port; production may not.

## 16. Backup and restore standard

Minimum V1 production standard:

- nightly `pg_dump --format=custom` after consistency check;
- additional backup immediately before server/database migrations;
- encrypt before off-host transfer using a dedicated backup key;
- retain 30 daily and 12 monthly backups;
- keep at least one off-VPS copy in a separately controlled destination;
- PostgreSQL named volume is production state but is not itself a backup;
- back up deployment secret material through a separate protected secret-recovery process;
- quarterly restore test into an isolated PostgreSQL instance, run Flyway validation and marker/member row checks,
  and record duration/result; and
- target RPO 24 hours; measure restore time to establish the actual RTO.

Backups are sensitive because notes and identity/audit data are readable after restore. Backup logs contain object
names/checksums only, not database contents or secrets.

## 17. Server repository layout

Phase 1 creates this separate local repository, not a submodule of Map Planner:

```text
EVE-Shared-Map-Server/
├─ .github/workflows/ci.yml
├─ gradle/wrapper/
├─ src/main/kotlin/dev/evesharedmap/server/
│  ├─ Main.kt
│  ├─ config/ServerConfig.kt
│  ├─ database/DatabaseFactory.kt
│  ├─ database/FlywayMigrator.kt
│  ├─ health/HealthRoutes.kt
│  ├─ http/HttpModule.kt
│  ├─ http/RequestIdPlugin.kt
│  └─ logging/LogSanitizer.kt
├─ src/main/resources/
│  ├─ application.conf
│  ├─ logback.xml
│  └─ db/migration/V1__skeleton.sql
├─ src/test/kotlin/dev/evesharedmap/server/
│  ├─ config/
│  ├─ health/
│  └─ database/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ Dockerfile
├─ docker-compose.dev.yml
├─ .dockerignore
├─ .env.example
├─ .gitignore
├─ README.md
└─ docs/
```

Later phases add sibling packages `auth`, `workspace`, `marker`, `audit`, `security`, `universe`, and
`idempotency`. Package root is `dev.evesharedmap.server`. The server repository does not depend on Map Planner,
Feature API, MCP, ESI Pack, or `user.db`.

## 18. Testing strategy for Phases 1–8

### Server

- Pure domain: field normalization, roles, final-Admin invariant, version transitions, token/invite state machines.
- Repository: exact SQL, conditional versions, Workspace revision, uniqueness, audit transaction, idempotency.
- PostgreSQL integration: real PostgreSQL Testcontainers, migrations from empty DB, rollback/constraint behavior.
- Auth: entropy shape, HMAC/constant time wrapper, expiry, single-use/revoke, membership-scoped token.
- Permission: every Viewer/Editor/Admin endpoint matrix plus manual forged requests.
- API: codec, status, headers, examples, body/rate limits, safe errors, request IDs.
- Migration: new and previously migrated database; Flyway validate; no destructive repair at startup.

### Map

- Protocol codecs including unknown response fields and unknown tag keys.
- Secure credential store with real Windows DPAPI round trip and corrupt ciphertext failure.
- Client auth/header redaction, timeout, cancellation, and DTO errors.
- Sync initial/full snapshots, A/B/C → A/C/D atomic reconciliation, mutation immediate merge.
- State machine for online/offline/degraded/401/403/protocol mismatch/recovery.
- Conflict preservation and user retry.
- UI role gating, context menu, manager, unknown system, local actions while disconnected.
- Presentation with Local + Shared + Mission on one system, culling, labels, selection priority, aggregation.

### Cross-system and security

- Two clients see create/update/delete after poll/manual refresh.
- Two-client update/delete conflicts never overwrite silently.
- Response-lost mutation retry returns the idempotent original result.
- Permission downgrade and membership/token revoke take effect on the next request.
- Offline/recovery has no disk snapshot and does not lose current-session last-good state.
- Oversized/malicious Unicode/input and request flood boundaries.
- Log/audit scans prove no invite, bearer token, hash, authorization header, or full notes body.

## 19. Phase sequencing after this freeze

1. Phase 1: independent server skeleton only.
2. Phase 2: server database/auth/bootstrap/invite foundation.
3. Phase 3: Workspace/member/device authorization.
4. Phase 4: Shared Marker repository, audit, optimistic locking, idempotency, and API.
5. Phase 5: Map `shared-client`, secure credential storage, sync/state.
6. Phase 6: Map Shared Marker UI, manager, Admin UI, and presentation.
7. Phase 7: cross-system/security/operational acceptance.
8. Phase 8: VPS deployment, backup/restore drill, and production hardening.

Shared Wormholes, Routes, Planning Views, objectives, real-time streams, and AI access remain outside this sequence.

## 20. Unresolved blockers

There is no blocker to Phase 1.

Later implementation checkpoints must verify, without changing this protocol casually:

- the chosen SDE build and generator for the compact server allowlist before marker CRUD;
- real Windows DPAPI packaging/portable acceptance when `shared-client` is added;
- dependency versions and licenses again at the start of Phase 1 because they are time-sensitive; and
- the final visual prototype against dense Local child badges and multiple AI Mission Markers.

None of these requires Feature API 2.0.0, `user.db` schema 4, MCP, ESI, or Sovereignty Pack changes.

# Phase 1 Implementation Contract

Phase 1 creates only the local `EVE-Shared-Map-Server` skeleton. It must not modify Map Planner and must not implement
auth, bootstrap, users, Workspace/member domain, invites, access tokens, Shared Marker CRUD, audit business events,
idempotency, system allowlist, or production deployment.

## 1. Pinned toolchain proposal

Version snapshot verified on 2026-09-01. Recheck the same official sources immediately before implementation; if a
security replacement exists, report the proposed pin change before scaffolding.

| Component | Phase 1 pin | Basis |
| --- | --- | --- |
| JDK | 25 LTS | Aligns with current Map toolchain and server baseline |
| Gradle Wrapper | 9.7.0 | [official Gradle 9.7 release](https://docs.gradle.org/9.7.0/release-notes.html) |
| Kotlin JVM/serialization plugin | 2.4.10 | [official Kotlin releases](https://kotlinlang.org/docs/releases.html) |
| Ktor Server | 3.5.2 | [official Ktor releases](https://ktor.io/docs/releases.html) |
| kotlinx serialization JSON | 1.11.0 | Explicit compatibility pin required by Ktor 3.5.2 |
| kotlinx coroutines | 1.11.0 | Explicit compatibility pin required by Ktor 3.5.2 |
| Flyway OSS core + PostgreSQL module | 13.2.0 | [official Java API coordinates](https://documentation.red-gate.com/flyway/reference/usage/api-java) |
| PostgreSQL JDBC | 42.7.13 | [official pgJDBC changelog](https://jdbc.postgresql.org/changelogs/) |
| HikariCP | 7.0.2 | [official project artifact declaration](https://github.com/brettwooldridge/HikariCP) |
| Logback Classic | 1.6.3 | [official Logback downloads](https://logback.qos.ch/download.html) |
| Testcontainers | 2.0.5 | [official releases](https://github.com/testcontainers/testcontainers-java/releases) |
| PostgreSQL dev image | `postgres:18.6-alpine` | [official PostgreSQL 18.6 docs](https://www.postgresql.org/docs/18/) |

> Compatibility correction: kotlinx-coroutines and kotlinx-serialization were updated to 1.11.0 because Ktor 3.5.2 requires the 1.11.0 runtime APIs. Forcing the previously documented 1.10.x versions caused runtime NoSuchMethodError failures. This correction does not change the Shared Map Protocol v1 or Phase 1 scope.

Use Logback's built-in `ch.qos.logback.classic.encoder.JsonEncoder` for JSON Lines structured logs; do not add a
second JSON logging stack. The official encoder contract is documented in the
[Logback encoder manual](https://logback.qos.ch/manual/encoders.html).

## 2. Gradle and source layout

Use one Gradle JVM application module in Phase 1. Do not prematurely split API/domain/data modules. Required plugins:

- `org.jetbrains.kotlin.jvm`;
- `org.jetbrains.kotlin.plugin.serialization`; and
- `application`.

Use `mavenCentral()` only. Main dependencies:

- Ktor server core, CIO, content negotiation, status pages, call logging, and Kotlinx JSON serialization;
- HikariCP, PostgreSQL JDBC;
- Flyway core and `flyway-database-postgresql` at the same version;
- SLF4J API and Logback Classic runtime; and
- coroutines.

Tests use Kotlin test/JUnit Platform, Ktor server test host, and Testcontainers PostgreSQL. Toolchain is Java 25.
Dependency locking or verification metadata is required before Phase 1 commit.

## 3. Configuration contract

Typed `ServerConfig` loads environment variables or mounted secret-file references and validates before opening a
listener:

```text
SHARED_MAP_BIND_HOST=0.0.0.0
SHARED_MAP_PORT=8080
SHARED_MAP_DATABASE_URL=jdbc:postgresql://localhost:54329/eve_shared_map
SHARED_MAP_DATABASE_USER=eve_shared_map
SHARED_MAP_DATABASE_PASSWORD_FILE=<path>
SHARED_MAP_TOKEN_PEPPER_FILE=<path, reserved for Phase 2>
SHARED_MAP_LOG_LEVEL=INFO
```

Phase 1 may validate the pepper-file path as reserved configuration but must not implement token hashing. `.env.example`
contains placeholders only. Secrets are never accepted as Gradle properties or logged. Missing/blank database secret,
invalid port, non-JDBC URL, or unreadable secret file fails startup with a safe configuration error.

## 4. Database and Flyway setup

- Hikari pool maximum 10, minimum idle 1, connection timeout 5 seconds, validation timeout 2 seconds, PostgreSQL
  `tcpKeepAlive=true`.
- Run Flyway synchronously before Ktor reports ready.
- Use only versioned forward migrations; `clean` and automatic `repair` are disabled.
- `V1__skeleton.sql` is a no-business-data migration containing only `SELECT 1;`; it proves migration discovery
  without creating auth/Workspace/marker tables early.
- Health readiness executes a bounded `SELECT 1` through the application pool.

## 5. Health endpoint

Implement only:

```text
GET /health
```

Return `200` with safe `status`, UTC `serverTime`, server version, and database readiness when configuration,
migrations, and database are healthy. Return `503` with a generic unavailable status when readiness fails. Do not
expose stack traces, JDBC URL, database/user name, host topology, environment variables, or secrets.

Do not implement `/api/v1/meta` in Phase 1; it belongs with the protocol foundation after the skeleton.

## 6. Structured logging

- JSON Lines to stdout using Logback `JsonEncoder`.
- MDC/request fields: `requestId`, method, route template, status, durationMs, and safe remote-address hash if needed.
- Generate/validate `X-Request-Id`; never echo control characters or unbounded input.
- Do not log request/response bodies, query strings, authorization headers, cookies, database credentials, secret
  files, or exception messages that may contain them.
- Log startup stage, migration result, listener readiness, shutdown, and a sanitized error category.
- Add a test that seeds recognizable fake secrets and proves captured logs do not contain them.

## 7. Docker artifacts

`Dockerfile` is multi-stage:

1. pinned JDK 25 build image runs tests/build;
2. JRE 25 runtime image runs as a non-root numeric user;
3. copy only the distribution/runtime and notices;
4. read-only-compatible filesystem, explicit temp directory, `8080` exposed;
5. no secret or `.env` copied; and
6. exec-form entrypoint with graceful SIGTERM.

`docker-compose.dev.yml` provides:

- PostgreSQL `18.6-alpine` with a named disposable dev volume;
- health check using `pg_isready`;
- host binding `127.0.0.1:54329:5432`, never `0.0.0.0`;
- credentials supplied from a developer `.env` ignored by Git; and
- optional server profile only after the image has been built.

This is a development artifact, not the production Caddy/PostgreSQL deployment.

## 8. Phase 1 tests and acceptance

Required automated tests:

- valid and invalid typed configuration;
- secret-file value is not present in exception/log output;
- Ktor `GET /health` returns 200 with a fake healthy database and 503 with an unhealthy database;
- Testcontainers PostgreSQL starts, Flyway migrates from empty, `validate` passes, and health query succeeds;
- second startup is idempotent;
- graceful shutdown closes Ktor and Hikari; and
- endpoint catalog contains only `/health` (plus Ktor framework fallback), proving auth/Workspace/marker APIs were not
  implemented early.

Validation commands:

```text
./gradlew --no-daemon --console=plain clean check
docker compose -f docker-compose.dev.yml config
docker build .
```

Phase 1 final checks:

- local Git repository initialized on `main`;
- working tree clean after checkpoint;
- no remote push, tag, release, or VPS action;
- no Map Planner file changed;
- no auth/Workspace/member/invite/token/marker/audit business table or route exists; and
- README gives local PostgreSQL, test, run, and shutdown instructions without real secrets.

Suggested local checkpoint after all acceptance passes:

```text
chore: scaffold shared map server
```
