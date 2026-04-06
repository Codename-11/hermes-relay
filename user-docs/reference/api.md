# Hermes API Reference

Hermes Relay communicates with the Hermes API Server using the following endpoints. If the server is configured with `API_SERVER_KEY`, requests must include a Bearer token in the `Authorization` header. Most local setups don't require a key.

## Base URL

```
http(s)://<server>:8642
```

## Authentication

```
Authorization: Bearer <API_SERVER_KEY>   (optional — only if server has a key configured)
```

## Endpoints

### Health Check

```
GET /health
```

Returns server health status. Used by the app to verify connectivity (green/red dot indicator).

**Response:** `200 OK` with server info.

---

### List Sessions

```
GET /api/sessions
```

Returns all chat sessions.

**Response:**
```json
{
  "sessions": [
    {
      "id": "uuid",
      "title": "Session title",
      "created_at": "2026-04-05T12:00:00Z",
      "updated_at": "2026-04-05T12:30:00Z",
      "message_count": 12
    }
  ]
}
```

---

### Create Session

```
POST /api/sessions
```

Creates a new chat session.

**Request body:**
```json
{
  "title": "Optional title"
}
```

**Response:** `201 Created` with the new session object.

---

### Get Messages

```
GET /api/sessions/{id}/messages
```

Returns message history for a session.

**Response:**
```json
{
  "messages": [
    {
      "id": "uuid",
      "role": "user | assistant",
      "content": "Message text",
      "created_at": "2026-04-05T12:00:00Z",
      "usage": {
        "input_tokens": 150,
        "output_tokens": 847
      }
    }
  ]
}
```

---

### Stream Chat

```
POST /api/sessions/{id}/chat/stream
```

Sends a message and streams the response via Server-Sent Events.

**Request body:**
```json
{
  "message": "Your message here",
  "personality": "default"
}
```

**Response:** SSE stream with the following event types:

| Event | Data |
|-------|------|
| `session.created` | `{ "session_id": "...", "run_id": "...", "title": "..." }` |
| `run.started` | `{ "session_id": "...", "run_id": "...", "user_message": { "id": "...", "role": "user", "content": "..." } }` |
| `message.started` | `{ "session_id": "...", "run_id": "...", "message": { "id": "...", "role": "assistant" } }` |
| `assistant.delta` | `{ "session_id": "...", "run_id": "...", "message_id": "...", "delta": "text chunk" }` |
| `tool.progress` | `{ "session_id": "...", "run_id": "...", "message_id": "...", "delta": "reasoning chunk" }` |
| `tool.pending` | `{ "session_id": "...", "run_id": "...", "tool_name": "...", "call_id": "..." }` |
| `tool.started` | `{ "session_id": "...", "run_id": "...", "tool_name": "...", "call_id": "...", "preview": "...", "args": {...} }` |
| `tool.completed` | `{ "session_id": "...", "run_id": "...", "tool_call_id": "...", "tool_name": "...", "args": {...}, "result_preview": "..." }` |
| `tool.failed` | `{ "session_id": "...", "run_id": "...", "call_id": "...", "tool_name": "...", "error": "..." }` |
| `assistant.completed` | `{ "session_id": "...", "run_id": "...", "message_id": "...", "content": "...", "completed": true, "partial": false, "interrupted": false }` |
| `run.completed` | `{ "session_id": "...", "run_id": "...", "message_id": "...", "completed": true, "partial": false, "interrupted": false, "api_calls": 3 }` |
| `error` | `{ "message": "error description", "error": "..." }` |
| `done` | `{ "session_id": "...", "run_id": "...", "state": "final" }` |

---

### Rename Session

```
PATCH /api/sessions/{id}
```

Updates a session's title.

**Request body:**
```json
{
  "title": "New title"
}
```

---

### Delete Session

```
DELETE /api/sessions/{id}
```

Permanently deletes a session and all its messages.

**Response:** `204 No Content`
