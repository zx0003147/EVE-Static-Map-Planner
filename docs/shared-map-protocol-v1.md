# Shared Map Protocol v1

Status: frozen for implementation after Shared Markers Phase 0

Protocol major: `1`

Wire prefix: `/api/v1`
Time format: UTC ISO-8601 `Instant`, for example `2026-09-01T11:32:18Z`

This document is the normative V1 wire contract between EVE Static Map Planner and EVE Shared Map Server. The
architecture and repository decisions that support this protocol are in `shared-markers-phase0.md`.

## 1. Scope and glossary

- **Local Saved Marker**: a Map-owned marker persisted in local `user.db`. It is never sent to Shared Map Server.
- **Temporary Marker**: a Map-owned, current-process marker. It is never sent to Shared Map Server.
- **AI Mission Marker**: an AI Control mission-owned, current-session marker scoped to a Planning View. It is never
  sent to Shared Map Server.
- **Shared Marker**: a server-owned strategic point visible to members of one Workspace.
- **Workspace**: the authorization and marker-isolation boundary.
- **Member**: a user membership in one Workspace, carrying exactly one role.
- **Device Access Token**: a high-entropy bearer credential issued by exchanging a single-use invite. In V1 it is
  bound to one membership, so a Workspace Admin can revoke it without affecting another Workspace.
- **Snapshot**: the complete authoritative marker set for one Workspace at one Workspace revision.
- **Actor**: the user identity associated with a create or update operation.

V1 shares only strategic markers. It does not synchronize any local marker, route, wormhole, Planning View, mission,
objective, or AI state.

## 2. Ownership invariants

1. Shared Map Server is authoritative for Shared Markers, membership, roles, invites, tokens, versions, and audit.
2. `user.db` is authoritative only for Local Saved Markers and never stores Shared Markers or Shared snapshots.
3. A Shared Marker belongs to exactly one Workspace.
4. A Workspace contains at most one Shared Marker for a given EVE solar system. The database constraint is
   `UNIQUE (workspace_id, system_id)`.
5. A Shared Marker has a server-generated ID. A client-generated temporary ID is never a final marker ID.
6. Role is read from the current server-side membership on every authorized request. The client cannot assert or
   encode a trusted role.
7. A Device Access Token is bound to one active membership. A user who joins another Workspace exchanges that
   Workspace's invite and receives a separate credential.

## 3. Transport and common HTTP rules

- Production transport is HTTPS only. Plain HTTP is permitted only on loopback in automated tests and local
  development.
- JSON uses UTF-8 and camelCase field names.
- Authenticated requests use `Authorization: Bearer <device-access-token>`.
- All mutating requests except invite exchange require `Idempotency-Key: <UUID>`.
- Responses include `X-Request-Id`. A valid caller-provided `X-Request-Id` may be adopted; otherwise the server
  creates one. Request IDs are not credentials.
- Request `Content-Type` is `application/json` when a body exists. Response content type is
  `application/json; charset=utf-8`, except `204 No Content`.
- Clients must ignore unknown response object properties. Servers reject unknown request object properties with
  `INVALID_ARGUMENT`; this catches misspelled mutations.
- UUIDs are lowercase canonical strings. IDs are opaque and clients must not derive ordering from them.
- The maximum request body is 32 KiB. The invite-exchange body maximum is 4 KiB.
- Server timestamps are generated and stored in UTC. Clients may localize them only for display.

## 4. Protocol negotiation

`/api/v1` is the major compatibility boundary. V1 also exposes metadata so a client does not silently use a server
whose supported protocol range excludes it.

### `GET /api/v1/meta`

No authentication is required.

```json
{
  "serverVersion": "0.1.0",
  "protocolVersion": 1,
  "minimumClientProtocolVersion": 1,
  "maximumClientProtocolVersion": 1,
  "features": ["shared-markers", "members", "invites", "device-revocation"],
  "universeBuild": "sde-2026-08-25"
}
```

The client connects only when its protocol version is within the inclusive server range. Otherwise it enters
`PROTOCOL_UNSUPPORTED`, performs no marker write, and explains the required upgrade. Feature strings are advisory;
they never grant permission.

## 5. Workspace and role contract

