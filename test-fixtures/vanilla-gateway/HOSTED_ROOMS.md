# Hosted-room contract lane (dependency-gated)

`hosted_room_response_loss` is a client-neutral, text-only protocol fixture for
hosted shared rooms. It extends the existing ticket/WebSocket lab; it does not
add a Relay route, an Android-specific protocol, a participant, or a scheduler.

## Source authority and integration gate

The wire contract comes from **`tui_gateway/methods_groups.py`**, including its
`@method`, `@_room_method`, and `_passthrough` registrations. The conformance
adapter also follows `gateway/hosted_rooms.py`,
`tui_gateway/hosted_room_service.py`, and `gateway/hosted_room_discussion.py` for
storage idempotency, server-owned actors, thread defaults, and textual mentions.
The hosted-room implementation is an upstream dependency, not functionality
implemented by this Relay fixture. The initial integration baseline was upstream
PR #98307 at `d8995c0a5c240186c3b5f8df3a94c6f94b4b06de`; local development may
include additional unmerged changes. This lane is **not evidence that hosted
rooms have shipped in clean current upstream**.

Manifest requirements:

- `gateway.groups_capabilities`: advertised protocol, service readiness,
  authority, feature names, and method catalog.
- `gateway.groups_send_idempotent`: nested payload forwarding, namespaced retry
  key, acceptance envelope, immutable-content conflict, lookup before insert,
  server-owned actor, thread default, and roster-handle mention resolution.
- `gateway.groups_state_log`: room listing, optional driver status, real
  `groups.log` passthrough, bounded exact-room sequence delta and cursor fields.

These contracts are opt-in through the manifest. The default conformance lane
retains every existing standard contract, and older manifests do not acquire a
hosted-room dependency. Unknown requirements still fail. No dirty-checkout,
remote-provenance, or fork-marker exception has been added. Missing hosted
sources fail the requested hosted checks; they are not treated as a pass or a
silent skip. The manifest's `conformance_dependency` is descriptive, not a bypass.

From the repository root, certification still requires an exact **clean**
upstream checkout:

```powershell
python scripts/check-gateway-scenario-conformance.py <clean-upstream-checkout> `
  --scenario-manifest test-fixtures/vanilla-gateway/vanilla_gateway/scenarios/hosted_room_response_loss.json
```

During development, source-only `audit_sources(root, HOSTED_CONTRACTS)` can be
called programmatically against the integration worktree. Such output is only a
**development source audit**, never clean-upstream certification. No gateway,
provider, credentials, or database is opened by the analyzer. Source inspection
does not prove native runtime execution or client rendering.

## Wire shapes

All calls use the existing JSON-RPC envelope
`{"jsonrpc":"2.0","id":1,"method":"groups.send","params":{...}}`.
Success is `{"jsonrpc":"2.0","id":1,"result":{...}}`; errors use the normal
`error: {code, message}` object (upstream may additionally supply `data.reason`).

| Method | Request params | Result |
|---|---|---|
| `groups.capabilities` | `{}` | `{protocol_version, driver, authority_gateway_id, features, methods, max_log_limit, ...}` |
| `groups.list` | `{limit?, offset?, include_disbanded?}` | `{rooms: [...], next_offset: number|null}` |
| `groups.state` | `{room_id, include_disbanded?}` | `{room, driver_status?}`; status is **not** nested under `room` |
| `groups.send` | `{room_id, event_id, payload: {text, thread_id?}}` | `{event, client_event_id, accepted: true, driver_started: true}` |
| `groups.log` | `{room_id, since_seq: 0, limit: 100, include_disbanded?}` | `{events: [...], cursor, latest_seq, has_more, authority: {gateway_id, epoch}}` |

The fixture advertises only implemented text-lane features and methods, not the
entire upstream capability catalog. `protocol_version` is 2 in this manifest.
Upstream can additionally advertise RoomLink, attachment, and process metadata;
clients must tolerate these additive fields.

- Send `@reviewer` in **`payload.text`**, resolving the handle from the room's
  stable roster. There is no separate `mentions` array in this Discussion
  payload. The fixture preserves the text; the actual host resolves recipients.
- Omit `thread_id` for a fresh thread. The inspected service defaults it to the
  canonical user event ID. Reply by supplying the existing `payload.thread_id`.
  This lane does not invent a reply-parent field unsupported by that source.
- Retry the **same client `event_id` and immutable payload** after response loss.
  The event ID returned by storage is `user:` plus the SHA-256 hex digest of the
  validated UTF-8 client key; it is not the client retry key. The receipt retains
  the original key separately as `client_event_id`.
- Events carry `{room_id, seq, event_id, kind, actor, authority_epoch, payload,
  created_at, idempotent}`. The inspected send service sets
  `kind: "message.user"` and `actor: {kind: "user", id: "desktop"}` even for a
  remote client. Do not substitute a mobile-supplied actor.
- A replay receipt has `event.idempotent: true` with the original `seq` and
  canonical event ID. Reading that stored row through `groups.log` returns
  `idempotent: false`; the flag describes the operation, not a persisted change.
- `since_seq` is exclusive, not an offset. Read from the returned `cursor` until
  `has_more` is false. An empty terminal page retains the caller's cursor.
- Invalid room/state/log calls, conflicting sends, and unavailable service use
  the inspected error codes: state `4114`, log `4112`, send conflict `4111`,
  invalid Discussion payload `5112`, unavailable worker `4123`.

## Declarative behavior and tests

`hosted_groups` declares one existing room, its capabilities and idle
`driver_status`, retry keys whose first accepted response is lost, and concrete
fresh-thread/reply request examples. Tests send those examples through the real
loopback ticket and WebSocket endpoints. The first configured send commits to the
fixture log and closes the socket before its response. A newly ticketed socket
can replay it without a second event, then send a reply in the same thread and
page through the authoritative room log. A changed retry payload must fail
without changing history. Unknown methods remain `-32601`.

```powershell
python -W error::ResourceWarning -m unittest discover -s test-fixtures/vanilla-gateway/tests -v
python -m unittest discover -s scripts/tests -p "*gateway*test.py" -v
$env:PYTHONPATH = "test-fixtures/vanilla-gateway"
python -m vanilla_gateway.cli hosted_room_response_loss --host 127.0.0.1 --port 8765
```

RED/GREEN regressions cover missing hosted discovery, response loss/replay,
immutable-content and room/cursor rejection, malformed declarations, unavailable
worker behavior, list pagination, opt-in requirement selection, registered
source-to-storage conformance, and rejection of acceptance/readiness drift.
Evidence remains bounded metadata only; message text and IDs are not logged.

## Limits

The fixture stores accepted events across sockets **in memory**, not across
process restarts. It scripts an idle driver status and never runs members. It
does not certify host restart durability, task scheduling, mention recipient
execution, native Claude/Codex sessions, attachments, multi-room authorization,
Compose rendering, Android background lifecycle, or physical devices. Creation,
archive, management, and media methods are not advertised by this scenario.
Those remain separate upstream/runtime/client acceptance lanes. No production
profiles, accounts, phone installations, or scheduled jobs are touched.
