# Hermes WebAPI Reference — For Hermes-Relay Chat Integration

> This document is the authoritative reference for how the Hermes WebAPI works.
> Use this when implementing chat, sessions, streaming, thinking, and tool-use
> features in the Hermes-Relay Android app.

## Architecture Overview

The Hermes gateway runs an **API server** (aiohttp) on port **8642** by default.
This is the `api_server` platform adapter inside `gateway/platforms/api_server.py`.

There is **no separate "gateway API key"** in the OpenClaw/OpenWebUI sense.
Authentication is handled via an **optional** Bearer token.

## Authentication

### How It Actually Works

The API server has **optional** Bearer token auth:

```
# Config source (in order of precedence):
# 1. gateway.json -> platforms.api_server.key
# 2. config.yaml -> platforms.api_server.key  
# 3. Environment variable: API_SERVER_KEY

# If no key is configured -> ALL requests are allowed (local-only use)
# If a key IS configured -> requests must include:
#   Authorization: Bearer <the-key>
```

**The key is NOT required for local use.** Most setups don't set one.
Only set it if the API server is exposed to the network.

### For the Android App

The settings screen should:
1. Have a "Server URL" field (e.g., `http://192.168.1.100:8642`)
2. Have an **optional** "API Key" field (Bearer token)
3. Label it "API Key (optional)" -- not "Gateway API Key"
4. Send it as: `Authorization: Bearer <key>` header on all requests
5. If blank, don't send the Authorization header at all

## API Endpoints

Base URL: `http://<host>:8642`

### Health Check
```
GET /health
-> { "status": "ok", "platform": "hermes-agent", "service": "webapi" }
```

### Models
```
GET /v1/models
-> { "object": "list", "data": [{ "id": "claude-opus-4-6", "object": "model" }, ...] }
```

### Sessions

```
# List sessions
GET /api/sessions?limit=50&offset=0
-> { "items": [...], "total": N }

# Create session
POST /api/sessions
Body: { "title": "My Chat", "model": "claude-opus-4-6" }
-> { "session": { "id": "sess_...", "title": "...", ... } }

# Get session
GET /api/sessions/{session_id}
-> { "session": { ... } }

# Update session
PATCH /api/sessions/{session_id}
Body: { "title": "New Title" }
-> { "session": { ... } }

# Delete session
DELETE /api/sessions/{session_id}

# Get messages
GET /api/sessions/{session_id}/messages
-> { "items": [...], "total": N }

# Search sessions
GET /api/sessions/search?q=keyword&limit=20
-> { "query": "...", "count": N, "results": [...] }

# Fork session
POST /api/sessions/{session_id}/fork
-> { "session": { ... }, "forked_from": "..." }
```

### Chat (Non-Streaming)
```
POST /api/sessions/{session_id}/chat
Content-Type: application/json
Body: {
  "message": "Hello",
  "model": "claude-opus-4-6",        // optional override
  "system_message": "...",            // optional ephemeral system prompt
  "enabled_toolsets": ["hermes-cli"], // optional
  "disabled_toolsets": [],            // optional
  "skip_context_files": false,        // optional
  "skip_memory": false,               // optional
  "attachments": [                    // optional image attachments
    {
      "contentType": "image/png",
      "content": "<base64-data>"
    }
  ]
}

-> {
  "session_id": "sess_...",
  "run_id": "run_...",
  "model": "claude-opus-4-6",
  "final_response": "The assistant's reply",
  "completed": true,
  "partial": false,
  "interrupted": false,
  "api_calls": 3,
  "messages": [...],         // full message array including tool calls
  "last_reasoning": "...",   // thinking/reasoning content if available
  "response_previewed": false
}
```

### Chat (Streaming) -- PRIMARY ENDPOINT FOR CHAT UI
```
POST /api/sessions/{session_id}/chat/stream
Content-Type: application/json
Body: (same as non-streaming chat above)

-> text/event-stream (SSE)
```

#### SSE Event Format

Every event follows this envelope:
```
event: <event_name>
data: {
  "session_id": "sess_...",
  "run_id": "run_...",
  "seq": 1,
  "ts": "2025-01-01T00:00:00.000Z",
  ...payload fields
}
```

#### SSE Event Types (in order of a typical chat flow):

1. **`session.created`** -- First event, confirms session
   ```json
   { "title": "My Chat", "cwd": null, "model": "claude-opus-4-6" }
   ```