```json
{
  "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
  "name": "Alliance Strategic Map",
  "role": "EDITOR",
  "revision": 184,
  "memberId": "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
}
```

Roles are exactly:

| Role | Allowed operations |
| --- | --- |
| `VIEWER` | Authenticate; read self, Workspace, and Shared Markers |
| `EDITOR` | Viewer operations plus create, update, and delete Shared Markers |
| `ADMIN` | Editor operations plus member, invite, role, and membership-scoped device management |

An Admin cannot downgrade or remove the final active Admin. The server returns `LAST_ADMIN_REQUIRED`.

## 6. Identity, invite, and token contract

### 6.1 Identity creation

An Admin creates a server-side user identity and Workspace membership. The client never supplies a user ID or claims
a role for itself. Display names are not login names and are not required to be unique.

An Admin then creates an invite bound to that exact membership. The recipient exchanges the invite for a Device
Access Token.

### 6.2 Invite secret

- At least 256 bits from a cryptographically secure random source.
- Prefix `esm_inv_` followed by unpadded base64url material.
- Default lifetime 72 hours; requested lifetime may be 1 hour through 30 days.
- Single-use, revocable, and bound to one user, membership, Workspace, and role as stored on the server.
- Stored only as `HMAC-SHA-256(serverPepper, secret)` plus a non-secret short prefix for operator identification.
- The plaintext is returned exactly once by invite creation and is never logged or placed in audit metadata.

### 6.3 Device Access Token

- At least 256 bits from a cryptographically secure random source.
- Prefix `esm_dev_` followed by unpadded base64url material.
- Default and fixed V1 lifetime: 90 days from issuance. V1 has no refresh-token flow.
- Revocable; multiple device tokens may exist for one membership.
- Stored only as `HMAC-SHA-256(serverPepper, token)` plus a non-secret short prefix.
- Returned exactly once by invite exchange and never returned by later device-list calls.
- The server checks token status, expiry, membership status, and current membership role on every request.

If the invite-exchange response is lost, the invite remains consumed and the Admin must issue a new invite. The
server never persists token plaintext merely to replay an exchange response.

### 6.4 Bootstrap Admin

The first Admin is created by a one-shot server CLI command, not by a public endpoint or direct PostgreSQL editing.
The command atomically creates a user, Workspace, Admin membership, and short-lived invite; it refuses to run once a
Workspace exists. It writes the invite only to the invoking operator's terminal, bypassing application logging.

## 7. Shared Marker DTO

```json
{
  "markerId": "01991d67-5672-7514-9369-482ed563c63d",
  "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
  "systemId": 30004759,
  "name": "Northern staging",
  "color": "BLUE",
  "tags": ["staging", "strategic"],
  "notes": "Form before 19:30 UTC.",
  "createdBy": {
    "userId": "01991d61-745e-7b08-a716-93c039cde2e2",
    "displayName": "Coord_A"
  },
  "updatedBy": {
    "userId": "01991d61-745e-7b08-a716-93c039cde2e2",
    "displayName": "Coord_A"
  },
  "createdAt": "2026-09-01T11:32:18Z",
  "updatedAt": "2026-09-01T11:32:18Z",
  "version": 1
}
```

`createdBy.displayName` and `updatedBy.displayName` are the actor's current display name when the response is built.
Audit events separately retain an event-time display-name snapshot.

### 7.1 Field validation

| Field | V1 rule |
| --- | --- |
| `systemId` | Signed 32-bit positive integer present in the server's packaged solar-system allowlist |
| `name` | Required; Unicode NFC; trimmed; 1–80 Unicode code points; no control characters or line breaks |
| `notes` | Nullable; blank becomes `null`; CRLF becomes LF; maximum 2,000 code points; LF and TAB allowed, other control characters rejected |
| `color` | One of `RED`, `ORANGE`, `YELLOW`, `GREEN`, `BLUE`, `PURPLE`, `WHITE` |
| `tags` | Zero to nine unique lowercase semantic keys matching `[a-z0-9][a-z0-9._-]{0,63}` |
| `version` | Positive 64-bit integer controlled by the server |

