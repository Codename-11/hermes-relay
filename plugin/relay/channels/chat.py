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
from typing import Any

import aiohttp
from aiohttp import web

logger = logging.getLogger(__name__)

# Mapping from Hermes WebAPI SSE event types to our envelope types.
# The WebAPI may use different names than our protocol — this layer
# absorbs that difference.
_SSE_TYPE_MAP: dict[str, str] = {
    "content_delta": "chat.delta",
    "delta": "chat.delta",
    "tool_start": "chat.tool.started",
    "tool_started": "chat.tool.started",
    "tool_result": "chat.tool.completed",
    "tool_completed": "chat.tool.completed",
    "content_complete": "chat.completed",
    "complete": "chat.completed",
    "completed": "chat.completed",
    "error": "chat.error",
}


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


class ChatHandler:
    """Handles all ``chat.*`` messages from the phone.

    Uses an ``aiohttp.ClientSession`` to talk to the Hermes WebAPI.
    The HTTP session is created lazily and reused across requests.
    """

    def __init__(self, webapi_url: str = "http://localhost:8642") -> None:
        self._webapi_url = webapi_url.rstrip("/")
        self._http: aiohttp.ClientSession | None = None

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

    async def handle(self, ws: web.WebSocketResponse, envelope: dict[str, Any]) -> None:
        """Route an incoming chat-channel envelope to the right handler."""
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
            # Some events may be plain text (e.g. "[DONE]")
            if data_str.strip() == "[DONE]":
                return
            logger.debug("Non-JSON SSE data: %s", data_str[:100])
            return

        # Determine the Hermes SSE event type. Try:
        # 1. The explicit ``event:`` line from SSE
        # 2. A ``type`` field inside the JSON data
        raw_type = sse_event_type or data.get("type", "")

        # Map to our protocol type
        proto_type = _SSE_TYPE_MAP.get(raw_type)

        if proto_type is None:
            # If we don't recognize the type, log it and skip
            logger.debug(
                "Unmapped SSE event type %r — forwarding raw data", raw_type
            )
            return

        # Build payload based on event type
        payload = self._build_payload(proto_type, data, session_id)

        try:
            await ws.send_str(_make_envelope(proto_type, payload, msg_id))
        except ConnectionResetError:
            logger.warning("WebSocket closed while sending %s", proto_type)

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
                "result_preview": data.get(
                    "result_preview", data.get("result", "")
                ),
                "success": data.get("success", True),
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