2. **`run.started`** -- Agent run begins
   ```json
   { "user_message": { "id": "msg_user_...", "role": "user", "content": "Hello" } }
   ```

3. **`message.started`** -- Assistant message begins
   ```json
   { "message": { "id": "msg_asst_...", "role": "assistant" } }
   ```

4. **`assistant.delta`** -- Streaming text chunks (the main content)
   ```json
   { "message_id": "msg_asst_...", "delta": "Here is " }
   { "message_id": "msg_asst_...", "delta": "my response." }
   ```

5. **`tool.progress`** -- Thinking/reasoning content (when tool_name is `_thinking`)
   ```json
   { "message_id": "msg_asst_...", "delta": "Let me think about this..." }
   ```

6. **`tool.pending`** -- Tool about to execute
   ```json
   { "tool_name": "terminal", "preview": "Running command...", "args": { "command": "ls" } }
   ```

7. **`tool.started`** -- Tool execution started
   ```json
   { "tool_name": "terminal", "preview": "Running command...", "args": { "command": "ls" } }
   ```

8. **`tool.completed`** -- Tool finished successfully
   ```json
   { "tool_call_id": "tc_...", "tool_name": "terminal", "args": {...}, "result_preview": "file1.txt\nfile2.txt" }
   ```

9. **`tool.failed`** -- Tool errored
   ```json
   { "tool_call_id": "tc_...", "tool_name": "terminal", "args": {...}, "result_preview": "Error: ..." }
   ```

10. **`memory.updated`** -- Memory was modified
    ```json
    { "tool_name": "memory", "target": "user", "entry_count": 5, "message": "Added entry" }
    ```

11. **`skill.loaded`** -- A skill was loaded
    ```json
    { "tool_name": "skill_view", "name": "docker-management" }
    ```

12. **`artifact.created`** -- A file was created/modified
    ```json
    { "tool_name": "write_file", "path": "/home/user/output.txt" }
    ```

13. **`assistant.completed`** -- Final response ready
    ```json
    { "message_id": "msg_asst_...", "content": "Full final response text", "completed": true, "partial": false, "interrupted": false }
    ```

14. **`run.completed`** -- Run is done
    ```json
    { "message_id": "msg_asst_...", "completed": true, "partial": false, "interrupted": false, "api_calls": 3 }
    ```

15. **`error`** -- Something went wrong
    ```json
    { "message": "Error description" }
    ```

16. **`done`** -- Stream is finished (always the last event)

### Config
```
GET /api/config
-> { "model": "claude-opus-4-6", "provider": "anthropic", "api_mode": null, "base_url": null, "config": {...} }

PATCH /api/config
Body: { "model": "claude-sonnet-4-5" }
-> { "ok": true, "model": "claude-sonnet-4-5", "provider": "anthropic" }
```

### Memory
```
GET /api/memory?target=memory    // or target=user
-> { "target": "memory", "entries": [...], "usage": {...}, "entry_count": N }
```

### Skills
```
GET /api/skills
GET /api/skills/categories
GET /api/skills/{name}
```

## Capability Detection

Probe endpoints to detect what's available:

```
GET /health              -> basic connectivity
GET /api/sessions        -> enhanced Hermes session API
GET /v1/models           -> model listing (OpenAI-compatible)
GET /api/skills          -> skills support
GET /api/memory          -> memory support
GET /api/config          -> config API

Chat modes:
  "enhanced-hermes" -> sessions API available (use /api/sessions/*/chat/stream)
  "portable"        -> only /v1/chat/completions available (OpenAI-compatible)
  "disconnected"    -> nothing works
```

## Key Differences from OpenClaw/OpenWebUI

| Concept | OpenClaw/OpenWebUI | Hermes WebAPI |
|---------|-------------------|---------------|
| Auth | API key required, stored in settings | Optional Bearer token, most local setups have none |
| Chat endpoint | `/api/chat/completions` | `/api/sessions/{id}/chat/stream` (SSE) |
| Session management | Implicit | Explicit (create -> chat -> list) |
| Streaming format | OpenAI delta format | Custom SSE events (see above) |
| Tool visibility | Hidden | Exposed via events (pending/started/completed/failed) |
| Thinking/Reasoning | Not exposed | Exposed via `tool.progress` events |
| Memory/Skills | Not applicable | Full API access |
