"""OpenAI provider-native realtime-agent connection."""

from __future__ import annotations

import base64
import json
import os
import urllib.parse
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from typing import Any, Protocol

import aiohttp

from ....voice_lab.auth import load_voice_lab_env_file
from ....voice_lab.providers.base import ProviderRunError, ProviderUnavailable

from ..models import (
    ProviderEvent,
    ProviderEventKind,
    RealtimeAgentCapabilities,
    RealtimeAgentSessionConfig,
    ToolCallEvent,
)
from .base import RealtimeAgentProviderAdapter

DEFAULT_MODEL = "gpt-realtime-2.1"
DEFAULT_TRANSCRIPTION_MODEL = "gpt-realtime-whisper"
DEFAULT_URL = "wss://api.openai.com/v1/realtime"
DEFAULT_TIMEOUT_SECONDS = 60.0
SUPPORTED_SAMPLE_RATE = 24000

_AUDIO_DELTA_EVENTS = {
    "response.output_audio.delta",
    "response.audio.delta",
}
_AUDIO_DONE_EVENTS = {
    "response.output_audio.done",
    "response.audio.done",
}
_INPUT_TRANSCRIPT_DELTA_EVENTS = {
    "conversation.item.input_audio_transcription.delta",
    "input_audio_transcription.delta",
}
_INPUT_TRANSCRIPT_FINAL_EVENTS = {
    "conversation.item.input_audio_transcription.completed",
    "conversation.item.input_audio_transcription.done",
    "input_audio_transcription.completed",
    "input_audio_transcription.done",
}
_OUTPUT_TEXT_DELTA_EVENTS = {
    "response.output_audio_transcript.delta",
    "response.audio_transcript.delta",
    "response.output_text.delta",
    "response.text.delta",
}
_FUNCTION_ITEM_DONE_EVENTS = {
    "response.output_item.done",
    "conversation.item.done",
}


class OpenAIProviderSocket(Protocol):
    async def send_json(self, payload: dict[str, Any]) -> None: ...

    async def receive_json(self) -> dict[str, Any]: ...

    async def close(self) -> None: ...


SocketFactory = Callable[[str, dict[str, str], float], Any]


@dataclass(frozen=True, slots=True)
class AuthToken:
    value: str
    source: str


class OpenAIRealtimeAgentAdapter(RealtimeAgentProviderAdapter):
    """Render-only OpenAI adapter kept for the non-native fallback path."""

    provider_id = "openai_realtime"


class OpenAIRealtimeAgentProvider:
    """Connection-oriented OpenAI Realtime provider for Hermes Realtime Agent."""

    provider_id = "openai_realtime"
    capabilities = RealtimeAgentCapabilities(provider_id=provider_id)

    def __init__(self, socket_factory: SocketFactory | None = None) -> None:
        self._socket_factory = socket_factory or _create_aiohttp_websocket

    async def connect(
        self,
        config: RealtimeAgentSessionConfig,
    ) -> "OpenAIRealtimeAgentConnection":
        if config.sample_rate != SUPPORTED_SAMPLE_RATE:
            raise ProviderUnavailable(
                "OpenAI Realtime currently supports sample_rate=24000 for "
                "provider-native Realtime Agent sessions."
            )
        auth = _resolve_auth_token(config.provider_options)
        if auth is None:
            raise ProviderUnavailable(
                "OpenAI Realtime auth is not configured. Set relay-owned "
                "OPENAI_API_KEY, VOICE_TOOLS_OPENAI_KEY, or an openai_realtime "
                "provider api_key before using Realtime Agent."
            )
        timeout = _float_option(
            config.provider_options,
            "timeout",
            DEFAULT_TIMEOUT_SECONDS,
        )
        base_url = (
            _option(config.provider_options, "url")
            or os.getenv("OPENAI_REALTIME_URL")
            or DEFAULT_URL
        ).rstrip("/")
        url = _url_with_model(base_url, config.model or DEFAULT_MODEL)
        headers = {"Authorization": f"Bearer {auth.value}"}
        safety_identifier = _option(config.provider_options, "safety_identifier")
        if safety_identifier:
            headers["OpenAI-Safety-Identifier"] = safety_identifier

        try:
            socket = await self._socket_factory(url, headers, timeout)
        except aiohttp.WSServerHandshakeError as exc:
            if exc.status in {401, 403}:
                raise ProviderUnavailable(
                    "OpenAI Realtime rejected the relay auth "
                    f"({exc.status}; source: {auth.source}). Update the "
                    "relay-side OpenAI realtime provider credentials."
                ) from exc
            raise ProviderUnavailable(
                f"OpenAI Realtime websocket handshake failed with HTTP {exc.status}"
            ) from exc
        connection = OpenAIRealtimeAgentConnection(
            socket=socket,
            config=config,
            auth_source=auth.source,
        )
        await connection.configure()
        return connection