Color vocabulary matches the current Local Marker palette but remains a protocol string rather than a dependency on
the Map's `MarkerColor` Kotlin type. Tags use the current Saved Marker semantic keys (`staging`, `rally`, `danger`,
`logistics`, `home`, `backup`, `industrial`, `strategic`, `keepstar`) but are not a closed wire enum. An older client
renders an unknown valid tag as a generic tag instead of rejecting the whole marker.

### 7.2 Solar-system validation

The V1 server packages a compact, generated allowlist of canonical solar-system IDs and records its SDE build in
`/api/v1/meta`. It does not package the full SDE and does not call ESI or another service at request time. Startup
fails if the allowlist is missing, empty, duplicated, or lacks its build metadata.

The Map also validates marker creation against its local static universe. If a newer server snapshot contains a
system unknown to an older Map, the Map:

1. keeps the DTO in the in-memory snapshot;
2. omits it from the Canvas rather than inventing a position;
3. shows `Unknown System (<systemId>)` with a compatibility warning in Shared Marker Manager;
4. disables map-focus for that row; and
5. never writes it to `user.db`.

## 8. Exact REST endpoint catalog

All endpoints under `/api/v1` return the unified error body in section 10.

| Method and URL | Auth | Minimum role | Result |
| --- | --- | --- | --- |
| `GET /health` | No | — | Liveness/readiness summary |
| `GET /api/v1/meta` | No | — | Version and feature negotiation |
| `POST /api/v1/auth/exchange-invite` | Invite | — | Create a Device Access Token |
| `GET /api/v1/me` | Bearer | Viewer | Current user, membership, Workspace, and device |
| `GET /api/v1/me/devices` | Bearer | Viewer | Devices for the current membership |
| `DELETE /api/v1/me/devices/{tokenId}` | Bearer | Viewer | Revoke one of the caller's membership devices |
| `GET /api/v1/workspaces` | Bearer | Viewer | Workspaces authorized by this membership-scoped token; one in V1 |
| `GET /api/v1/workspaces/{workspaceId}` | Bearer | Viewer | Workspace detail |
| `GET /api/v1/workspaces/{workspaceId}/markers` | Bearer | Viewer | Complete authoritative snapshot |
| `POST /api/v1/workspaces/{workspaceId}/markers` | Bearer | Editor | Create Shared Marker |
| `PATCH /api/v1/workspaces/{workspaceId}/markers/{markerId}` | Bearer | Editor | Update Shared Marker |
| `DELETE /api/v1/workspaces/{workspaceId}/markers/{markerId}?expectedVersion={version}` | Bearer | Editor | Delete Shared Marker |
| `GET /api/v1/workspaces/{workspaceId}/members` | Bearer | Admin | List active and revoked members |
| `POST /api/v1/workspaces/{workspaceId}/members` | Bearer | Admin | Create a user identity and membership |
| `PATCH /api/v1/workspaces/{workspaceId}/members/{memberId}` | Bearer | Admin | Change display name and/or role |
| `DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}?expectedVersion={version}` | Bearer | Admin | Revoke membership and its devices |
| `GET /api/v1/workspaces/{workspaceId}/members/{memberId}/devices` | Bearer | Admin | List membership devices |
| `DELETE /api/v1/workspaces/{workspaceId}/members/{memberId}/devices/{tokenId}` | Bearer | Admin | Revoke membership device |
| `GET /api/v1/workspaces/{workspaceId}/invites` | Bearer | Admin | List invite metadata, never secrets |
| `POST /api/v1/workspaces/{workspaceId}/members/{memberId}/invites` | Bearer | Admin | Create invite for existing member |
| `DELETE /api/v1/workspaces/{workspaceId}/invites/{inviteId}` | Bearer | Admin | Revoke unused invite |

V1 has no public Workspace-create or Workspace-delete endpoint. Bootstrap creates the first Workspace; later Workspace
provisioning is a server-operator operation until a future protocol explicitly adds it.

## 9. Request and response examples

### 9.1 Health

```http
GET /health
```

```json
{
  "status": "ok",
  "serverVersion": "0.1.0",
  "serverTime": "2026-09-01T11:32:18Z",
  "checks": {
    "database": "ok"
  }
}
```

