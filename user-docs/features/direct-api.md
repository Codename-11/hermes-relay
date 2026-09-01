# Vanilla Hermes Chat Transport

Hermes-Relay talks to your Hermes server's own surfaces for chat — no Hermes-Relay relay plugin is ever in the chat path. Standard connections use the dashboard **Gateway** (`/api/ws`, the same `tui_gateway` transport Hermes Desktop and the TUI speak), because that's the Vanilla Hermes path with live thinking/reasoning and full attachment support. That owner is stable: if sign-in expires or Gateway is temporarily unavailable, Android preserves the conversation and offers sign-in or retry instead of sending the turn to another database. Legacy API-only and explicitly selected **Direct API** chats keep using the API server's SSE routes.

## How It Works

```
Phone (WS)       → Hermes dashboard (:9119)    [standard — Gateway chat, live thinking]
Phone (HTTP/SSE) → Hermes API Server (:8642)   [Direct API — sessions / runs / completions]
```

Both paths are **vanilla upstream Hermes** surfaces. The dashboard gateway `/api/ws` is *not* the Hermes-Relay relay (`:8767`); it's a vanilla dashboard endpoint, reached with a short-lived ticket minted from your Manage dashboard session. The Relay plugin is not the owner of standard chat. It is the encouraged extension for current upstream gaps such as Terminal/TUI, notifications, media handoff, desktop tools, Relay sessions, enhanced voice, and optional Device Control; compatible upstream surfaces take precedence as they become available.

## Share into a new chat

Hermes Relay appears as a target when another Android app shares a link, text,
image, or file. Choosing it opens a new conversation for your currently active
profile, places shared text in the composer, and adds up to ten shared files as
reviewable attachments. Mixed text-and-file shares are supported; if more than
ten eligible files are shared, the app adds the first ten and tells you the rest
were omitted. Multi-text shares preserve each supplied text item in source order.
Nothing is sent automatically: edit, remove, reorder, or discard the draft, then
tap Send when it is ready.

For an API-only or explicitly selected Direct API chat, the app uses the Hermes `/api/sessions` REST API:

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

Current Hermes API-server deployments require a usable `API_SERVER_KEY` before
the API server starts. The app sends that server-created value as:

```
Authorization: Bearer <API_SERVER_KEY>
```

Direct API itself is optional. If you enable it, generate a strong
server-side key (for example, `openssl rand -hex 32`; upstream requires at least
16 characters) and enter the same value in Android.

When provided, the key is stored in Android's `EncryptedSharedPreferences` using AES-256-GCM encryption backed by the Android Keystore.

## SSE Streaming (Direct API path)

On Direct API, chat responses stream via Server-Sent Events with these Hermes-native event types. (On Gateway the same lifecycle arrives over the `/api/ws` WebSocket instead, with live `reasoning.delta`/`thinking.delta` as the model reasons — the API-server SSE surface only surfaces reasoning after the fact via `tool.progress` and the final `run.completed` messages.)

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

Chat always rides vanilla upstream Hermes — never the Hermes-Relay relay plugin. Gateway `/api/ws` is the standard owner because it provides live reasoning streaming and full attachment support, matching Hermes Desktop and the Hermes TUI. Direct API remains useful for existing API-only/headless deployments, compatibility testing, and an explicit advanced selection. The app probes both surfaces for diagnostics, but reachability never changes the owner of an open conversation.