class OpenAIRealtimeAgentConnection:
    def __init__(
        self,
        *,
        socket: OpenAIProviderSocket,
        config: RealtimeAgentSessionConfig,
        auth_source: str,
    ) -> None:
        self.socket = socket
        self.config = config
        self.auth_source = auth_source
        self._completed_function_call_ids: set[str] = set()

    async def configure(self) -> None:
        await self.socket.send_json(_session_update(self.config))

    async def send_audio(self, pcm: bytes, sample_rate: int) -> None:
        pcm = _resample_pcm16_mono(pcm, sample_rate, self.config.sample_rate)
        await self.socket.send_json(
            {
                "type": "input_audio_buffer.append",
                "audio": base64.b64encode(pcm).decode("ascii"),
            }
        )

    async def commit_audio(self) -> None:
        await self.socket.send_json({"type": "input_audio_buffer.commit"})

    async def send_text(self, text: str) -> None:
        await self.socket.send_json(
            {
                "type": "conversation.item.create",
                "item": {
                    "type": "message",
                    "role": "user",
                    "content": [
                        {
                            "type": "input_text",
                            "text": text,
                        }
                    ],
                },
            }
        )
        await self.socket.send_json({"type": "response.create"})

    async def clear_audio(self) -> None:
        await self.socket.send_json({"type": "input_audio_buffer.clear"})

    async def cancel_response(self) -> None:
        await self.socket.send_json({"type": "response.cancel"})

    async def send_tool_result(self, call_id: str, output: dict[str, Any]) -> None:
        await self.socket.send_json(
            {
                "type": "conversation.item.create",
                "item": {
                    "type": "function_call_output",
                    "call_id": call_id,
                    "output": json.dumps(output, sort_keys=True, separators=(",", ":")),
                },
            }
        )

    async def request_response(self, *, instructions: str | None = None) -> None:
        payload: dict[str, Any] = {"type": "response.create"}
        if instructions:
            # Per-response instructions override the session-level system
            # prompt for this response only — see xai.py's matching method
            # for why this replaces the fake-user-message injection hack.
            payload["response"] = {"instructions": instructions}
        await self.socket.send_json(payload)

    async def close(self) -> None:
        await self.socket.close()

    async def events(self) -> AsyncIterator[ProviderEvent]:
        while True:
            try:
                raw = await self.socket.receive_json()
            except (EOFError, StopAsyncIteration):
                return
            except ProviderRunError as exc:
                yield ProviderEvent(
                    ProviderEventKind.ERROR,
                    payload={"message": str(exc)},
                )
                return
            event = _provider_event(raw, self._completed_function_call_ids)
            if event is not None:
                yield event


class _AioHttpOpenAIProviderSocket:
    def __init__(
        self,
        session: aiohttp.ClientSession,
        ws: aiohttp.ClientWebSocketResponse,
    ) -> None:
        self._session = session
        self._ws = ws

    async def send_json(self, payload: dict[str, Any]) -> None:
        await self._ws.send_str(json.dumps(payload, separators=(",", ":")))

    async def receive_json(self) -> dict[str, Any]:
        msg = await self._ws.receive()
        if msg.type == aiohttp.WSMsgType.TEXT:
            try:
                payload = json.loads(msg.data)
            except json.JSONDecodeError as exc:
                raise ProviderRunError("OpenAI Realtime returned non-JSON text") from exc
            if not isinstance(payload, dict):
                raise ProviderRunError("OpenAI Realtime returned a non-object event")
            return payload
        if msg.type == aiohttp.WSMsgType.BINARY:
            raise ProviderRunError("OpenAI Realtime returned an unexpected binary frame")
        if msg.type == aiohttp.WSMsgType.ERROR:
            raise ProviderRunError(
                f"OpenAI Realtime websocket error: {self._ws.exception()}"
            )
        raise EOFError

    async def close(self) -> None:
        try:
            await self._ws.close()
        finally:
            await self._session.close()