Readiness returns HTTP `503` with `status: unavailable` and `checks.database: unavailable` if migrations or the
database are unavailable. It does not expose connection strings, database names, or exception text.

### 9.2 Exchange invite

```http
POST /api/v1/auth/exchange-invite
Content-Type: application/json
```

```json
{
  "inviteToken": "esm_inv_<secret>",
  "deviceName": "FC Laptop"
}
```

```json
{
  "accessToken": "esm_dev_<secret-returned-once>",
  "tokenId": "01991d6a-74ce-7ef5-8735-4e15444fc980",
  "expiresAt": "2026-11-30T11:32:18Z",
  "user": {
    "userId": "01991d61-745e-7b08-a716-93c039cde2e2",
    "displayName": "Coord_A"
  },
  "workspace": {
    "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
    "name": "Alliance Strategic Map",
    "role": "EDITOR",
    "revision": 184,
    "memberId": "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
  }
}
```

Success is HTTP `201`. The client must place `accessToken` directly into secure credential storage and must not log,
copy to preferences, or retain it in UI state longer than required.

### 9.3 Current user

```http
GET /api/v1/me
Authorization: Bearer esm_dev_<secret>
```

```json
{
  "user": {
    "userId": "01991d61-745e-7b08-a716-93c039cde2e2",
    "displayName": "Coord_A"
  },
  "workspace": {
    "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
    "name": "Alliance Strategic Map",
    "role": "EDITOR",
    "revision": 184,
    "memberId": "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
  },
  "device": {
    "tokenId": "01991d6a-74ce-7ef5-8735-4e15444fc980",
    "deviceName": "FC Laptop",
    "createdAt": "2026-09-01T11:32:18Z",
    "lastUsedAt": "2026-09-01T11:33:07Z",
    "expiresAt": "2026-11-30T11:32:18Z"
  }
}
```

### 9.4 Workspace list

```json
{
  "workspaces": [
    {
      "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
      "name": "Alliance Strategic Map",
      "role": "EDITOR",
      "revision": 184,
      "memberId": "01991d62-1fcb-70d0-858b-1d65f6ce3cf6"
    }
  ]
}
```

### 9.5 Marker snapshot

```http
GET /api/v1/workspaces/01991d60-b8a2-7a20-a311-b5114b27c219/markers
Authorization: Bearer esm_dev_<secret>
```

```json
{
  "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
  "revision": 184,
  "generatedAt": "2026-09-01T11:34:00Z",
  "markers": [
    {
      "markerId": "01991d67-5672-7514-9369-482ed563c63d",
      "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
      "systemId": 30004759,
      "name": "Northern staging",
      "color": "BLUE",
      "tags": ["staging", "strategic"],
      "notes": "Form before 19:30 UTC.",
      "createdBy": {"userId": "01991d61-745e-7b08-a716-93c039cde2e2", "displayName": "Coord_A"},
      "updatedBy": {"userId": "01991d61-745e-7b08-a716-93c039cde2e2", "displayName": "Coord_A"},
      "createdAt": "2026-09-01T11:32:18Z",
      "updatedAt": "2026-09-01T11:32:18Z",
      "version": 1
    }
  ]
}
```

The server reads `revision` and `markers` in one transactionally consistent snapshot.

### 9.6 Create marker

```http
POST /api/v1/workspaces/01991d60-b8a2-7a20-a311-b5114b27c219/markers
Authorization: Bearer esm_dev_<secret>
Idempotency-Key: a3c1be66-724c-46f0-bb8a-678817343a58
Content-Type: application/json
```

```json
{
  "systemId": 30004759,
  "name": "Northern staging",
  "color": "BLUE",
  "tags": ["staging", "strategic"],
  "notes": "Form before 19:30 UTC."
}
```

Success is HTTP `201`, includes `Location: /api/v1/workspaces/{workspaceId}/markers/{markerId}`, and returns the
server-created Shared Marker with `version: 1`.

### 9.7 Update marker

PATCH carries the complete mutable marker state, not a merge-patch. This avoids ambiguous missing-versus-null fields.

```http
PATCH /api/v1/workspaces/01991d60-b8a2-7a20-a311-b5114b27c219/markers/01991d67-5672-7514-9369-482ed563c63d
Authorization: Bearer esm_dev_<secret>
Idempotency-Key: 355e18eb-b7d6-4abe-aedd-6ef9abc678e1
Content-Type: application/json
```

