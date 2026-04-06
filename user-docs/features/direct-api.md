# Direct API Connection

Hermes Relay connects directly to the Hermes API Server for chat, bypassing the relay server entirely.

## How It Works

```
Phone (HTTP/SSE) → Hermes API Server (:8642)
```

The app uses the Hermes `/api/sessions` REST API:

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

Most local Hermes setups don't require a key. The API key field in Settings is optional.

When provided, the key is stored in Android's `EncryptedSharedPreferences` using AES-256-GCM encryption backed by the Android Keystore.

## SSE Streaming

Chat responses stream via Server-Sent Events with these Hermes-native event types:

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

## Why Direct?

Previous versions routed chat through a WebSocket relay. Direct connection is simpler, has lower latency, and aligns with how every other Hermes frontend works (Open WebUI, ClawPort, etc.).
