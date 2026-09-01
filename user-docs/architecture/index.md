# Architecture

<ExpandableImage
  src="/architecture-homepage.svg"
  alt="How Hermes-Relay connects: upstream Hermes owns standard chat, Manage and voice; the encouraged Relay extension fills current gaps for terminal, notifications, media, desktop tools, enhanced voice and Relay sessions; Device Control also needs the sideload build."
  caption="Select the diagram to inspect it at full size."
/>

## Connection Model

The app maintains independent connection paths — standard chat over the upstream
Dashboard Gateway, explicit API-only chat over Direct API, and
persistent WSS for Relay extensions. Relay is optional for the upstream standard
path but encouraged for the full current feature set; compatible upstream
surfaces take precedence as they ship.

For a compact shareable reference covering connection paths, transport boundaries, pairing/session lifecycle, and operator controls, see the [Relay Architecture Spec](/architecture/relay-architecture-spec).

<HermesFlow diagram="architecture" height="260px" />

| Path | Protocol | Server | Purpose |
|------|----------|--------|---------|
| Chat (standard) | WS | Dashboard origin (local target commonly `:9119`) | Gateway chat via `/api/ws` (`tui_gateway`) — live thinking/reasoning |
| Chat (Direct API) | HTTP/SSE | API Server `:8642` | API-only compatibility conversations via Sessions / runs / completions |
| Terminal | WS/WSS | Selected Dashboard origin · same-origin Relay ingress | Remote shell via tmux (Phase 2) |
| Bridge | WS/WSS | Selected Dashboard origin · same-origin Relay ingress | Device control via AccessibilityService + MediaProjection (Phase 3) |
| Notifications | WS/WSS | Selected Dashboard origin · same-origin Relay ingress | `NotificationListenerService` forwards posted notifications over a bounded channel |

The Relay process still owns one internal listener on `:8767`, but normal LAN,
Tailscale, and public clients reach it through the Dashboard plugin path on the
Dashboard origin. Direct external `:8767` is retained only for explicit legacy
compatibility. The older standalone `android_relay.py` service on port 8766 is retired.
For recommended Tailscale, the selected origin uses the helper-reported
dedicated HTTPS listener (`:10443` by default) and proxies the local Dashboard
target on `:9119`. The dedicated port avoids colliding with an existing
Traefik, Caddy, or nginx listener on `:443`.

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

Standard chats ride the `/api/ws` WebSocket (`GatewayChatClient`) and receive lifecycle events over JSON-RPC with live reasoning. The flow below is the explicit **Direct API** path for API-only/headless compatibility connections:

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

<HermesFlow diagram="auth-flow" height="220px" />

Pairing codes use the full `A-Z / 0-9` alphabet (36 chars). The pair command (`hermes pair`, `/hermes-relay-pair`, or the compatibility `hermes-pair` shell shim) on the Hermes host mints the code and pre-registers it with the relay via a loopback-only `/pairing/register` endpoint before embedding it in the QR — so the phone never types a code by hand. Session tokens are stored in EncryptedSharedPreferences backed by Android Keystore.

## Vanilla Hermes chat vs Relay

Chat uses vanilla upstream Hermes either way (Gateway for standard connections, Direct API for API-only compatibility); the relay is a separate, optional surface for bridge/terminal/notifications.

| Aspect | Vanilla Hermes Chat (Gateway / Direct API) | Relay (Bridge/Terminal/Notifications) |
|--------|----------------------------------------|------------------------|
| Protocol | WS (`/api/ws`) for Gateway · HTTP/SSE for Direct API | WSS |
| Connection | Persistent Gateway socket · per-request on Direct API | Persistent |
| Auth | Dashboard ws-ticket (Gateway) · API bearer token (Direct API) | Pairing code + session token. Voice endpoints may also accept the API bearer token. |
| Server | Hermes Dashboard origin · Hermes API `:8642` | Dashboard origin → local `:9119` → internal Relay `:8767` |
| Live reasoning | Yes on Gateway · post-hoc only on Direct API | — |
