# Upstream Hermes Contract

This page documents the upstream routes Hermes-Relay actually consumes. It is
not a complete reference for Hermes Agent.

::: info Vanilla path
Everything on this page works without installing the Relay plugin. Runtime
capabilities win over version assumptions.
:::

## API Server

**Base URL:** `http(s)://<server>:8642`

When `API_SERVER_KEY` is configured, send:

```http
Authorization: Bearer <API_SERVER_KEY>
```

| Method | Route | Hermes-Relay use | Availability |
|---|---|---|---|
| `GET` | `/health` | Connectivity indicator | Upstream |
| `GET` | `/v1/capabilities` | Route and feature discovery | Upstream; probe first |
| `GET` | `/v1/models` | Authentication validation and model inventory | Upstream |
| `GET` | `/v1/skills` | Read-only skill discovery | Capability-gated |
| `GET` | `/v1/toolsets` | Read-only toolset discovery | Capability-gated |
| `POST` | `/v1/chat/completions` | OpenAI-compatible chat fallback | Fallback |
| `POST` | `/v1/runs` | Structured-run fallback | Fallback |
| `GET` | `/v1/runs/{run_id}/events` | Structured run event stream | Fallback |
| `POST` | `/v1/runs/{run_id}/approval` | Approve a pending run action | Capability-gated |
| `POST` | `/v1/runs/{run_id}/stop` | Stop a running turn | Capability-gated |

### Native session API

The native session surface is the preferred API-server chat path when the
dashboard Gateway is unavailable.

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/api/sessions` | List sessions |
| `POST` | `/api/sessions` | Create a session |
| `GET` | `/api/sessions/{id}` | Read one session |
| `PATCH` | `/api/sessions/{id}` | Rename or update client-safe metadata |
| `DELETE` | `/api/sessions/{id}` | Delete a session |
| `GET` | `/api/sessions/{id}/messages` | Read message history |
| `POST` | `/api/sessions/{id}/fork` | Branch a session from existing lineage |
| `POST` | `/api/sessions/{id}/chat` | Run one synchronous persisted turn |
| `POST` | `/api/sessions/{id}/chat/stream` | Run one persisted turn over SSE |

The Android client accepts current list envelopes and older compatibility
shapes, but new integrations should follow the response shapes advertised by
the running server's capability contract.

### Session stream events

The SSE stream carries lifecycle events rather than one untyped text channel.
Hermes-Relay currently handles these families:

| Family | Events |
|---|---|
| Session and run | `session.created`, `run.started`, `run.completed` |
| Assistant | `message.started`, `assistant.delta`, `assistant.completed` |
| Tools and reasoning | `tool.progress`, `tool.pending`, `tool.started`, `tool.completed`, `tool.failed` |
| Terminal state | `error`, `done` |

Clients must ignore unknown event types so upstream can add events without
breaking older Hermes-Relay releases.

## Dashboard & Gateway

**Base URL:** `http(s)://<server>:9119`

Dashboard routes use the dashboard login session. They do not accept the API
Server bearer unless upstream explicitly documents that route otherwise.

### Authentication and chat

| Method | Route | Hermes-Relay use |
|---|---|---|
| `GET` | `/api/status` | Dashboard reachability and feature status |
| `GET` | `/api/auth/me` | Current Manage session |
| `POST` | `/api/auth/ws-ticket` | Mint a short-lived Gateway WebSocket ticket |
| `WS` | `/api/ws` | Preferred chat transport with live reasoning and session events |

The WS ticket is short-lived and scoped to the Gateway connection. Do not store
it as a replacement for the dashboard session.

### Manage surfaces

Hermes-Relay consumes selected Dashboard routes for the Manage UI. Route
availability varies with upstream version, so the app treats `404` as an
unsupported capability rather than silently substituting a Relay write.

| Route family | Purpose |
|---|---|
| `/api/config`, `/api/config/schema` | Configuration and safe field metadata |
| `/api/model/*` | Model options, defaults and selection |
| `/api/profiles/*` | Profiles, active profile, SOUL and profile-scoped state |
| `/api/env` | Server-owned environment configuration |
| `/api/mcp/*` | MCP servers, catalog, testing and authentication |
| `/api/cron/*` | Jobs, runs, delivery targets and controls |
| `/api/sessions/*` | Dashboard/profile-aware session history, export and cleanup |

### Vanilla Hermes voice

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/audio/transcribe` | Speech to text |
| `POST` | `/api/audio/speak` | One-shot text to speech; request body is `{ "text": "..." }` |
| `GET` | `/api/audio/elevenlabs/voices` | ElevenLabs voice choices when configured |

These routes are upstream Dashboard routes. Every `/voice/*` route belongs to
the optional Relay server instead.

## Failure and fallback rules

- A failed dashboard login does not invalidate API-server chat.
- A missing `/api/ws` falls back to native session SSE when advertised.
- A missing native session API falls back to completions or runs.
- A `404` on a Manage route means unsupported; it does not authorize a Relay
  compatibility write.
- Profile-aware session history must stay on the dashboard/profile-aware
  transport rather than being mixed with a different database surface.

See [Compatibility](./compatibility.html) for older-host routes and
[Relay API](./relay-api.html) for plugin-owned features.