async def _create_aiohttp_websocket(
    url: str,
    headers: dict[str, str],
    timeout: float,
) -> OpenAIProviderSocket:
    # Liveness via WS heartbeat rather than an ambient total-timeout — realtime
    # sessions legitimately live for many minutes; `timeout` bounds the connect.
    session = aiohttp.ClientSession(
        timeout=aiohttp.ClientTimeout(total=None, connect=timeout, sock_connect=timeout)
    )
    try:
        ws = await session.ws_connect(url, headers=headers, heartbeat=20.0)
    except aiohttp.WSServerHandshakeError as exc:
        await session.close()
        if exc.status in {401, 403}:
            raise ProviderUnavailable(
                "OpenAI Realtime rejected the relay auth. Update the relay-side "
                "OpenAI realtime provider credentials."
            ) from exc
        raise ProviderUnavailable(
            f"OpenAI Realtime websocket handshake failed with HTTP {exc.status}"
        ) from exc
    except Exception:
        await session.close()
        raise
    return _AioHttpOpenAIProviderSocket(session, ws)


def _session_update(config: RealtimeAgentSessionConfig) -> dict[str, Any]:
    transcription_model = _option(config.provider_options, "transcription_model")
    transcription_disabled = _bool_false_option(
        config.provider_options,
        "input_transcription_enabled",
    )
    input_audio: dict[str, Any] = {
        "format": {
            "type": "audio/pcm",
            "rate": config.sample_rate,
        },
        "turn_detection": None,
    }
    if not transcription_disabled:
        transcription: dict[str, Any] = {
            "model": transcription_model or DEFAULT_TRANSCRIPTION_MODEL,
        }
        language = _option(config.provider_options, "transcription_language")
        if language:
            transcription["language"] = language
        prompt = _option(config.provider_options, "transcription_prompt")
        if prompt and transcription["model"] != DEFAULT_TRANSCRIPTION_MODEL:
            transcription["prompt"] = prompt
        input_audio["transcription"] = transcription

    return {
        "type": "session.update",
        "session": {
            "type": "realtime",
            "model": config.model or DEFAULT_MODEL,
            "instructions": config.instructions or _default_instructions(config),
            "output_modalities": ["audio"],
            "audio": {
                "input": input_audio,
                "output": {
                    "format": {
                        "type": "audio/pcm",
                    },
                    "voice": config.voice,
                },
            },
            "tools": [_openai_function_tool(tool) for tool in config.tools],
            "tool_choice": "auto",
        },
    }


def _openai_function_tool(schema: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "function",
        "name": str(schema.get("name") or ""),
        "description": str(schema.get("description") or ""),
        "parameters": dict(schema.get("parameters") or {}),
    }


