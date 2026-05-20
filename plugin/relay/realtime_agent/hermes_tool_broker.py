"""Hermes session/tool broker used by realtime voice agent mode."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, AsyncIterator

import aiohttp

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class HermesTaskRequest:
    text: str
    profile: str | None
    session_id: str | None
    bearer_token: str | None = None


class HermesToolBroker:
    """Small brokered Hermes tool/session surface for realtime providers."""

    def __init__(self, webapi_url: str = "http://localhost:8642") -> None:
        self.webapi_url = webapi_url.rstrip("/")

    async def stream_task(
        self,
        request: HermesTaskRequest,
    ) -> AsyncIterator[dict[str, Any]]:
        timeout = aiohttp.ClientTimeout(total=None, connect=10)
        headers = _headers(request.bearer_token)
        try:
            async with aiohttp.ClientSession(timeout=timeout) as http:
                session_id = request.session_id
                if not session_id:
                    session_id = await self._create_session(http, request, headers)
                    yield {
                        "type": "hermes.session.bound",
                        "session_id": session_id,
                        "profile": request.profile,
                    }

                yield {
                    "type": "hermes.run.started",
                    "session_id": session_id,
                    "profile": request.profile,
                    "tool_surface": _TOOL_SURFACE,
                }

                body: dict[str, Any] = {"message": request.text}
                if request.profile and request.profile != "default":
                    body["profile"] = request.profile
                url = f"{self.webapi_url}/api/sessions/{session_id}/chat/stream"
                async with http.post(
                    url,
                    json=body,
                    headers={**headers, "Accept": "text/event-stream"},
                ) as resp:
                    if resp.status != 200:
                        text = await resp.text()
                        yield {
                            "type": "voice.error",
                            "message": f"Hermes API error ({resp.status}): {text[:240]}",
                            "session_id": session_id,
                        }
                        return
                    async for event in _iter_sse_events(resp):
                        mapped = _map_sse_event(event, session_id)
                        if mapped is not None:
                            yield mapped

                yield {
                    "type": "hermes.run.completed",
                    "session_id": session_id,
                    "profile": request.profile,
                }
        except aiohttp.ClientError as exc:
            logger.info("Hermes realtime-agent broker could not reach WebAPI: %s", exc)
            yield {
                "type": "voice.error",
                "message": f"Cannot reach Hermes WebAPI: {exc}",
            }

    async def _create_session(
        self,
        http: aiohttp.ClientSession,
        request: HermesTaskRequest,
        headers: dict[str, str],
    ) -> str:
        body: dict[str, Any] = {}
        if request.profile and request.profile != "default":
            body["profile"] = request.profile
        async with http.post(
            f"{self.webapi_url}/api/sessions",
            json=body,
            headers=headers,
        ) as resp:
            if resp.status not in (200, 201):
                text = await resp.text()
                raise aiohttp.ClientResponseError(
                    request_info=resp.request_info,
                    history=resp.history,
                    status=resp.status,
                    message=text[:240],
                    headers=resp.headers,
                )
            data = await resp.json()
        session_id = str(data.get("id") or data.get("session_id") or "").strip()
        if not session_id:
            raise aiohttp.ClientError("Hermes API created a session without an id")
        return session_id


_TOOL_SURFACE = ("hermes_run_task", "hermes_get_status", "hermes_cancel", "hermes_confirm")


def _headers(bearer_token: str | None) -> dict[str, str]:
    headers: dict[str, str] = {}
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    return headers


async def _iter_sse_events(resp: aiohttp.ClientResponse) -> AsyncIterator[dict[str, Any]]:
    buffer = ""
    current_type: str | None = None
    async for chunk_bytes in resp.content.iter_any():
        buffer += chunk_bytes.decode("utf-8", errors="replace")
        while "\n" in buffer:
            line, buffer = buffer.split("\n", 1)
            line = line.rstrip("\r")
            if not line:
                current_type = None
                continue
            if line.startswith("event:"):
                current_type = line[len("event:") :].strip()
                continue
            if not line.startswith("data:"):
                continue
            data_str = line[len("data:") :].strip()
            if not data_str or data_str == "[DONE]":
                continue
            try:
                payload = json.loads(data_str)
            except json.JSONDecodeError:
                continue
            if isinstance(payload, dict):
                if current_type and "type" not in payload:
                    payload["type"] = current_type
                elif current_type:
                    payload.setdefault("_sse_event_type", current_type)
                yield payload


def _map_sse_event(data: dict[str, Any], session_id: str) -> dict[str, Any] | None:
    raw_type = str(data.get("_sse_event_type") or data.get("type") or "").strip()
    if raw_type in {"assistant.delta", "content_delta", "delta"}:
        delta = _text(data.get("delta") or data.get("content"))
        if not delta:
            return None
        return {
            "type": "voice.response.delta",
            "session_id": session_id,
            "delta": delta,
        }
    if raw_type in {"thinking_delta", "reasoning_delta", "thinking", "tool.progress"}:
        delta = _text(data.get("delta") or data.get("thinking") or data.get("content"))
        if not delta:
            return None
        return {
            "type": "hermes.tool.delta",
            "session_id": session_id,
            "delta": delta,
            "tool_name": _tool_name(data),
        }
    if raw_type in {"tool.pending", "tool.started", "tool_start", "tool_started"}:
        return {
            "type": "hermes.tool.started",
            "session_id": session_id,
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "arguments": data.get("args") or data.get("arguments"),
        }
    if raw_type in {"tool.completed", "tool_result", "tool_completed"}:
        return {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "result_preview": _result_preview(data),
            "success": data.get("success", True),
        }
    if raw_type in {"tool.failed", "tool_error"}:
        return {
            "type": "hermes.tool.failed",
            "session_id": session_id,
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "error": _text(data.get("error") or data.get("message") or "Tool failed"),
        }
    if raw_type == "message.started":
        message = data.get("message")
        message_id = None
        if isinstance(message, dict):
            message_id = message.get("id")
        return {
            "type": "hermes.message.started",
            "session_id": session_id,
            "message_id": str(message_id or data.get("message_id") or data.get("id") or ""),
        }
    if raw_type in {"assistant.completed", "content_complete", "complete", "completed"}:
        content = _text(data.get("content"))
        event: dict[str, Any] = {
            "type": "voice.response.turn_completed",
            "session_id": session_id,
        }
        if content:
            event["content"] = content
        return event
    if raw_type == "error":
        return {
            "type": "voice.error",
            "session_id": session_id,
            "message": _text(data.get("message") or data.get("error") or "Hermes error"),
        }
    return None


def _tool_name(data: dict[str, Any]) -> str:
    tool = data.get("tool")
    if isinstance(tool, dict):
        nested = tool.get("name") or tool.get("tool_name")
        if nested:
            return str(nested)
    return str(data.get("tool_name") or data.get("name") or "hermes")


def _tool_call_id(data: dict[str, Any]) -> str:
    value = data.get("call_id") or data.get("tool_call_id") or data.get("id")
    if value:
        return str(value)
    return _tool_name(data)


def _result_preview(data: dict[str, Any]) -> str | None:
    for key in ("result_preview", "result", "content"):
        value = data.get(key)
        if value is None:
            continue
        if isinstance(value, str):
            return value[:2000]
        return json.dumps(value, sort_keys=True)[:2000]
    return None


def _text(value: Any) -> str:
    return value if isinstance(value, str) else ""


__all__ = ["HermesTaskRequest", "HermesToolBroker"]