```json
{
  "expectedVersion": 1,
  "name": "Northern staging — moved",
  "color": "ORANGE",
  "tags": ["staging", "danger"],
  "notes": null
}
```

Success is HTTP `200` and returns the authoritative marker with `version: 2`.

### 9.8 Version conflict

```json
{
  "code": "MARKER_VERSION_CONFLICT",
  "message": "The Shared Marker was changed by another member.",
  "requestId": "01991d70-d8a9-7250-826f-548d02aa0b18",
  "details": {
    "expectedVersion": 1,
    "currentVersion": 2,
    "currentMarker": {
      "markerId": "01991d67-5672-7514-9369-482ed563c63d",
      "workspaceId": "01991d60-b8a2-7a20-a311-b5114b27c219",
      "systemId": 30004759,
      "name": "Northern staging — updated by FC",
      "color": "RED",
      "tags": ["danger"],
      "notes": null,
      "createdBy": {"userId": "01991d61-745e-7b08-a716-93c039cde2e2", "displayName": "Coord_A"},
      "updatedBy": {"userId": "01991d72-3d74-724b-b501-752d265a4bbd", "displayName": "FC_B"},
      "createdAt": "2026-09-01T11:32:18Z",
      "updatedAt": "2026-09-01T11:40:02Z",
      "version": 2
    }
  }
}
```

Conflict is HTTP `409`. The client presents server and local values and requires the user to reapply changes; it
never silently overwrites.

### 9.9 Delete marker

```http
DELETE /api/v1/workspaces/01991d60-b8a2-7a20-a311-b5114b27c219/markers/01991d67-5672-7514-9369-482ed563c63d?expectedVersion=2
Authorization: Bearer esm_dev_<secret>
Idempotency-Key: 76a4cb5d-2f63-4c30-822e-6bbf7c37a645
```

Success is HTTP `204`. A version mismatch is the same HTTP `409` conflict shape as update. The marker is hard
deleted; its audit event remains.

### 9.10 Permission denied

```json
{
  "code": "FORBIDDEN",
  "message": "EDITOR or ADMIN role is required for this operation.",
  "requestId": "01991d75-b87a-722f-ad20-24ac849c3a21"
}
```

HTTP status is `403`.

### 9.11 Invalid input

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "One or more fields are invalid.",
  "requestId": "01991d77-1c82-73e4-a227-c6322363875f",
  "details": {
    "fieldErrors": [
      {"field": "systemId", "reason": "UNKNOWN_SOLAR_SYSTEM"},
      {"field": "name", "reason": "REQUIRED"}
    ]
  }
}
```

HTTP status is `400` for malformed shape and `422` for a well-formed object that fails domain validation.

### 9.12 Authentication failure

```json
{
  "code": "TOKEN_REVOKED",
  "message": "The device credential is no longer valid.",
  "requestId": "01991d79-5398-7be2-88c3-2dc9ceca8ba2"
}
```

HTTP status is `401`, with `WWW-Authenticate: Bearer`.

### 9.13 Member and invite flow

Create identity and membership:

```json
{
  "displayName": "Scout_C",
  "role": "VIEWER"
}
```

Response (`201`):

```json
{
  "memberId": "01991d7b-34f8-7f44-b54b-8ee0daa73924",
  "userId": "01991d7b-30f4-7444-8f21-63237cb35f17",
  "displayName": "Scout_C",
  "role": "VIEWER",
  "version": 1,
  "createdAt": "2026-09-01T11:45:00Z",
  "revokedAt": null
}
```

Create invite:

```http
POST /api/v1/workspaces/{workspaceId}/members/{memberId}/invites
Idempotency-Key: ce640945-0e1d-4d23-93b4-5ef3ed1b72d0
```

```json
{
  "expiresInHours": 72
}
```

Response (`201`) returns the secret once:

```json
{
  "inviteId": "01991d7c-c79d-7771-904d-72ce4212a75a",
  "inviteToken": "esm_inv_<secret-returned-once>",
  "memberId": "01991d7b-34f8-7f44-b54b-8ee0daa73924",
  "expiresAt": "2026-09-04T11:45:00Z",
  "createdAt": "2026-09-01T11:45:00Z"
}
```

Invite lists return metadata only: `inviteId`, `memberId`, creator, creation/expiry/use/revocation timestamps, and
status. They never return `inviteToken` or its hash.

## 10. Unified errors

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "One or more fields are invalid.",
  "requestId": "01991d77-1c82-73e4-a227-c6322363875f",
  "details": {}
}
```

