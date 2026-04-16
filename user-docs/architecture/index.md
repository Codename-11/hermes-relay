# Architecture

## Connection Model

The app maintains two independent connection paths — direct HTTP/SSE for chat, persistent WSS for relay channels.

<HermesFlow diagram="architecture" height="260px" />

| Path | Protocol | Server | Purpose |
|------|----------|--------|---------|
| Chat | HTTP/SSE | API Server `:8642` | Streaming conversations via Sessions API |
| Terminal | WSS | Relay Server `:8767` | Remote shell via tmux (Phase 2) |
| Bridge | WSS | Relay Server `:8767` | Device control via AccessibilityService + MediaProjection (Phase 3) |
| Notifications | WSS | Relay Server `:8767` | `NotificationListenerService` forwards posted notifications over a bounded channel |

The bridge channel was consolidated onto the unified relay port `:8767` in v0.3 — the legacy standalone `android_relay.py` service on port 8766 is retired.

## Key Components

| Component | Purpose |
|-----------|---------|
| `HermesApiClient` | Direct HTTP/SSE client for Hermes API Server |
| `ChatHandler` | Message state management and streaming event processing |
| `ChatViewModel` | Session CRUD, message sending, personality selection |
| `ConnectionViewModel` | Dual connection model, API client lifecycle, settings |
| `ConnectionManager` | WebSocket connection for relay (bridge/terminal) |
| `ChannelMultiplexer` | Envelope routing for relay channels |
| `AuthManager` | API key and session token storage (encrypted) |
| `ConnectivityObserver` | Network connectivity monitoring |

## Chat Message Flow

<HermesFlow diagram="chat-flow" height="300px" />

1. User types a message in ChatScreen
2. ChatViewModel creates a session (if needed) via `POST /api/sessions`
3. Message sent via `POST /api/sessions/{id}/chat/stream`
4. HermesApiClient receives SSE events on OkHttp thread pool
5. Events dispatched to main thread via Handler
6. ChatHandler updates StateFlows (messages, streaming, tools)
7. Compose UI recomposes from StateFlow changes

## SSE Event Pipeline

The Hermes API Server streams events using Server-Sent Events. Each event type maps to a specific UI update.

<HermesFlow diagram="sse-events" height="340px" />

| Event | Handler Action |
|-------|---------------|
| `session.created` | Initialize session context (`session_id`, `run_id`, `title`) |
| `run.started` | Record run start, capture `user_message` object |
| `message.started` | Create assistant message placeholder from `message` object (`id`, `role`) |
| `assistant.delta` | Append text delta to streaming message |
| `tool.progress` | Append reasoning/thinking delta to message |
| `tool.pending` | Create tool progress card (queued state) |
| `tool.started` | Update card with start time, `preview`, `args` |
| `tool.completed` | Mark card as done with `result_preview` |
| `tool.failed` | Mark card as failed with `error` |
| `assistant.completed` | Finalize message (`content`, `completed`, `partial`, `interrupted` flags) |
| `run.completed` | End streaming state (`completed`, `partial`, `interrupted`, `api_calls`) |
| `error` | Display error banner (`message`, `error`) |
| `done` | Close SSE connection (`state: "final"`) |

## Relay Auth Flow

The relay connection (bridge/terminal) uses a pairing code for initial setup, then session tokens for persistence.

<HermesFlow diagram="auth-flow" height="200px" />

Pairing codes use the full `A-Z / 0-9` alphabet (36 chars). The pair command (`/hermes-relay-pair` skill or `hermes-pair` shell shim) on the Hermes host mints the code and pre-registers it with the relay via a loopback-only `/pairing/register` endpoint before embedding it in the QR — so the phone never types a code by hand. Session tokens are stored in EncryptedSharedPreferences backed by Android Keystore.

## Direct API vs Relay

| Aspect | Direct API (Chat) | Relay (Bridge/Terminal/Notifications) |
|--------|-------------------|------------------------|
| Protocol | HTTP/SSE | WSS |
| Connection | Per-request | Persistent |
| Auth | Bearer token (optional) | Pairing code + session token |
| Server | Hermes API `:8642` | Unified Relay `:8767` |
| State | Stateless | Channel-multiplexed |
