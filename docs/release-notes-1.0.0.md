# EVE Static Map Planner 1.0.0 release candidate

This release candidate completes the final pre-1.0 planning and ESI upgrade without replacing the existing normal,
Ansiblex, or capital-routing engines.

## Planning Views

- The top map bar now keeps `Official 2D` and `Real X-Z` fixed beside a horizontally scrollable View strip.
- Views have stable internal IDs, unique editable labels, a compact create control, right-click Rename/Delete actions,
  and a one-View minimum.
- Each View restores its own user normal route, user capital route, AI Missions, and generic Route Action target.
- Projection, static data, Saved Markers, Feature Packs, sovereignty, connected characters, and all character locations
  remain global.
- Views and their route drafts, AI Missions, ownership, and selected target IDs are session-only. Restart always opens
  one blank `View 1`; `user.db` remains at stable schema 4.

## AI and localhost HTTP MCP

- The fixed catalog contains 28 tools, including list/get/create/rename/switch/delete View operations.
- Mission creation and listing accept an optional stable `viewId`; omission means the currently displayed View.
- The EVE Map Assistant resolves unique View labels case-insensitively and sends stable IDs to the Map.
- Plugin transport is unchanged: the running Map hosts `http://127.0.0.1:27892/mcp` on IPv4 loopback.

## Feature API 2.0.0 freeze

- Runtime remains `2` and artifact version remains `2.0.0`.
- Generic Route Action target snapshots, selections, and execution context support Pack-owned target IDs.
- Generic Overlay image/system markers support bounded image data, multiple image sectors, overflow counts, and tooltips.
- The public API remains independent of Compose, Core, HTTP, ESI, and other product-specific types.

## ESI Pack 1.0.0

- Multiple EVE characters can be added, reauthorized, restored, and disconnected independently.
- Refresh tokens, session metadata, access state, location cache, scopes, and backoff state remain isolated per character.
- One shared location scheduler manages all character due times; it does not create one location thread per character.
- Every connected character location is global and rendered with a cached portrait pin. Characters in one system share
  a segmented 1–4 portrait head with `+N` overflow, one pin tip, and a hover identity list.
- Each View stores one selected EVE character ID. `Send Draft to EVE` and `Set EVE Destination` share that selector.
  Disconnecting the selected character leaves it unavailable and never falls back to another identity.
- `Send Draft to EVE` still sends only the current View's user normal route and still rejects non-pure-Stargate routes.
  It never sends AI, capital, or Mission routes automatically.

## Distribution and compatibility

- Core: `1.0.0`
- Feature API: runtime `2`, artifact `2.0.0`
- ESI Pack: `1.0.0`
- Sovereignty Pack: `0.2.0` (unchanged)
- EVE Map Assistant Plugin: `0.5.0` (fixed localhost HTTP configuration unchanged)
- Windows x64 Portable ZIP remains the only Core distribution; Feature Pack JARs remain separate.

This candidate is intentionally not pushed, tagged, or published. Automated regression and the required human product
QA have passed. The cross-View behavior after disconnecting one selected character is covered by automation; manual QA
for that specific case was skipped by the user and is not a release blocker for this candidate. The validated source
and artifact set is ready for source migration before any final Git tag or GitHub release is created.