`details` is optional and must not contain credentials, full notes, SQL, stack traces, connection strings, or other
users' private data.

| Code | HTTP | Meaning |
| --- | ---: | --- |
| `UNAUTHENTICATED` | 401 | Missing, malformed, or unknown bearer credential |
| `TOKEN_REVOKED` | 401 | Known Device Access Token was revoked |
| `TOKEN_EXPIRED` | 401 | Device Access Token expired |
| `FORBIDDEN` | 403 | Authenticated principal lacks current permission |
| `NOT_FOUND` | 404 | Resource is absent or intentionally hidden from this principal |
| `INVALID_ARGUMENT` | 400/422 | Shape or domain validation failed |
| `PAYLOAD_TOO_LARGE` | 413 | Body or field limit exceeded |
| `MARKER_ALREADY_EXISTS` | 409 | Workspace already has a marker for the system |
| `MARKER_VERSION_CONFLICT` | 409 | `expectedVersion` is stale |
| `MEMBER_VERSION_CONFLICT` | 409 | Membership update/delete version is stale |
| `LAST_ADMIN_REQUIRED` | 409 | Mutation would leave no active Admin |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | Mutation omitted the required header |
| `IDEMPOTENCY_KEY_REUSED` | 409 | Same key was used for a different request fingerprint |
| `INVITE_INVALID` | 401 | Invite is malformed or unknown |
| `INVITE_EXPIRED` | 401 | Invite expired |
| `INVITE_REVOKED` | 401 | Invite was revoked |
| `INVITE_ALREADY_USED` | 409 | Invite was already exchanged |
| `PROTOCOL_UNSUPPORTED` | 426 | Client/server protocol ranges do not overlap |
| `RATE_LIMITED` | 429 | Rate limit exceeded; response includes `Retry-After` |
| `INTERNAL_ERROR` | 500 | Safe generic server failure |

## 11. Optimistic locking

- Create starts at `version = 1`.
- Update requires `expectedVersion`; SQL updates only where both marker ID and version match, then increments version
  by exactly one in the same transaction.
- Delete requires `expectedVersion` as a query parameter and deletes only the matching version.
- Update and delete conflict return HTTP `409` with the current marker when it still exists.
- A missing marker returns `NOT_FOUND`; no last-write-wins path exists.
- Every successful marker create/update/delete increments the Workspace `revision` once in the same transaction.

## 12. Idempotency

Create, update, delete, member, role, invite, and device mutations require a client-generated UUID in
`Idempotency-Key`. Invite exchange is excluded because replaying a server-generated plaintext credential would
require persisting recoverable token plaintext.

The server stores for 24 hours:

- access-token ID;
- idempotency key;
- HTTP method plus normalized route;
- SHA-256 of the canonical request body and version query;
- final HTTP status and non-secret response body;
- creation and expiry time.

The same principal, key, and request fingerprint returns the original result without re-executing the mutation. The
same key with a different fingerprint returns `IDEMPOTENCY_KEY_REUSED`. In-progress duplicate requests serialize on
the same record. Expired records are hard deleted by a bounded cleanup job.

## 13. Polling and snapshot reconciliation

V1 deliberately uses complete snapshots:

1. After authentication and Workspace selection, fetch markers immediately.
2. While connected, fetch every 30 seconds. The interval is fixed in V1; manual `Refresh Now` is always available.
3. A successful create/update/delete merges the returned server object into the in-memory snapshot immediately.
4. The next poll performs final reconciliation.
5. A poll result atomically replaces the entire Workspace marker map and revision.

If local memory has A/B/C and the server returns A/C/D, one StateFlow publication contains A/C/D: B disappears, D
appears, and A/C are replaced by server versions. The Canvas never observes a partially reconciled map.