def _provider_event(
    event: dict[str, Any],
    completed_function_call_ids: set[str],
) -> ProviderEvent | None:
    event_type = str(event.get("type") or "").strip()
    response_id = _response_id(event)
    if event_type in {"session.created", "session.updated", "conversation.created"}:
        # Surface the RESOLVED model id so session logs record which model
        # actually served (aliases/snapshots can move server-side).
        session_info = event.get("session")
        resolved_model = (
            str(session_info.get("model") or "").strip()
            if isinstance(session_info, dict)
            else ""
        )
        return ProviderEvent(
            ProviderEventKind.READY,
            response_id=response_id,
            payload={
                "provider_event_type": event_type,
                "resolved_model": resolved_model or None,
            },
        )
    if event_type in {"response.created", "response.output_item.added"}:
        return ProviderEvent(
            ProviderEventKind.RESPONSE_STARTED,
            response_id=response_id,
            payload={"provider_event_type": event_type},
        )
    if event_type in _INPUT_TRANSCRIPT_DELTA_EVENTS:
        delta = _text(event.get("delta") or event.get("transcript") or event.get("text"))
        if delta:
            return ProviderEvent(
                ProviderEventKind.INPUT_TRANSCRIPT_DELTA,
                response_id=response_id,
                payload={"delta": delta},
            )
        return None
    if event_type in _INPUT_TRANSCRIPT_FINAL_EVENTS:
        text = _text(event.get("transcript") or event.get("text") or event.get("delta"))
        if text:
            return ProviderEvent(
                ProviderEventKind.INPUT_TRANSCRIPT_FINAL,
                response_id=response_id,
                payload={"text": text},
            )
        return None
    if event_type in _OUTPUT_TEXT_DELTA_EVENTS:
        delta = _text(event.get("delta") or event.get("text"))
        if delta:
            return ProviderEvent(
                ProviderEventKind.OUTPUT_TEXT_DELTA,
                response_id=response_id,
                payload={"delta": delta},
            )
        return None
    if event_type in _AUDIO_DELTA_EVENTS:
        chunk, audio64 = _decode_audio_delta(event)
        return ProviderEvent(
            ProviderEventKind.AUDIO_DELTA,
            response_id=response_id,
            payload={
                "audio": chunk,
                "audio_base64": audio64,
                "byte_count": len(chunk),
            },
            metadata={"provider_event_type": event_type},
        )
    if event_type in _AUDIO_DONE_EVENTS:
        return ProviderEvent(
            ProviderEventKind.AUDIO_DONE,
            response_id=response_id,
            payload={"provider_event_type": event_type},
        )
    if event_type == "response.function_call_arguments.delta":
        return ProviderEvent(
            ProviderEventKind.FUNCTION_CALL_ARGUMENTS_DELTA,
            response_id=response_id,
            payload={
                "call_id": _call_id(event),
                "name": _tool_name(event),
                "delta": _text(event.get("delta") or event.get("arguments")),
            },
        )
    if event_type == "response.function_call_arguments.done":
        event = _event_with_response_output_fallback(event)
        call_id = _call_id(event)
        if call_id:
            completed_function_call_ids.add(call_id)
        return _function_call_event(event, response_id=response_id)
    if event_type in _FUNCTION_ITEM_DONE_EVENTS:
        item = event.get("item")
        if isinstance(item, dict) and item.get("type") == "function_call":
            call_id = _call_id(item)
            if call_id and call_id not in completed_function_call_ids:
                completed_function_call_ids.add(call_id)
                return _function_call_event(item, response_id=response_id)
    if event_type == "response.done":
        function_call = _first_unseen_function_call(event, completed_function_call_ids)
        if function_call is not None:
            call_id = _call_id(function_call)
            if call_id:
                completed_function_call_ids.add(call_id)
            return _function_call_event(function_call, response_id=response_id)
        return ProviderEvent(
            ProviderEventKind.RESPONSE_DONE,
            response_id=response_id,
            payload={"provider_event_type": event_type},
        )
    if event_type == "error":
        return ProviderEvent(
            ProviderEventKind.ERROR,
            response_id=response_id,
            payload={"message": _format_openai_error(event)},
        )
    return None


def _function_call_event(event: dict[str, Any], *, response_id: str) -> ProviderEvent:
    name = _tool_name(event)
    arguments = _arguments(event)
    call_id = _call_id(event)
    return ProviderEvent(
        ProviderEventKind.FUNCTION_CALL_COMPLETED,
        response_id=response_id,
        payload={
            "call": ToolCallEvent(
                call_id=call_id,
                name=name,
                arguments=arguments,
            ),
            "call_id": call_id,
            "name": name,
            "arguments": arguments,
        },
    )


def _event_with_response_output_fallback(event: dict[str, Any]) -> dict[str, Any]:
    if _tool_name(event) or event.get("arguments"):
        return event
    function_call = _first_function_call(event)
    if function_call is None:
        return event
    merged = dict(function_call)
    for key in ("response_id", "event_id"):
        if key in event:
            merged.setdefault(key, event[key])
    return merged


def _first_unseen_function_call(
    event: dict[str, Any],
    completed_function_call_ids: set[str],
) -> dict[str, Any] | None:
    for function_call in _function_calls(event):
        call_id = _call_id(function_call)
        if not call_id or call_id not in completed_function_call_ids:
            return function_call
    return None


def _first_function_call(event: dict[str, Any]) -> dict[str, Any] | None:
    return next(iter(_function_calls(event)), None)


def _function_calls(event: dict[str, Any]) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []
    response = event.get("response")
    if isinstance(response, dict):
        output = response.get("output")
        if isinstance(output, list):
            calls.extend(
                item
                for item in output
                if isinstance(item, dict) and item.get("type") == "function_call"
            )
    item = event.get("item")
    if isinstance(item, dict) and item.get("type") == "function_call":
        calls.append(item)
    if event.get("type") == "function_call":
        calls.append(event)
    return calls


def _decode_audio_delta(event: dict[str, Any]) -> tuple[bytes, str]:
    audio64 = _text(event.get("delta") or event.get("audio"))
    if not audio64:
        raise ProviderRunError("OpenAI Realtime audio delta missing base64 payload")
    try:
        return base64.b64decode(audio64), audio64
    except Exception as exc:
        raise ProviderRunError("OpenAI Realtime audio delta was not valid base64") from exc


