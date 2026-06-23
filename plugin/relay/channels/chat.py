"""Chat channel handler — proxies between phone (WSS) and Hermes WebAPI (SSE).

The core flow:
  1. Phone sends ``chat.send`` with a message and optional profile/session_id
  2. We create a session on WebAPI if needed
  3. POST to ``/api/sessions/{id}/chat/stream`` — returns an SSE stream
  4. Parse SSE events and re-emit as WebSocket envelopes to the phone
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime
from typing import Any

import aiohttp
from aiohttp import web

logger = logging.getLogger(__name__)

# Mapping from Hermes WebAPI SSE event types to our envelope types.
# The WebAPI may use different names than our protocol — this layer
# absorbs that difference.
_SSE_TYPE_MAP: dict[str, str] = {
    # Current Hermes API-server event families
    "assistant.delta": "chat.delta",
    "tool.progress": "chat.progress",
    "tool.pending": "chat.tool.started",
    "tool.started": "chat.tool.started",
    "tool.completed": "chat.tool.completed",
    "tool.failed": "chat.tool.failed",
    "assistant.completed": "chat.turn.completed",
    "run.completed": "chat.completed",
    "done": "chat.completed",
    "error": "chat.error",
    # Historical aliases
    "content_delta": "chat.delta",
    "delta": "chat.delta",
    "thinking_delta": "chat.progress",
    "reasoning_delta": "chat.progress",
    "tool_start": "chat.tool.started",
    "tool_started": "chat.tool.started",
    "tool_result": "chat.tool.completed",
    "tool_completed": "chat.tool.completed",
    "content_complete": "chat.completed",
    "complete": "chat.completed",
    "completed": "chat.completed",
}

_TYPED_STREAM_EVENT_NAMES: set[str] = {
    "session.created",
    "run.started",
    "message.started",
    "assistant.delta",
    "tool.progress",
    "tool.pending",
    "tool.started",
    "tool.completed",
    "tool.failed",
    "memory.updated",
    "skill.loaded",
    "artifact.created",
    "assistant.completed",
    "run.completed",
    "error",
    "done",
}

_STABLE_EVENT_FIELDS = {"type", "event", "session_id", "run_id", "seq", "ts", "timestamp"}


def _make_envelope(
    msg_type: str,
    payload: dict[str, Any],
    msg_id: str | None = None,
) -> str:
    """Build a JSON envelope string for the chat channel."""
    return json.dumps(
        {
            "channel": "chat",
            "type": msg_type,
            "id": msg_id or str(uuid.uuid4()),
            "payload": payload,
        }
    )


def _iso_now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def _json_preview(value: Any, *, max_chars: int = 1600) -> Any:
    """Return a compact JSON-serializable preview suitable for native clients.

    Typed relay stream payloads deliberately avoid forwarding unbounded raw tool
    results. Dict/list structure is preserved where it is small; otherwise the
    payload falls back to a truncated string preview.
    """
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return value if len(value) <= max_chars else value[: max_chars - 1] + "…"
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str):
                continue
            lowered = key.lower()
            if lowered in {"api_key", "authorization", "password", "refresh_token", "session_token", "token", "secret"}:
                out[key] = "[REDACTED]"
            elif key in {"result", "output", "stdout", "stderr"}:
                out[f"{key}_preview"] = _json_preview(item, max_chars=max_chars)
            else:
                out[key] = _json_preview(item, max_chars=max_chars)
        return out
    if isinstance(value, list):
        return [_json_preview(item, max_chars=max_chars) for item in value[:20]]
    return _json_preview(str(value), max_chars=max_chars)


def _normalize_typed_payload(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        key: _json_preview(value)
        for key, value in raw.items()
        if key not in _STABLE_EVENT_FIELDS
    }


def _typed_stream_envelope(
    *,
    event: str,
    payload: dict[str, Any],
    session_id: str,
    run_id: str | None,
    seq: int,
    msg_id: str | None = None,
    ts: str | None = None,
) -> str:
    """Build the Relay WS wrapper around the versioned stream.event payload."""
    stream_payload: dict[str, Any] = {
        "type": "stream.event",
        "schema_version": 1,
        "session_id": session_id,
        "run_id": run_id,
        "seq": seq,
        "event": event,
        "ts": ts or _iso_now(),
        "payload": payload,
    }
    return _make_envelope("stream.event", stream_payload, msg_id)


def _supports_typed_stream_events(capabilities: dict[str, Any] | None) -> bool:
    if not isinstance(capabilities, dict):
        return False
    supports = capabilities.get("supports")
    if not isinstance(supports, dict):
        supports = capabilities
    if supports.get("typed_stream_events") is not True:
        return False
    version = supports.get("event_schema_version", supports.get("typed_stream_event_schema_version", 1))
    return version in (1, "1")


class ChatHandler:
    """Handles all ``chat.*`` messages from the phone.

    Uses an ``aiohttp.ClientSession`` to talk to the Hermes WebAPI.
    The HTTP session is created lazily and reused across requests.
    """

    def __init__(self, webapi_url: str = "http://localhost:8642") -> None:
        self._webapi_url = webapi_url.rstrip("/")
        self._http: aiohttp.ClientSession | None = None
        self._client_capabilities: dict[int, dict[str, Any]] = {}
        self._stream_seq: dict[str, int] = {}

    def set_client_capabilities(
        self, ws: web.WebSocketResponse, capabilities: dict[str, Any] | None
    ) -> None:
        """Record negotiated client capabilities for this websocket."""
        if capabilities:
            self._client_capabilities[id(ws)] = capabilities
        else:
            self._client_capabilities.pop(id(ws), None)

    def detach_ws(self, ws: web.WebSocketResponse) -> None:
        """Forget per-websocket stream state after disconnect."""
        ws_id = id(ws)
        self._client_capabilities.pop(ws_id, None)
        prefix = f"{ws_id}:"
        for key in [key for key in self._stream_seq if key.startswith(prefix)]:
            self._stream_seq.pop(key, None)

    def _next_seq(self, stream_key: str) -> int:
        seq = self._stream_seq.get(stream_key, 0) + 1
        self._stream_seq[stream_key] = seq
        return seq

    async def _get_http(self) -> aiohttp.ClientSession:
        """Return (and lazily create) the shared HTTP client session."""
        if self._http is None or self._http.closed:
            self._http = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=None, connect=10),
            )
        return self._http

    async def close(self) -> None:
        """Shut down the HTTP client session."""
        if self._http is not None and not self._http.closed:
            await self._http.close()
            self._http = None

    # ── Dispatcher ───────────────────────────────────────────────────────

    async def handle(
        self,
        ws: web.WebSocketResponse,
        envelope: dict[str, Any],
        client_capabilities: dict[str, Any] | None = None,
    ) -> None:
        """Route an incoming chat-channel envelope to the right handler."""
        if client_capabilities is not None:
            self.set_client_capabilities(ws, client_capabilities)
        msg_type = envelope.get("type", "")
        payload = envelope.get("payload", {})
        msg_id = envelope.get("id")

        if msg_type == "chat.send":
            await self._handle_send(ws, payload, msg_id)
        elif msg_type == "chat.sessions.list":
            await self._handle_sessions_list(ws, payload, msg_id)
        else:
            logger.warning("Unknown chat message type: %s", msg_type)
            await ws.send_str(
                _make_envelope(
                    "chat.error",
                    {"message": f"Unknown chat message type: {msg_type}"},
                    msg_id,
                )
            )

    # ── chat.send → stream response ─────────────────────────────────────

    async def _handle_send(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
        msg_id: str | None,
    ) -> None:
        """Process a chat.send message: create session if needed, then stream."""
        profile = payload.get("profile", "default")
        session_id = payload.get("session_id")
        message = payload.get("message", "")
        # attachments = payload.get("attachments", [])  # TODO: Phase 2+

        if not message:
            await ws.send_str(
                _make_envelope(
                    "chat.error",
                    {"message": "Empty message"},
                    msg_id,
                )
            )
            return

        http = await self._get_http()

        # If no session_id provided, create a new session
        if not session_id:
            session_id = await self._create_session(ws, http, profile, msg_id)
            if session_id is None:
                return  # Error already sent

        # POST to the streaming chat endpoint
        url = f"{self._webapi_url}/api/sessions/{session_id}/chat/stream"
        request_body: dict[str, Any] = {"message": message}

        # Include profile if the WebAPI supports it
        if profile and profile != "default":
            request_body["profile"] = profile

        logger.info(
            "Streaming chat: session=%s profile=%s message=%s",
            session_id,
            profile,
            message[:80],
        )

        try:
            async with http.post(
                url,
                json=request_body,
                headers={"Accept": "text/event-stream"},
            ) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    logger.error(
                        "WebAPI returned %d for chat stream: %s",
                        resp.status,
                        body[:200],
                    )
                    await ws.send_str(
                        _make_envelope(
                            "chat.error",
                            {
                                "message": f"WebAPI error ({resp.status}): {body[:200]}"
                            },
                            msg_id,
                        )
                    )
                    return

                await self._consume_sse_stream(ws, resp, session_id, msg_id)

        except aiohttp.ClientError as exc:
            logger.error("HTTP error talking to WebAPI: %s", exc)
            await ws.send_str(
                _make_envelope(
                    "chat.error",
                    {"message": f"Cannot reach Hermes WebAPI: {exc}"},
                    msg_id,
                )
            )
        except ConnectionResetError:
            logger.warning("WebSocket closed while streaming chat response")

    async def _create_session(
        self,
        ws: web.WebSocketResponse,
        http: aiohttp.ClientSession,
        profile: str,
        msg_id: str | None,
    ) -> str | None:
        """Create a new chat session via the WebAPI. Returns session_id or None."""
        url = f"{self._webapi_url}/api/sessions"
        body: dict[str, Any] = {}
        if profile and profile != "default":
            body["profile"] = profile

        try:
            async with http.post(url, json=body) as resp:
                if resp.status not in (200, 201):
                    text = await resp.text()
                    logger.error(
                        "Failed to create session (HTTP %d): %s",
                        resp.status,
                        text[:200],
                    )
                    await ws.send_str(
                        _make_envelope(
                            "chat.error",
                            {"message": f"Failed to create session: {text[:200]}"},
                            msg_id,
                        )
                    )
                    return None

                data = await resp.json()
                session_id = data.get("id") or data.get("session_id", "")
                title = data.get("title", "New chat")
                model = data.get("model", "unknown")

        except aiohttp.ClientError as exc:
            logger.error("HTTP error creating session: %s", exc)
            await ws.send_str(
                _make_envelope(
                    "chat.error",
                    {"message": f"Cannot reach Hermes WebAPI: {exc}"},
                    msg_id,
                )
            )
            return None

        # Notify the phone of the new session
        await ws.send_str(
            _make_envelope(
                "chat.session",
                {
                    "session_id": session_id,
                    "title": title,
                    "model": model,
                },
                msg_id,
            )
        )
        logger.info("Created session %s (title=%s)", session_id, title)
        return session_id

    # ── SSE stream consumer ──────────────────────────────────────────────

    async def _consume_sse_stream(
        self,
        ws: web.WebSocketResponse,
        resp: aiohttp.ClientResponse,
        session_id: str,
        msg_id: str | None,
    ) -> None:
        """Read an SSE stream from the WebAPI and forward events to the phone.

        SSE format: lines of ``data: {...}\\n\\n`` with optional ``event:`` lines.
        We parse manually to avoid adding a heavy SSE client dependency.
        """
        # Buffer for incomplete lines across chunk boundaries
        buffer = ""
        current_event_type: str | None = None

        async for chunk_bytes in resp.content.iter_any():
            if ws.closed:
                logger.info("WebSocket closed — stopping SSE consumption")
                return

            chunk = chunk_bytes.decode("utf-8", errors="replace")
            buffer += chunk

            # Process complete lines
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.rstrip("\r")

                if not line:
                    # Empty line = end of SSE event — reset event type
                    current_event_type = None
                    continue

                if line.startswith("event:"):
                    current_event_type = line[len("event:"):].strip()
                    continue

                if line.startswith("data:"):
                    data_str = line[len("data:"):].strip()
                    if not data_str:
                        continue
                    await self._emit_sse_event(
                        ws, data_str, session_id, msg_id, current_event_type
                    )
                    continue

                # Ignore comment lines (starting with :) and unknown prefixes
                if line.startswith(":"):
                    continue

                logger.debug("Ignoring unknown SSE line: %s", line[:100])

    async def _emit_sse_event(
        self,
        ws: web.WebSocketResponse,
        data_str: str,
        session_id: str,
        msg_id: str | None,
        sse_event_type: str | None,
    ) -> None:
        """Parse a single SSE ``data:`` value and send the corresponding WS envelope."""
        try:
            data = json.loads(data_str)
        except json.JSONDecodeError:
            # Some events may be plain text (notably "[DONE]").  Typed-capable
            # clients still receive a first-class final done event; legacy
            # clients keep the historical behavior (ignore bare [DONE]).
            if data_str.strip() == "[DONE]":
                if _supports_typed_stream_events(self._client_capabilities.get(id(ws))):
                    await self._emit_typed_stream_event(
                        ws,
                        raw_type="done",
                        data={"state": "final"},
                        session_id=session_id,
                        msg_id=msg_id,
                    )
                return
            logger.debug("Non-JSON SSE data: %s", data_str[:100])
            return

        # Determine the Hermes SSE event type. Try:
        # 1. The explicit ``event:`` line from SSE
        # 2. A ``type`` or ``event`` field inside the JSON data
        raw_type = sse_event_type or data.get("type") or data.get("event") or ""

        if _supports_typed_stream_events(self._client_capabilities.get(id(ws))):
            await self._emit_typed_stream_event(
                ws, raw_type=raw_type, data=data, session_id=session_id, msg_id=msg_id
            )
            return

        # Map to our legacy protocol type for text-only clients.
        proto_type = _SSE_TYPE_MAP.get(raw_type)

        if proto_type is None:
            # Unknown informational events are intentionally dropped in legacy
            # text mode to preserve old clients' behavior. Typed-capable clients
            # receive them losslessly above.
            logger.debug(
                "Unmapped SSE event type %r — skipped for legacy chat client", raw_type
            )
            return

        # Build payload based on event type
        payload = self._build_payload(proto_type, data, session_id)

        try:
            await ws.send_str(_make_envelope(proto_type, payload, msg_id))
        except ConnectionResetError:
            logger.warning("WebSocket closed while sending %s", proto_type)

    async def _emit_typed_stream_event(
        self,
        ws: web.WebSocketResponse,
        *,
        raw_type: str,
        data: dict[str, Any],
        session_id: str,
        msg_id: str | None,
    ) -> None:
        event = raw_type or data.get("type") or data.get("event") or "assistant.delta"
        if event not in _TYPED_STREAM_EVENT_NAMES and not event.startswith("relay."):
            logger.debug("Forwarding preview typed stream event: %s", event)
        run_id = data.get("run_id") if isinstance(data.get("run_id"), str) else None
        stream_key = f"{id(ws)}:{session_id}:{msg_id or 'no-msg'}"
        seq = data.get("seq")
        if not isinstance(seq, int):
            seq = self._next_seq(stream_key)
        ts_raw = data.get("ts") or data.get("timestamp")
        ts = ts_raw if isinstance(ts_raw, str) else None
        payload = _normalize_typed_payload(data)
        try:
            await ws.send_str(
                _typed_stream_envelope(
                    event=event,
                    payload=payload,
                    session_id=str(data.get("session_id") or session_id),
                    run_id=run_id,
                    seq=seq,
                    msg_id=msg_id,
                    ts=ts,
                )
            )
        except ConnectionResetError:
            logger.warning("WebSocket closed while sending typed stream event %s", event)

    @staticmethod
    def _build_payload(
        proto_type: str,
        data: dict[str, Any],
        session_id: str,
    ) -> dict[str, Any]:
        """Extract the relevant fields from SSE data into our protocol payload."""
        if proto_type == "chat.delta":
            return {
                "session_id": session_id,
                "message_id": data.get("message_id", data.get("id", "")),
                "delta": data.get("delta", data.get("content", "")),
            }

        if proto_type == "chat.tool.started":
            return {
                "tool_name": data.get("tool_name", data.get("name", "")),
                "preview": data.get("preview", ""),
                "args": data.get("args", data.get("arguments", {})),
            }

        if proto_type == "chat.tool.completed":
            return {
                "tool_name": data.get("tool_name", data.get("name", "")),
                "tool_call_id": data.get("tool_call_id", data.get("call_id", "")),
                "result_preview": data.get(
                    "result_preview", data.get("result", "")
                ),
                "success": data.get("success", True),
            }

        if proto_type == "chat.tool.failed":
            return {
                "tool_name": data.get("tool_name", data.get("name", "")),
                "tool_call_id": data.get("tool_call_id", data.get("call_id", "")),
                "error": data.get("error", data.get("message", "Tool failed")),
            }

        if proto_type == "chat.progress":
            return {
                "session_id": session_id,
                "message_id": data.get("message_id", data.get("id", "")),
                "delta": data.get("delta", data.get("thinking_delta", data.get("text", ""))),
            }

        if proto_type == "chat.turn.completed":
            return {
                "session_id": session_id,
                "message_id": data.get("message_id", data.get("id", "")),
                "content": data.get("content", ""),
                "completed": data.get("completed", True),
                "partial": data.get("partial", False),
                "interrupted": data.get("interrupted", False),
            }

        if proto_type == "chat.completed":
            return {
                "session_id": session_id,
                "message_id": data.get("message_id", data.get("id", "")),
                "content": data.get("content", ""),
                "api_calls": data.get("api_calls", 0),
            }

        if proto_type == "chat.error":
            return {
                "message": data.get("message", data.get("error", "Unknown error")),
            }

        # Fallback — return the raw data
        return data

    # ── chat.sessions.list ───────────────────────────────────────────────

    async def _handle_sessions_list(
        self,
        ws: web.WebSocketResponse,
        payload: dict[str, Any],
        msg_id: str | None,
    ) -> None:
        """Fetch session list from WebAPI and forward to the phone."""
        http = await self._get_http()
        url = f"{self._webapi_url}/api/sessions"
        params: dict[str, str] = {}
        profile = payload.get("profile")
        if profile:
            params["profile"] = profile

        try:
            async with http.get(url, params=params) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    logger.error(
                        "WebAPI returned %d for sessions list: %s",
                        resp.status,
                        body[:200],
                    )
                    await ws.send_str(
                        _make_envelope(
                            "chat.error",
                            {"message": f"Failed to list sessions: {body[:200]}"},
                            msg_id,
                        )
                    )
                    return

                data = await resp.json()

        except aiohttp.ClientError as exc:
            logger.error("HTTP error listing sessions: %s", exc)
            await ws.send_str(
                _make_envelope(
                    "chat.error",
                    {"message": f"Cannot reach Hermes WebAPI: {exc}"},
                    msg_id,
                )
            )
            return

        # The WebAPI may return sessions under different keys
        sessions = data if isinstance(data, list) else data.get("sessions", [])

        await ws.send_str(
            _make_envelope(
                "chat.sessions",
                {"sessions": sessions},
                msg_id,
            )
        )
        logger.info("Sent %d sessions to phone", len(sessions))