Complete snapshots are selected over delta streams and ETags because V1 caps a Workspace at 500 markers and polls
only every 30 seconds. This is simpler to test, makes deletion unambiguous, and avoids a persistent local cursor. V1
has no WebSocket, SSE, delta endpoint, or disk snapshot cache.

## 14. Write behavior in the client

- Create success: merge the server-returned marker; never retain a temporary client ID as final.
- Update success: replace the marker with the server-returned version.
- Delete success: remove the marker immediately.
- Conflict: retain the server marker, preserve the user's unsaved draft separately, and show an explicit merge/retry
  decision.
- Failed mutation: retain the last successful snapshot and show a bounded error; never invent a success.

## 15. Offline and authorization failure semantics

Connection state is one of:

- `DISCONNECTED`: no configured session or user explicitly disconnected;
- `CONNECTING`: metadata/auth/snapshot bootstrap in progress;
- `ONLINE`: latest operation succeeded;
- `DEGRADED`: transient failure after a successful current-session snapshot;
- `OFFLINE`: transient failure before any successful current-session snapshot;
- `AUTH_REQUIRED`: 401, expired, revoked, or unreadable secure credential;
- `FORBIDDEN`: membership no longer permits marker reads;
- `PROTOCOL_UNSUPPORTED`: version ranges do not overlap.

On a transient network/5xx failure, the client retains the current-session last successful snapshot, marks it stale,
shows `lastSuccessfulSyncAt`, disables writes, and retries on the next 30-second cycle. `Refresh Now` may retry sooner
but is locally debounced.

On 401, the client enters `AUTH_REQUIRED`, disables writes, and may continue displaying the current-session snapshot
with a prominent stale/auth-required indicator. It never writes that snapshot to disk. On restart there is no Shared
Marker snapshot until authentication and a fetch succeed.

On 403, the client refreshes `/api/v1/me`. A downgrade to Viewer immediately removes write controls. A revoked
membership clears the Shared snapshot and enters `FORBIDDEN`. Local and AI-owned marker actions remain unaffected.

## 16. Rate and resource limits

V1 uses an in-process token-bucket limiter suitable for one server instance:

| Class | Limit |
| --- | --- |
| Invite exchange | 5/minute/IP and 20/day/IP |
| Authenticated reads | 120/minute/token |
| Marker writes | 30/minute/token |
| Admin writes | 20/minute/token |
| Public health/meta | 60/minute/IP |

Additional limits:

- 32 KiB general request body; 4 KiB invite exchange;
- 500 Shared Markers per Workspace;
- 80 code points for marker name, Workspace name, user display name, and device name;
- 2,000 code points for notes;
- nine tags per marker;
- five active unused invites per membership;
- ten active Device Access Tokens per membership.

Limits are enforced server-side even if the UI also enforces them.

## 17. Security and privacy wire requirements

- Authorization is server-side for every request; hiding UI controls is not authorization.
- SQL values use prepared statements. User-provided text is never concatenated into SQL or logs.
- Bearer tokens and invites are accepted only in the Authorization header or documented JSON field, never URL query
  strings.
- Request logging redacts `Authorization`, `inviteToken`, access-token responses, token/invite hashes, and full marker
  notes. Audit metadata contains field names or changed-field sets, not whole notes.
- Error responses never contain exception text, SQL, stack traces, token prefixes beyond intentionally non-secret
  operator metadata, or database topology.
- Shared Marker data and backups are sensitive alliance data. TLS protects transit; V1 does not provide end-to-end
  encryption from one Map client to another, so the server operator and database administrator can read marker data.
- CORS is disabled by default; the desktop client is not a browser. CSRF cookies are not used.

## 18. NOT IN V1

Not in V1:

- RC2;
- Shared Wormholes;
- Shared Routes;
- Shared Planning Views;
- Shared Objectives;
- WebSocket, SSE, or delta streams;
- comments or attachments;
- presence or chat;
- Discord integration;
- ESI login;
- email/password registration, login, or password reset;
- AI access to Shared Markers;
- local disk cache of Shared snapshots;
- import/export;
- history restore;
- granular ACLs beyond Viewer/Editor/Admin.