def _arguments(event: dict[str, Any]) -> dict[str, Any]:
    value = event.get("arguments")
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {"_raw": value}
        if isinstance(parsed, dict):
            return parsed
        return {"value": parsed}
    return {}


def _call_id(event: dict[str, Any]) -> str:
    return str(
        event.get("call_id")
        or event.get("item_id")
        or event.get("id")
        or event.get("event_id")
        or ""
    ).strip()


def _tool_name(event: dict[str, Any]) -> str:
    return str(
        event.get("name")
        or event.get("function_name")
        or event.get("tool_name")
        or ""
    ).strip()


def _response_id(event: dict[str, Any]) -> str:
    response = event.get("response")
    if isinstance(response, dict) and response.get("id"):
        return str(response["id"])
    item = event.get("item")
    if isinstance(item, dict) and item.get("response_id"):
        return str(item["response_id"])
    return str(event.get("response_id") or "").strip()


def _format_openai_error(event: dict[str, Any]) -> str:
    error = event.get("error")
    if isinstance(error, dict):
        message = error.get("message") or error.get("code") or error.get("type")
        if message:
            return f"OpenAI Realtime error: {message}"
    return f"OpenAI Realtime error event: {event}"


def _default_instructions(config: RealtimeAgentSessionConfig) -> str:
    profile = config.profile or "active Hermes profile"
    return (
        "You are the voice front-end for Hermes Relay. Use the provided Hermes "
        "functions for user requests that need memory, tools, device or desktop "
        "actions, confirmations, persistent context, research, current facts, "
        "news, external data, live checks, latest/versioned info, personal or "
        "project context, side effects, precision-sensitive answers, or media and "
        "artifact handling. Hermes is the durable conversation memory; use any "
        "seeded recent chat context for follow-up references when it is enough, "
        "and route missing/stale/verification-sensitive references through "
        "Hermes before answering. Do not say you lack context before a Hermes "
        "call. You may speak one brief acknowledgement such as 'I'll check "
        "Hermes' or 'I'll check that' before the tool call, then call Hermes "
        "immediately. Do not give a substantive answer until Hermes returns. "
        "Android will provide restrained local status while Hermes runs. "
        "When speaking, summarize dense machine-readable "
        "values instead of reading raw IDs, URLs, paths, JSON, logs, or long "
        f"numbers character by character. Active profile: {profile}. "
        "Do not call web_search, x_search, MCP, or any non-Hermes tool."
    )


def _resolve_auth_token(options: dict[str, Any]) -> AuthToken | None:
    for name in ("api_key", "openai_api_key", "client_secret"):
        explicit = _option(options, name)
        if explicit:
            return AuthToken(value=explicit, source=f"provider-option:{name}")
    load_voice_lab_env_file()
    for name in ("OPENAI_REALTIME_API_KEY", "OPENAI_API_KEY", "VOICE_TOOLS_OPENAI_KEY"):
        value = os.getenv(name, "").strip()
        if value:
            return AuthToken(value=value, source=f"env:{name}")
    return None


def _url_with_model(base_url: str, model: str) -> str:
    separator = "&" if "?" in base_url else "?"
    encoded_model = urllib.parse.quote(model, safe="")
    return f"{base_url}{separator}model={encoded_model}"


def _option(options: dict[str, Any], name: str) -> str | None:
    value = options.get(name)
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _bool_false_option(options: dict[str, Any], name: str) -> bool:
    value = options.get(name)
    if isinstance(value, bool):
        return not value
    if value is None:
        return False
    return str(value).strip().lower() in {"0", "false", "no", "off", "disabled"}


def _float_option(options: dict[str, Any], name: str, default: float) -> float:
    value = _option(options, name)
    if not value:
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise ProviderUnavailable(f"{name} must be a number") from exc


def _text(value: Any) -> str:
    return value if isinstance(value, str) else ""


def _resample_pcm16_mono(pcm: bytes, source_rate: int, target_rate: int) -> bytes:
    if not pcm or source_rate <= 0 or target_rate <= 0 or source_rate == target_rate:
        return pcm
    sample_count = len(pcm) // 2
    if sample_count <= 1:
        return pcm
    target_count = max(1, round(sample_count * target_rate / source_rate))
    out = bytearray(target_count * 2)
    for i in range(target_count):
        source_index = min(sample_count - 1, round(i * source_rate / target_rate))
        start = source_index * 2
        out[i * 2 : i * 2 + 2] = pcm[start : start + 2]
    return bytes(out)
