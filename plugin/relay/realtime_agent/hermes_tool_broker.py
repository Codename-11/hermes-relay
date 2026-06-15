"""Hermes session/tool broker used by realtime voice agent mode."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, AsyncIterator

import aiohttp

logger = logging.getLogger(__name__)


class HermesApiAuthError(aiohttp.ClientError):
    def __init__(self, status: int, *, has_bearer: bool) -> None:
        self.status = status
        self.has_bearer = has_bearer
        super().__init__(f"Hermes broker auth failed ({status})")


@dataclass(frozen=True, slots=True)
class HermesTaskRequest:
    text: str
    profile: str | None
    session_id: str | None
    bearer_token: str | None = None
    interface_context: dict[str, Any] | None = None


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
                    "interface": request.interface_context,
                }

                body: dict[str, Any] = {"message": request.text}
                interface_system_message = _interface_system_message(request.interface_context)
                if interface_system_message:
                    body["system_message"] = interface_system_message
                if request.profile and request.profile != "default":
                    body["profile"] = request.profile
                url = f"{self.webapi_url}/api/sessions/{session_id}/chat/stream"
                async with http.post(
                    url,
                    json=body,
                    headers={**headers, "Accept": "text/event-stream"},
                ) as resp:
                    if resp.status in (401, 403):
                        await resp.read()
                        yield _auth_error_event(
                            resp.status,
                            session_id=session_id,
                            has_bearer=bool(headers.get("Authorization")),
                        )
                        return
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
        except HermesApiAuthError as exc:
            yield _auth_error_event(
                exc.status,
                session_id=request.session_id,
                has_bearer=exc.has_bearer,
            )
        except aiohttp.ClientError as exc:
            logger.info("Hermes realtime-agent broker could not reach WebAPI: %s", exc)
            yield {
                "type": "voice.error",
                "message": f"Cannot reach Hermes WebAPI: {exc}",
            }

    async def fetch_context_messages(
        self,
        *,
        session_id: str,
        bearer_token: str | None,
        limit: int = 14,
    ) -> tuple[dict[str, str], ...]:
        if not session_id:
            return ()
        timeout = aiohttp.ClientTimeout(total=5, connect=3)
        headers = _headers(bearer_token)
        try:
            async with aiohttp.ClientSession(timeout=timeout) as http:
                async with http.get(
                    f"{self.webapi_url}/api/sessions/{session_id}/messages",
                    headers=headers,
                ) as resp:
                    if resp.status != 200:
                        await resp.read()
                        return ()
                    payload = await resp.json()
        except Exception as exc:
            logger.debug("Hermes context fetch failed for %s: %s", session_id, exc)
            return ()
        if isinstance(payload, dict):
            items = payload.get("data") or payload.get("items") or payload.get("messages")
        else:
            items = None
        if not isinstance(items, list):
            return ()
        messages: list[dict[str, str]] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role") or item.get("type") or "").strip().lower()
            if role not in {"user", "assistant", "system"}:
                continue
            content = _message_content_text(item)
            if not content:
                continue
            messages.append({"role": role, "content": content, "source": "hermes_session"})
        return tuple(messages[-max(1, limit):])

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
            if resp.status in (401, 403):
                await resp.read()
                raise HermesApiAuthError(
                    resp.status,
                    has_bearer=bool(headers.get("Authorization")),
                )
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


def _auth_error_event(
    status: int,
    *,
    session_id: str | None,
    has_bearer: bool,
) -> dict[str, Any]:
    if has_bearer:
        detail = "relay-side Hermes credential was rejected"
    else:
        detail = "no relay-side Hermes credential is configured"
    event: dict[str, Any] = {
        "type": "voice.error",
        "message": f"Hermes broker auth failed ({status}): {detail}.",
        "error_code": "hermes_broker_auth_failed",
    }
    if session_id:
        event["session_id"] = session_id
    return event


def _interface_system_message(context: dict[str, Any] | None) -> str | None:
    if not context:
        return None

    engine = _text(context.get("engine") or "realtime_agent")
    engine_label = _text(context.get("engine_label") or "Realtime Agent")
    provider = _text(context.get("provider") or "unknown")
    model = _text(context.get("model") or "unknown")
    voice = _text(context.get("voice") or "unknown")
    profile = _text(context.get("profile") or "default")
    stable_engine = _text(context.get("stable_engine") or "hermes_voice_output")
    stable_label = _text(context.get("stable_engine_label") or "Hermes chat + voice output")
    path_summary = _text(context.get("path_summary"))
    current_date = _text(context.get("current_date"))
    current_time = _text(context.get("current_time"))
    current_timezone = _text(context.get("current_timezone"))
    stable_line = (
        f"- This is the stable {stable_label} path."
        if engine == stable_engine
        else f"- This is not the stable {stable_label} path unless the active engine says so."
    )

    lines = [
        "Hermes Relay interface context for this turn:",
        f"- Active voice engine: {engine_label} ({engine}).",
        f"- Active provider path: provider={provider}, model={model}, voice={voice}, profile={profile}.",
        stable_line,
    ]
    if path_summary:
        lines.append(f"- Active route: {path_summary}.")
    if current_date:
        time_part = f" {current_time}" if current_time else ""
        zone_part = f" {current_timezone}" if current_timezone else ""
        lines.append(f"- Current relay date/time: {current_date}{time_part}{zone_part}.")
        lines.append(
            "If the user asks for today's date or current time, answer from this relay-local context."
        )
    lines.append(
        "If the user asks which interface, path, or mode is active, answer from this context."
    )
    lines.append(
        "Handle research, current facts, news, external data, and live app/desktop/phone checks here rather than leaving the realtime provider to guess."
    )
    lines.append(
        "Also handle latest/versioned info, personal/session/project context, side effects, high-stakes or precision-sensitive answers, explicit check/verify/look-up requests, and media/files/screenshots/attachments/artifacts."
    )
    lines.append(
        "Return concise task results for the realtime provider to summarize; do not optimize for raw spoken output."
    )
    lines.append(
        "Prefer speech-safe summaries for dates, times, numbers, versions, currency, units, paths, URLs, IDs, JSON, logs, tables, and other dense machine output."
    )
    return "\n".join(lines)


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
            "run_id": _run_id(data),
            "delta": delta,
        }
    if raw_type in {"run.started", "response.created"}:
        return {
            "type": "hermes.run.started",
            "session_id": session_id,
            "run_id": _run_id(data),
        }
    if raw_type in {
        "thinking_delta",
        "reasoning_delta",
        "thinking",
        "tool.progress",
        "reasoning.available",
    }:
        delta = _text(data.get("delta") or data.get("thinking") or data.get("content"))
        if not delta:
            delta = _text(data.get("text") or data.get("message"))
        if not delta:
            return None
        return {
            "type": "hermes.tool.delta",
            "session_id": session_id,
            "run_id": _run_id(data),
            "delta": delta,
            "tool_name": _tool_name(data),
        }
    if raw_type in {"memory.updated", "skill.loaded", "artifact.created"}:
        return {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "run_id": _run_id(data),
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "result_preview": _result_preview(data),
            "success": True,
        }
    if raw_type in {"tool.pending", "tool.started", "tool_start", "tool_started"}:
        return {
            "type": "hermes.tool.started",
            "session_id": session_id,
            "run_id": _run_id(data),
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "arguments": data.get("args") or data.get("arguments"),
        }
    if raw_type in {"tool.completed", "tool_result", "tool_completed"}:
        return {
            "type": "hermes.tool.completed",
            "session_id": session_id,
            "run_id": _run_id(data),
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "result_preview": _result_preview(data),
            "success": data.get("success", True),
        }
    if raw_type in {"tool.failed", "tool_error"}:
        return {
            "type": "hermes.tool.failed",
            "session_id": session_id,
            "run_id": _run_id(data),
            "tool_call_id": _tool_call_id(data),
            "tool_name": _tool_name(data),
            "error": _text(data.get("error") or data.get("message") or "Tool failed"),
        }
    if raw_type in {"confirmation.requested", "tool.confirmation_requested", "confirmation.required"}:
        return {
            "type": "hermes.confirmation.requested",
            "session_id": session_id,
            "run_id": _run_id(data),
            "confirmation_id": _confirmation_id(data),
            "message": _text(data.get("message") or data.get("prompt") or "Waiting for confirmation"),
        }
    if raw_type == "message.started":
        message = data.get("message")
        message_id = None
        if isinstance(message, dict):
            message_id = message.get("id")
        return {
            "type": "hermes.message.started",
            "session_id": session_id,
            "run_id": _run_id(data),
            "message_id": str(message_id or data.get("message_id") or data.get("id") or ""),
        }
    if raw_type in {"assistant.completed", "content_complete", "complete", "completed"}:
        content = _text(data.get("content"))
        event: dict[str, Any] = {
            "type": "voice.response.turn_completed",
            "session_id": session_id,
            "run_id": _run_id(data),
        }
        if content:
            event["content"] = content
        return event
    if raw_type in {"run.completed", "response.completed", "done"}:
        return {
            "type": "hermes.run.completed",
            "session_id": session_id,
            "run_id": _run_id(data),
        }
    if raw_type == "error":
        return {
            "type": "voice.error",
            "session_id": session_id,
            "run_id": _run_id(data),
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


def _run_id(data: dict[str, Any]) -> str:
    value = data.get("run_id")
    if value:
        return str(value)
    response = data.get("response")
    if isinstance(response, dict) and response.get("id"):
        return str(response["id"])
    return ""


def _confirmation_id(data: dict[str, Any]) -> str:
    value = data.get("confirmation_id") or data.get("id") or data.get("call_id")
    if value:
        return str(value)
    return _tool_call_id(data)


def _result_preview(data: dict[str, Any]) -> str | None:
    for key in ("result_preview", "result", "content", "output", "message", "preview", "text"):
        value = data.get(key)
        if value is None:
            continue
        if isinstance(value, str):
            return value[:2000]
        return json.dumps(value, sort_keys=True)[:2000]
    compact = {
        key: value
        for key, value in data.items()
        if key
        in {
            "tool_name",
            "name",
            "path",
            "target",
            "entry_count",
            "success",
            "duration",
        }
        and value is not None
    }
    if compact:
        return json.dumps(compact, sort_keys=True)[:2000]
    return None


def _text(value: Any) -> str:
    return value if isinstance(value, str) else ""


def _message_content_text(item: dict[str, Any]) -> str:
    value = (
        item.get("content")
        or item.get("text")
        or item.get("message")
        or item.get("final_response")
    )
    if isinstance(value, str):
        return " ".join(value.strip().split())[:1500]
    if isinstance(value, list):
        parts: list[str] = []
        for part in value:
            if isinstance(part, str):
                parts.append(part)
            elif isinstance(part, dict):
                text = part.get("text") or part.get("content")
                if isinstance(text, str):
                    parts.append(text)
        return " ".join(" ".join(parts).split())[:1500]
    return ""


__all__ = ["HermesTaskRequest", "HermesToolBroker"]
