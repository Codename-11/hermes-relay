# Relay API Contract

The Relay server is an optional additive surface. It does not carry normal
Hermes chat and is never required for the Vanilla Hermes path.

**Base URL:** `ws(s)://<server>:8767` for the multiplexed connection, with the
matching `http(s)://` origin for HTTP routes.

## Authentication classes

| Label | Caller and credential |
|---|---|
| **Public probe** | No credential; limited to health and initial pairing behavior |
| **Loopback** | Hermes-host process from `127.0.0.1` or `::1` only |
| **Paired** | Relay session bearer plus the required per-channel grant |
| **API voice** | Valid Hermes API bearer, accepted only for documented `/voice/*` routes; HTTPS required remotely |
| **Sideload** | Paired route additionally gated by the Android sideload capability |

::: danger Relay bearer scope
Never reuse a Relay session token against the upstream API Server or Dashboard.
Never accept the Hermes API bearer on terminal, media, desktop, profile-write,
phone-control or general Relay session routes.
:::

## Connection and pairing

| Method | Route | Auth | Purpose |
|---|---|---|---|
| `GET` upgrade | `/ws`, `/` | Pairing code or paired session | Multiplexed phone connection |
| `GET` | `/health` | Public probe | Version, readiness and connected-client summary |
| `POST` | `/pairing` | Rate-limited public setup | Create a relay-side pairing code |
| `POST` | `/pairing/register` | Loopback | Register a host-minted code and policy metadata |
| `POST` | `/pairing/mint` | Loopback | Mint a signed QR payload for dashboard/CLI pairing |
| `POST` | `/pairing/approve` | Loopback, reserved | Future host-approval direction |

Pairing codes are short-lived and one-shot. A successful pair produces the
longer-lived Relay session and its clamped grants.

## Paired sessions and channels

| Method | Route family | Auth | Purpose |
|---|---|---|---|
| `GET/PATCH/DELETE` | `/sessions`, `/sessions/{token_prefix}` | Paired | Inspect, extend, re-grant or revoke paired devices |
| `POST` | `/clipboard/inbox` | Paired | Clipboard rendezvous |
| `GET` upgrade | WebSocket channel `terminal` | `terminal` grant | Remote shell stream |
| `GET` upgrade | WebSocket channel `tui` | `tui` grant | TUI relay stream |
| multiplexed | WebSocket channel `bridge` | `bridge` grant | Phone command and response envelopes |

## Media

| Method | Route | Auth | Purpose |
|---|---|---|---|
| `POST` | `/media/register` | Loopback | Register an allowed host file and return an opaque token |
| `POST` | `/media/upload` | Paired | Upload bounded phone-originated media |
| `GET` | `/media/{token}` | Paired | Fetch registered media without exposing its host path |
| `GET` | `/media/by-path` | Paired | Fetch a sandbox-approved absolute path emitted by Hermes |
| `GET` | `/media/inspect` | Loopback | Operator inspection of the media registry |

All file routes resolve real paths under configured allowed roots and reject
symlink escapes.

## Relay voice

Every route below is Relay-owned. Vanilla Hermes voice uses Dashboard
`/api/audio/*` instead.

| Route family | Methods | Auth | Stability |
|---|---|---|---|
| `/voice/config` | `GET` | Paired voice grant or API voice | Stable discovery |
| `/voice/transcribe` | `POST` | Paired `voice:stt` or API voice | Stable fallback STT |
| `/voice/synthesize` | `POST` | Paired `voice:tts` or API voice | Stable fallback TTS |
| `/voice/output/*` | `GET/PATCH/POST/WS` | Paired voice grant or API voice | Streaming output |
| `/voice/realtime/*` | `GET/PATCH/POST/WS` | Paired realtime grant or API voice | Experimental provider lab |
| `/voice/realtime-agent/*` | `GET/PATCH/POST/WS` | Paired realtime grant or API voice | Experimental broker |

Streaming sessions upgrade at `/voice/output/{session_id}`,
`/voice/realtime/{session_id}`, or `/voice/realtime-agent/{session_id}` after
the matching session-creation route returns an id.

Non-loopback API-bearer calls require HTTPS unless the operator explicitly
enables the temporary insecure-LAN escape hatch.

## Desktop, notifications and phone messaging

| Route family | Auth | Purpose |
|---|---|---|
| `/desktop/_ping`, `/desktop/health`, `/desktop/{tool_name}` | Paired desktop grant | Desktop CLI tool dispatch and readiness |
| `/notifications/recent` | Loopback or paired | Bounded recent notification companion state |
| `/phone/message`, `/phone/replies` | Platform/paired path | Agent-to-phone messages and phone replies |
| `/phone/outbound`, `/phone/threads` | Paired | Outbound queue and conversation state |
| `/context/injected` | Paired | Inspect bounded Relay-injected context |

## Profiles and Relay-owned files

Routes on port `8767` under `/api/profiles/{name}/*` are Relay power surfaces,
not the upstream Dashboard profile API:

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/api/profiles/{name}/config` | Relay-visible profile configuration |
| `GET` | `/api/profiles/{name}/avatar` | Profile avatar asset |
| `GET` | `/api/profiles/{name}/skills` | Profile skill inventory |
| `GET/PUT` | `/api/profiles/{name}/soul` | Paired profile SOUL access |
| `GET` | `/api/profiles/{name}/memory` | Profile memory inventory |
| `PUT` | `/api/profiles/{name}/memory/{filename}` | Paired profile memory-file write |

Prefer upstream Dashboard routes for standard profile and Manage behavior.

## Device Control

Device Control HTTP routes proxy through the connected phone's bridge channel.
They require a paired sideload phone reporting
`bridge.device_control_supported=true`.

| Group | Representative routes | Gate |
|---|---|---|
| Read | `/screen`, `/screenshot`, `/find_nodes`, `/screen_hash` | Sideload + enabled service |
| Gestures | `/tap`, `/tap_text`, `/swipe`, `/drag`, `/scroll` | Sideload + safety pipeline |
| Input and navigation | `/type`, `/press_key`, `/open_app`, `/return_to_hermes` | Sideload + safety pipeline |
| Phone utilities | `/location`, `/search_contacts`, `/call`, `/send_sms`, `/send_mms` | Sideload + capability permission |
| Events and media | `/events`, `/events/stream`, `/media`, `/share_media` | Route-specific gate |

The Google Play build fails closed before AccessibilityService-dependent work.
Direct commands return structured `403` responses instead of silently
degrading.

## Operator routes

| Method | Route | Auth | Purpose |
|---|---|---|---|
| `GET` | `/relay/info` | Loopback | Relay build and capability summary |
| `GET/PATCH` | `/relay/security` | Loopback | Runtime security toggles |
| `GET` | `/bridge/status` | Loopback | Device, bridge and safety state |
| `GET` | `/bridge/activity` | Loopback | Recent bridge activity |
| `GET` | `/bridge/devices` | Loopback | Connected bridge-device inventory |
| `POST` | `/bridge/select-active` | Loopback | Select the active bridge device |
| `GET` | `/relay/update-check` | Loopback | Plugin update status |

For deployment, environment variables, exact request shapes, the complete
Device Control route table and troubleshooting, see
[Relay server operations](./relay-server.html).
