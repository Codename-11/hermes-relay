# Standard Chat Transport

Hermes-Relay talks to your Hermes server's own surfaces for chat — no Hermes-Relay relay plugin is ever in the chat path. By default it **prefers the dashboard gateway** (`/api/ws`, the same `tui_gateway` transport hermes-desktop and the TUI speak) when your Manage sign-in is ready, because that's the only standard path with **live thinking/reasoning** as it streams. When the gateway isn't available — no dashboard auth yet, an older server, or a forced override — it **falls back to the API server's SSE routes**.

## How It Works

```
Phone (WS)       → Hermes dashboard (:9119)    [preferred — gateway chat, live thinking]
Phone (HTTP/SSE) → Hermes API Server (:8642)   [fallback — sessions / runs / completions]
```

Both paths are **standard upstream Hermes** surfaces. The dashboard gateway `/api/ws` is *not* the Hermes-Relay relay (`:8767`); it's a vanilla dashboard endpoint, reached with a short-lived ticket minted from your Manage dashboard session. The optional Relay plugin is never involved in chat — it only adds terminal, device control, media, and the like.

When it falls back, the app uses the Hermes `/api/sessions` REST API:

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/api/sessions` | List sessions |
| POST | `/api/sessions` | Create session |
| GET | `/api/sessions/{id}/messages` | Get message history |
| POST | `/api/sessions/{id}/chat/stream` | Stream chat (SSE) |
| PATCH | `/api/sessions/{id}` | Rename session |
| DELETE | `/api/sessions/{id}` | Delete session |
| GET | `/health` | Health check |

## Authentication

If the Hermes server is configured with `API_SERVER_KEY`, the app sends:

```
Authorization: Bearer <API_SERVER_KEY>
```

The API key field in Settings is technically optional because Hermes can run an open local API server. For phone-reachable LAN, VPN, or public deployments, set `API_SERVER_KEY` and enter the same value in Android.

When provided, the key is stored in Android's `EncryptedSharedPreferences` using AES-256-GCM encryption backed by the Android Keystore.

## SSE Streaming (fallback path)

On the API-server fallback, chat responses stream via Server-Sent Events with these Hermes-native event types. (On the preferred gateway path the same lifecycle arrives over the `/api/ws` WebSocket instead, with live `reasoning.delta`/`thinking.delta` as the model reasons — the API-server SSE surface only surfaces reasoning after the fact via `tool.progress` and the final `run.completed` messages.)

| Event | Description | Key Fields |
|-------|-------------|------------|
| `session.created` | Session initialized | `session_id`, `run_id`, `title?` |
| `run.started` | Agent run begins | `session_id`, `run_id`, `user_message` (object) |
| `message.started` | Assistant message begins | `session_id`, `run_id`, `message` (object with `id`, `role`) |
| `assistant.delta` | Text content chunk | `session_id`, `run_id`, `message_id`, `delta` |
| `tool.progress` | Reasoning/thinking chunk | `session_id`, `run_id`, `message_id`, `delta` |
| `tool.pending` | Tool queued for execution | `session_id`, `run_id`, `tool_name`, `call_id` |
| `tool.started` | Tool execution started | `session_id`, `run_id`, `tool_name`, `call_id`, `preview?`, `args` |
| `tool.completed` | Tool finished successfully | `session_id`, `run_id`, `tool_call_id`, `tool_name`, `args`, `result_preview` |
| `tool.failed` | Tool execution failed | `session_id`, `run_id`, `call_id`, `tool_name`, `error` |
| `assistant.completed` | Response finished | `session_id`, `run_id`, `message_id`, `content`, `completed`, `partial`, `interrupted` |
| `run.completed` | Entire agent run finished | `session_id`, `run_id`, `message_id`, `completed`, `partial`, `interrupted`, `api_calls?` |
| `error` | Error occurred | `message`, `error` |
| `done` | Stream closed | `session_id`, `run_id`, `state: "final"` |

## Why two paths?

Chat always rides standard upstream Hermes — never the Hermes-Relay relay plugin. The gateway `/api/ws` path is preferred because it's the only standard surface with **live** reasoning streaming and full attachment support, matching what hermes-desktop and the Hermes TUI use. The API-server SSE path is the resilient fallback: it needs only the API server (no dashboard sign-in), works on older builds, and aligns with how other Hermes frontends talk to the API. The app probes both and picks the best available on each connect, so you get live thinking when your server can serve it and a working chat either way.
