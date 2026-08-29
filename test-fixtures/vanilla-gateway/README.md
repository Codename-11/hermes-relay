# Vanilla Gateway contract fixture

This is a deterministic, client-neutral test double for the standard upstream
Hermes Dashboard/Gateway chat contract. It runs a real aiohttp HTTP server and
WebSocket JSON-RPC endpoint; it does not call a model, require provider
credentials, or depend on Relay-only routes.

It is intended to be shared by Android instrumentation, Desktop, TUI, and ADB
certification runners. The fixture is a protocol peer, not an imitation client,
so client-specific assertions remain in each client's own test lane.

## Run

Python 3.11+ and `aiohttp` are required. From this directory:

```powershell
python -m unittest discover -s tests -v
python -m vanilla_gateway.cli terminal_gap_activate --host 127.0.0.1 --port 8765
```

For a physical Android device, expose the host fixture with `adb reverse` and
keep the fixture bound to loopback. A device runner can query the control
endpoints after exercising the production client:

- `GET /__fixture__/state` — scenario progress and counts.
- `GET /__fixture__/evidence` — bounded metadata-only protocol evidence.

The fixture creates no scheduled jobs or recurring runs.

Loopback HTTP/WS is intentionally available for local tests and `adb reverse`,
where traffic remains on the host/device loopback boundary. Any certification
that binds to a non-loopback interface or reaches the fixture remotely must use
HTTPS/WSS by supplying a certificate and private key together:

```powershell
python -m vanilla_gateway.cli terminal_gap_activate `
  --host 0.0.0.0 --port 8765 `
  --tls-cert path/to/certificate-chain.pem `
  --tls-key path/to/private-key.pem
```

The CLI fails closed if only one TLS argument is supplied. Certificate creation,
distribution, and trust installation are deliberately outside this fixture.

## Standard client surface

- `POST /api/auth/ws-ticket` mints a fresh, single-use 30-second ticket.
- `GET /api/ws?ticket=...` upgrades to WebSocket and sends `gateway.ready`.
- JSON-RPC methods: `session.create`, `session.resume`, `session.activate`,
  `session.active_list`, `prompt.submit`, and `session.interrupt` when the
  selected scenario enables them.
- `GET /api/sessions/{stored-id}/messages` returns persisted, paginated history
  and accepts the upstream `profile`, `limit`, `offset`, and `order` query shape.
- Unknown RPC methods return JSON-RPC `-32601`; wrong live/durable identities
  fail explicitly and never create a replacement session.

Tickets are random transport credentials, but scenario identities, messages,
event order, and state transitions are deterministic.

## Scenario format

Bundled scenarios are JSON under `vanilla_gateway/scenarios/`. An external JSON
path can also be passed to the CLI or `load_scenario`. Each turn contains an
ordered `steps` list using these operations:

| Operation | Purpose |
|---|---|
| `event` | Send a Gateway event with `exact`, `foreign`, or `unscoped` identity. |
| `persist` | Append authoritative Dashboard history rows. |
| `sleep` | Create a bounded deterministic ordering window (maximum 5 seconds). |
| `set_running` | Change the authoritative session running state. |
| `close` | Create a fixture-controlled socket gap without replaying later frames. |

An optional `active_list` object scripts process-wide live-runtime snapshots.
`supported: false` returns JSON-RPC `-32601`, matching an older Gateway.
`supported: true` returns each declared `snapshots` entry in order and retains
the final successful snapshot for later polls. Rows use upstream's
`starting`/`working`/`waiting`/`idle` vocabulary. A successful empty snapshot
is therefore distinct from a failed or unsupported refresh.

Every bundled manifest also declares a top-level `contract_requirements` string
array. Its values use the contract names accepted by the on-demand upstream
conformance adapter (for example, `gateway.settled_session_info` and
`gateway.session_activate_live`). Client runners consume the scenario steps;
the upstream adapter consumes this requirements array from the same file.

The initial catalog covers ordinary streaming, rapid chunks/reasoning/tool
events, queued turns, scoped and foreign/unscoped inputs, persisted history,
and both issue #365 terminal-gap forms:

- `subagent_child_preview`: interleaved concurrent child lifecycle events carry
  stable child/session identity, thinking/progress/tool previews, and distinct
  completed/interrupted terminal states. Its upstream requirement also proves
  the vanilla lazy child-session watch contract used by read-only clients.

- `active_status_lifecycle`: one successful live snapshot contains starting,
  working, waiting, and idle rows; the next successful snapshot is empty so a
  client can prove a complete, unambiguously resolved snapshot clears prior
  live state.
- `active_status_profile_scope`: a process-wide row has no profile metadata and
  ignores a caller-supplied profile hint. Client adapters must resolve it from
  exact foreground/detached ownership already held by that client (or future
  explicit upstream profile metadata); a bounded directory must not invent an
  owner from apparent uniqueness.
- `active_status_unsupported`: `session.active_list` returns method-not-found so
  older-host fallback remains explicit rather than being mistaken for Idle.

- `terminal_gap_activate`: live deltas arrive, history persists, the socket
  closes before `message.complete`, and replacement `session.activate` reports
  the exact live session with `running=false`. A bounded two-second fixture delivery
  window after the delta ensures the device has observed live-turn proof before
  the deliberate close; it is test orchestration, not client recovery timing.
- `terminal_gap_session_info`: live deltas and history are followed by an exact-
  session `session.info {running:false}` without `message.complete`.

## Evidence and privacy

Evidence is a fixed-size ring buffer. Entries contain only sequence number,
connection number, RPC method, event type, identity-scope classification, and
outcome. RPC parameters, prompts, message payloads, credentials, URLs,
hostnames, headers, and local paths are never recorded. Scenario content is
returned only through the intentionally scripted wire/history surfaces.

## Planned lanes (not scheduled)

The fixture is ready to be invoked manually or by CI configuration added later.
Potential future lanes are emulator instrumentation, current-upstream
conformance, Desktop/TUI adapters, and physical-device ADB certification. This
package does not configure timers, cron jobs, scheduled workflows, or mandatory
device runs.
